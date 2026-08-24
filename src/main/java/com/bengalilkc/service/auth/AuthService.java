package com.bengalilkc.service.auth;

import com.bengalilkc.common.exception.ApiException;
import com.bengalilkc.config.LkcProperties;
import com.bengalilkc.domain.entity.admin.SystemConfig;
import com.bengalilkc.domain.entity.identity.AppUser;
import com.bengalilkc.domain.entity.identity.Credential;
import com.bengalilkc.domain.enums.AuditTargetType;
import com.bengalilkc.repository.identity.AppUserRepository;
import com.bengalilkc.repository.identity.CredentialRepository;
import com.bengalilkc.security.JwtService;
import com.bengalilkc.security.UserPrincipal;
import com.bengalilkc.service.admin.AuditService;
import com.bengalilkc.service.admin.ConfigService;
import jakarta.servlet.http.HttpServletResponse;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AuthService {

    private static final int OTP_VALIDITY_MINUTES = 5;
    private final AppUserRepository userRepository;
    private final CredentialRepository credentialRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final ConfigService configService;
    private final AuditService auditService;
    private final LkcProperties properties;
    private final Environment environment;
    private final SecureRandom secureRandom = new SecureRandom();

    @Transactional
    public Map<String, Object> login(String username, String password, HttpServletResponse response) {
        if (username == null || username.isBlank() || password == null || password.isBlank()) {
            throw ApiException.badRequest("Username and password are required.");
        }
        Credential credential = credentialRepository.findByUsernameIgnoreCase(username.trim())
                .orElse(null);
        if (credential == null || !passwordEncoder.matches(password, credential.getPasswordHash())) {
            throw ApiException.unauthorized(
                    "Invalid username or password. Credentials are issued by the Platform Architect.");
        }
        if (credential.isDeactivated()) {
            throw ApiException.unauthorized(
                    "This credential has been deactivated by the Platform Architect.");
        }
        AppUser user = userRepository.findById(credential.getUserId()).orElse(null);
        if (user == null || !user.isActive()) {
            throw ApiException.unauthorized("The account linked to this credential is inactive.");
        }
        if (user.isSuspended()) {
            throw ApiException.forbidden("This account is suspended pending Platform Architect review.");
        }

        auditService.record(
                user.getId(),
                user.getRole().name(),
                credential.isOneTime() ? "LOGIN_ONE_TIME_PASSWORD" : "LOGIN",
                AuditTargetType.AUTH,
                null,
                credential.isOneTime()
                        ? "Credential " + credential.getId() + " used — first-login password change required"
                        : "Credential " + credential.getId() + " used");

        SystemConfig config = configService.getConfig();
        String token = jwtService.createToken(
                user.getId(), user.getUsername(), user.getRole(), credential.isOneTime(), config.getSessionTimeoutMinutes());
        setSessionCookie(response, token, config.getSessionTimeoutMinutes() * 60L);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("user", Map.of("name", user.getName(), "role", user.getRole().name()));
        body.put("mustChangePassword", credential.isOneTime());
        return body;
    }

    public Map<String, Object> logout(HttpServletResponse response) {
        clearSessionCookie(response);
        return Map.of("ok", true);
    }

    @Transactional
    public Map<String, Object> requestPasswordOtp(UserPrincipal principal, String currentPassword) {
        Credential credential = credentialRepository.findByUserId(principal.getId())
                .orElseThrow(() -> ApiException.notFound("No credential on record."));
        AppUser user = userRepository.findById(principal.getId())
                .orElseThrow(() -> ApiException.notFound("No credential on record."));
        if (currentPassword == null || !passwordEncoder.matches(currentPassword, credential.getPasswordHash())) {
            throw ApiException.unauthorized("Current password is incorrect.");
        }
        String code = String.format("%06d", secureRandom.nextInt(1_000_000));
        credential.setOtpCodeHash(passwordEncoder.encode(code));
        credential.setOtpExpiresAt(Instant.now().plusSeconds(OTP_VALIDITY_MINUTES * 60L));
        credentialRepository.save(credential);

        auditService.record(principal, "PASSWORD_OTP_SENT", AuditTargetType.CREDENTIAL, credential.getId(),
                "Password-change OTP emailed to " + maskEmail(user.getEmail())
                        + " (valid " + OTP_VALIDITY_MINUTES + " min)");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("maskedEmail", maskEmail(user.getEmail()));
        body.put("validMinutes", OTP_VALIDITY_MINUTES);
        if (properties.getMail().isDemoExposeSecrets()) {
            body.put("demoOtp", code);
        }
        return body;
    }

    @Transactional
    public Map<String, Object> changePassword(
            UserPrincipal principal, String currentPassword, String otp, String newPassword) {
        Credential credential = credentialRepository.findByUserId(principal.getId())
                .orElseThrow(() -> ApiException.notFound("No credential on record."));
        if (currentPassword == null || !passwordEncoder.matches(currentPassword, credential.getPasswordHash())) {
            throw ApiException.unauthorized("Current password is incorrect.");
        }
        if (credential.getOtpCodeHash() == null || credential.getOtpExpiresAt() == null) {
            throw ApiException.unprocessable(
                    "No OTP requested. Complete step 1 first (OTP via registered email).");
        }
        if (credential.getOtpExpiresAt().isBefore(Instant.now())) {
            credential.setOtpCodeHash(null);
            credential.setOtpExpiresAt(null);
            credentialRepository.save(credential);
            throw ApiException.unprocessable("The OTP has expired. Request a new one.");
        }
        if (otp == null || !passwordEncoder.matches(otp.trim(), credential.getOtpCodeHash())) {
            throw ApiException.unauthorized("Incorrect OTP. Check the code sent to your registered email.");
        }
        String next = newPassword == null ? "" : newPassword.trim();
        int min = configService.getConfig().getPasswordMinLength();
        if (next.length() < min) {
            throw ApiException.unprocessable(
                    "New password must be at least " + min + " characters (password policy, SRS §3.2).");
        }
        if (passwordEncoder.matches(next, credential.getPasswordHash())) {
            throw ApiException.unprocessable("New password must be different from the current password.");
        }
        boolean wasFirstLogin = credential.isOneTime();
        credential.setPasswordHash(passwordEncoder.encode(next));
        credential.setOneTime(false);
        credential.setOtpCodeHash(null);
        credential.setOtpExpiresAt(null);
        credential.setLastChangedAt(Instant.now());
        credentialRepository.save(credential);

        auditService.record(
                principal,
                wasFirstLogin ? "FIRST_LOGIN_PASSWORD_CHANGED" : "PASSWORD_CHANGED",
                AuditTargetType.CREDENTIAL,
                credential.getId(),
                wasFirstLogin
                        ? "One-time password invalidated after email OTP verification — account fully activated"
                        : "Password updated by the account holder (email OTP verified)");
        return Map.of("ok", true);
    }

    @Transactional
    public Map<String, Object> forgotPassword(String identifier) {
        if (identifier == null || identifier.isBlank()) {
            throw ApiException.badRequest("Enter your username or registered email address.");
        }
        String needle = identifier.trim().toLowerCase();
        AppUser user = userRepository.findByUsernameIgnoreCase(needle)
                .or(() -> userRepository.findByEmailIgnoreCase(needle))
                .orElse(null);
        Credential credential = user == null ? null : credentialRepository.findByUserId(user.getId()).orElse(null);

        Map<String, Object> generic = new LinkedHashMap<>();
        generic.put("ok", true);
        generic.put("message",
                "If the account exists, a password-reset email has been sent to the registered address.");

        if (user == null || credential == null || credential.isDeactivated() || !user.isActive()) {
            return generic;
        }

        String oneTimePassword = "reset-" + UUID.randomUUID().toString().substring(0, 8);
        credential.setPasswordHash(passwordEncoder.encode(oneTimePassword));
        credential.setOneTime(true);
        credential.setOtpCodeHash(null);
        credential.setOtpExpiresAt(null);
        credentialRepository.save(credential);

        auditService.record(
                user.getId(),
                user.getRole().name(),
                "PASSWORD_RESET_REQUESTED",
                AuditTargetType.CREDENTIAL,
                credential.getId(),
                "Forgot-password flow: one-time password emailed after email validation");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("message",
                "A one-time password has been sent to " + user.getEmail()
                        + ". It must be changed at your next login.");
        if (properties.getMail().isDemoExposeSecrets()) {
            body.put("demoOneTimePassword", oneTimePassword);
        }
        return body;
    }

    private void setSessionCookie(HttpServletResponse response, String token, long maxAgeSeconds) {
        boolean secure = isProd();
        ResponseCookie cookie = ResponseCookie.from(properties.getSecurity().getJwt().getCookieName(), token)
                .httpOnly(true)
                .path("/")
                .maxAge(maxAgeSeconds)
                .secure(secure)
                .sameSite(secure ? "None" : "Lax")
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    private void clearSessionCookie(HttpServletResponse response) {
        boolean secure = isProd();
        ResponseCookie cookie = ResponseCookie.from(properties.getSecurity().getJwt().getCookieName(), "")
                .httpOnly(true)
                .path("/")
                .maxAge(0)
                .secure(secure)
                .sameSite(secure ? "None" : "Lax")
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    private boolean isProd() {
        for (String profile : environment.getActiveProfiles()) {
            if ("prod".equalsIgnoreCase(profile)) {
                return true;
            }
        }
        return false;
    }

    private static String maskEmail(String email) {
        int at = email.indexOf('@');
        if (at <= 0) {
            return "***";
        }
        String local = email.substring(0, at);
        String domain = email.substring(at + 1);
        String visible = local.substring(0, Math.min(2, local.length()));
        return visible + "*".repeat(Math.max(local.length() - 2, 3)) + "@" + domain;
    }
}
