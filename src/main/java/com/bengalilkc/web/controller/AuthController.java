package com.bengalilkc.web.controller;

import com.bengalilkc.security.SecurityUtils;
import com.bengalilkc.security.UserPrincipal;
import com.bengalilkc.service.auth.AuthService;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import lombok.RequiredArgsConstructor;
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
        return authService.login(str(body.get("username")), str(body.get("password")), response);
    }

    @PostMapping("/logout")
    public Map<String, Object> logout(HttpServletResponse response) {
        return authService.logout(response);
    }

    @PostMapping("/password-otp")
    public Map<String, Object> passwordOtp(@RequestBody Map<String, Object> body) {
        UserPrincipal principal = SecurityUtils.requirePrincipal();
        return authService.requestPasswordOtp(principal, str(body.get("currentPassword")));
    }

    @PostMapping("/change-password")
    public Map<String, Object> changePassword(@RequestBody Map<String, Object> body) {
        UserPrincipal principal = SecurityUtils.requirePrincipal();
        return authService.changePassword(
                principal, str(body.get("currentPassword")), str(body.get("otp")), str(body.get("newPassword")));
    }

    @PostMapping("/forgot-password")
    public Map<String, Object> forgotPassword(@RequestBody Map<String, Object> body) {
        return authService.forgotPassword(str(body.get("identifier")));
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
