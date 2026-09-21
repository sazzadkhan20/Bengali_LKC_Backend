package com.bengalilkc.web.controller;

import com.bengalilkc.security.SecurityUtils;
import com.bengalilkc.security.UserPrincipal;
import com.bengalilkc.service.auth.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/login")
    public Map<String, Object> login(@RequestBody Map<String, Object> body, HttpServletResponse response) {
        // Accept username, email, or identifier — all resolve to the same account lookup.
        String identifier = firstNonBlank(
                str(body.get("username")),
                str(body.get("identifier")),
                str(body.get("email")));
        return authService.login(identifier, str(body.get("password")), response);
    }

    @PostMapping("/refresh")
    public Map<String, Object> refresh(HttpServletRequest request, HttpServletResponse response) {
        return authService.refresh(request, response);
    }

    /**
     * Session payload used by the frontend after login and on every authenticated page load.
     * Includes profile, permissions, standing, and access-token expiry (1 hour by default).
     */
    @GetMapping("/session")
    public Map<String, Object> session(HttpServletRequest request) {
        UserPrincipal principal = SecurityUtils.requirePrincipal();
        return authService.session(principal, request);
    }

    @PostMapping("/logout")
    public Map<String, Object> logout(HttpServletRequest request, HttpServletResponse response) {
        return authService.logout(request, response);
    }

    @PostMapping("/password-otp")
    public Map<String, Object> passwordOtp(@RequestBody Map<String, Object> body) {
        UserPrincipal principal = SecurityUtils.requirePrincipal();
        return authService.requestPasswordOtp(principal, str(body.get("currentPassword")));
    }

    @PostMapping("/change-password")
    public Map<String, Object> changePassword(
            @RequestBody Map<String, Object> body, HttpServletResponse response) {
        UserPrincipal principal = SecurityUtils.requirePrincipal();
        return authService.changePassword(
                principal,
                str(body.get("currentPassword")),
                str(body.get("otp")),
                str(body.get("newPassword")),
                response);
    }

    @PostMapping("/forgot-password")
    public Map<String, Object> forgotPassword(@RequestBody Map<String, Object> body) {
        String identifier = firstNonBlank(
                str(body.get("identifier")),
                str(body.get("username")),
                str(body.get("email")));
        return authService.forgotPassword(identifier);
    }

    @PostMapping("/forgot-password/confirm")
    public Map<String, Object> confirmForgotPassword(@RequestBody Map<String, Object> body) {
        String identifier = firstNonBlank(
                str(body.get("identifier")),
                str(body.get("username")),
                str(body.get("email")));
        return authService.confirmForgotPassword(
                identifier, str(body.get("otp")), str(body.get("newPassword")));
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }
}
