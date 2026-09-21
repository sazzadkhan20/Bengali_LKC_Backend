package com.bengalilkc.service.auth;

import com.bengalilkc.common.exception.ApiException;
import com.bengalilkc.config.LkcProperties;
import com.bengalilkc.domain.entity.admin.SystemConfig;
import com.bengalilkc.domain.entity.identity.AppUser;
import com.bengalilkc.domain.entity.identity.Credential;
import com.bengalilkc.domain.entity.identity.RefreshToken;
import com.bengalilkc.domain.enums.AuditTargetType;
import com.bengalilkc.domain.enums.Role;
import com.bengalilkc.repository.identity.AppUserRepository;
import com.bengalilkc.repository.identity.CredentialRepository;
import com.bengalilkc.security.JwtService;
import com.bengalilkc.security.PermissionMatrix;
import com.bengalilkc.security.UserPrincipal;
import com.bengalilkc.service.admin.AuditService;
import com.bengalilkc.service.admin.ConfigService;
import com.bengalilkc.service.community.LeaderboardService;
import com.bengalilkc.service.mail.EmailService;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
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
    private final RefreshTokenService refreshTokenService;
    private final ConfigService configService;
    private final AuditService auditService;
    private final UserService userService;
    private final LeaderboardService leaderboardService;
    private final EmailService emailService;
    private final LkcProperties properties;
    private final Environment environment;
    private final SecureRandom secureRandom = new SecureRandom();

    /**
     * Sign in with username <em>or</em> registered email plus password.
     * Field-level errors are returned under {@code username} / {@code password}
     * so the login form can highlight only the failing field.
     */
    @Transactional
    public Map<String, Object> login(String identifier, String password, HttpServletResponse response) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        String needle = identifier == null ? "" : identifier.trim();
        String pass = password == null ? "" : password;

        if (needle.isBlank()) {
            fieldErrors.put("username", "Enter your username or email address.");
        }
        if (pass.isBlank()) {
            fieldErrors.put("password", "Enter your password.");
        }
        if (!fieldErrors.isEmpty()) {
            throw ApiException.badRequest(fieldErrors);
        }

        Credential credential = resolveCredentialByUsernameOrEmail(needle);
        if (credential == null) {
            throw ApiException.unauthorized(Map.of(
                    "username", "No account found with this username or email."));
        }
        if (!passwordEncoder.matches(pass, credential.getPasswordHash())) {
            throw ApiException.unauthorized(Map.of(
                    "password", "Incorrect password."));
        }
        if (credential.isDeactivated()) {
            throw ApiException.unauthorized(Map.of(
                    "username", "This credential has been deactivated by the Platform Architect."));
        }
        AppUser user = userRepository.findById(credential.getUserId()).orElse(null);
        if (user == null || !user.isActive()) {
            throw ApiException.unauthorized(Map.of(
                    "username", "The account linked to this credential is inactive."));
        }
        if (user.isSuspended()) {
            throw ApiException.forbidden(
                    "This account is suspended pending Platform Architect review.");
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

        // One refresh session per login — revoke prior refresh tokens for this account.
        refreshTokenService.revokeAllForUser(user.getId());
        return issueAuthResponse(user, credential, response);
    }

    /** Resolve login credential by username (credential or user) or by registered email. */
    private Credential resolveCredentialByUsernameOrEmail(String identifier) {
        return credentialRepository.findByUsernameIgnoreCase(identifier)
                .or(() -> userRepository.findByUsernameIgnoreCase(identifier)
                        .flatMap(u -> credentialRepository.findByUserId(u.getId())))
                .or(() -> userRepository.findByEmailIgnoreCase(identifier)
                        .flatMap(u -> credentialRepository.findByUserId(u.getId())))
                .orElse(null);
    }

    @Transactional
    public Map<String, Object> refresh(HttpServletRequest request, HttpServletResponse response) {
        String rawRefresh = resolveRefreshToken(request);
        RefreshToken stored = refreshTokenService.requireActive(rawRefresh);
        AppUser user = userRepository.findById(stored.getUserId())
                .orElseThrow(() -> ApiException.unauthorized("Account no longer exists."));
        Credential credential = credentialRepository.findByUserId(user.getId()).orElse(null);
        if (!user.isActive() || user.isSuspended() || credential == null || credential.isDeactivated()) {
            refreshTokenService.revoke(rawRefresh);
            throw ApiException.unauthorized("Account is not eligible for token refresh.");
        }

        // Rotate refresh token (reuse detection / theft mitigation).
        refreshTokenService.revoke(rawRefresh);
        return issueAuthResponse(user, credential, response);
    }

    public Map<String, Object> logout(HttpServletRequest request, HttpServletResponse response) {
        String rawRefresh = resolveRefreshToken(request);
        refreshTokenService.revoke(rawRefresh);
        clearSessionCookies(response);
        return Map.of("ok", true);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> session(UserPrincipal principal, HttpServletRequest request) {
        AppUser user = userRepository.findById(principal.getId())
                .orElseThrow(() -> ApiException.unauthorized("Unauthorized"));
        Credential credential = credentialRepository.findByUserId(principal.getId()).orElse(null);
        boolean mustChange = credential != null && credential.isOneTime();

        Instant expiresAt = null;
        String access = resolveAccessToken(request);
        if (access != null) {
            try {
                expiresAt = jwtService.expiration(access);
            } catch (Exception ignored) {
                // fall through — still return live session from principal
            }
        }
        if (expiresAt == null) {
            long ttl = accessTtlMinutes();
            expiresAt = Instant.now().plusSeconds(ttl * 60);
        }

        return buildSessionPayload(user, credential, mustChange, expiresAt, null, null);
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

        emailService.sendPasswordChangeOtp(user.getEmail(), user.getName(), code, OTP_VALIDITY_MINUTES);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("maskedEmail", maskEmail(user.getEmail()));
        body.put("validMinutes", OTP_VALIDITY_MINUTES);
        body.put("emailSent", emailService.isConfigured());
        return body;
    }

    @Transactional
    public Map<String, Object> changePassword(
            UserPrincipal principal,
            String currentPassword,
            String otp,
            String newPassword,
            HttpServletResponse response) {
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

        AppUser user = userRepository.findById(principal.getId()).orElseThrow();
        refreshTokenService.revokeAllForUser(user.getId());
        Map<String, Object> body = issueAuthResponse(user, credential, response);
        body.put("ok", true);
        return body;
    }

    /**
     * Step 1 — forgot password: resolve username/email and email a 6-digit OTP.
     * Does not change the password until OTP is confirmed in step 2.
     */
    @Transactional
    public Map<String, Object> forgotPassword(String identifier) {
        if (identifier == null || identifier.isBlank()) {
            throw ApiException.badRequest(Map.of(
                    "identifier", "Enter your username or email address."));
        }
        AppUser user = resolveUserByUsernameOrEmail(identifier.trim());
        Credential credential = user == null
                ? null
                : credentialRepository.findByUserId(user.getId()).orElse(null);

        if (user == null || credential == null) {
            throw ApiException.unauthorized(Map.of(
                    "identifier", "No account found with this username or email."));
        }
        if (credential.isDeactivated() || !user.isActive()) {
            throw ApiException.unauthorized(Map.of(
                    "identifier", "This account is inactive or deactivated."));
        }
        if (user.isSuspended()) {
            throw ApiException.forbidden(
                    "This account is suspended pending Platform Architect review.");
        }

        String code = String.format("%06d", secureRandom.nextInt(1_000_000));
        credential.setOtpCodeHash(passwordEncoder.encode(code));
        credential.setOtpExpiresAt(Instant.now().plusSeconds(OTP_VALIDITY_MINUTES * 60L));
        credentialRepository.save(credential);

        auditService.record(
                user.getId(),
                user.getRole().name(),
                "FORGOT_PASSWORD_OTP_SENT",
                AuditTargetType.CREDENTIAL,
                credential.getId(),
                "Forgot-password OTP emailed to " + maskEmail(user.getEmail())
                        + " (valid " + OTP_VALIDITY_MINUTES + " min)");

        emailService.sendForgotPasswordOtp(
                user.getEmail(), user.getName(), code, OTP_VALIDITY_MINUTES);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("maskedEmail", maskEmail(user.getEmail()));
        body.put("validMinutes", OTP_VALIDITY_MINUTES);
        body.put("emailSent", emailService.isConfigured());
        body.put("passwordMinLength", configService.getConfig().getPasswordMinLength());
        body.put("message",
                "A verification code has been sent to " + maskEmail(user.getEmail()) + ".");
        return body;
    }

    /**
     * Step 2 — verify email OTP and set a new password (unauthenticated reset).
     */
    @Transactional
    public Map<String, Object> confirmForgotPassword(
            String identifier, String otp, String newPassword) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        String needle = identifier == null ? "" : identifier.trim();
        String code = otp == null ? "" : otp.trim();
        String next = newPassword == null ? "" : newPassword.trim();

        if (needle.isBlank()) {
            fieldErrors.put("identifier", "Enter your username or email address.");
        }
        if (code.isBlank()) {
            fieldErrors.put("otp", "Enter the 6-digit code from your email.");
        } else if (!code.matches("\\d{6}")) {
            fieldErrors.put("otp", "OTP must be a 6-digit code.");
        }
        int min = configService.getConfig().getPasswordMinLength();
        if (next.isBlank()) {
            fieldErrors.put("newPassword", "Enter a new password.");
        } else if (next.length() < min) {
            fieldErrors.put("newPassword",
                    "New password must be at least " + min + " characters.");
        }
        if (!fieldErrors.isEmpty()) {
            throw ApiException.badRequest(fieldErrors);
        }

        AppUser user = resolveUserByUsernameOrEmail(needle);
        Credential credential = user == null
                ? null
                : credentialRepository.findByUserId(user.getId()).orElse(null);
        if (user == null || credential == null) {
            throw ApiException.unauthorized(Map.of(
                    "identifier", "No account found with this username or email."));
        }
        if (credential.isDeactivated() || !user.isActive()) {
            throw ApiException.unauthorized(Map.of(
                    "identifier", "This account is inactive or deactivated."));
        }
        if (credential.getOtpCodeHash() == null || credential.getOtpExpiresAt() == null) {
            throw ApiException.unprocessable(Map.of(
                    "otp", "No OTP requested. Go back and request a new code."));
        }
        if (credential.getOtpExpiresAt().isBefore(Instant.now())) {
            credential.setOtpCodeHash(null);
            credential.setOtpExpiresAt(null);
            credentialRepository.save(credential);
            throw ApiException.unprocessable(Map.of(
                    "otp", "The OTP has expired. Request a new one."));
        }
        if (!passwordEncoder.matches(code, credential.getOtpCodeHash())) {
            throw ApiException.unauthorized(Map.of(
                    "otp", "Incorrect OTP. Check the code sent to your registered email."));
        }
        if (passwordEncoder.matches(next, credential.getPasswordHash())) {
            throw ApiException.unprocessable(Map.of(
                    "newPassword", "New password must be different from the current password."));
        }

        credential.setPasswordHash(passwordEncoder.encode(next));
        credential.setOneTime(false);
        credential.setOtpCodeHash(null);
        credential.setOtpExpiresAt(null);
        credential.setLastChangedAt(Instant.now());
        credentialRepository.save(credential);
        refreshTokenService.revokeAllForUser(user.getId());

        auditService.record(
                user.getId(),
                user.getRole().name(),
                "FORGOT_PASSWORD_COMPLETED",
                AuditTargetType.CREDENTIAL,
                credential.getId(),
                "Password reset via forgot-password email OTP");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("message",
                "Password updated successfully. You can sign in with your username or email and the new password.");
        return body;
    }

    private AppUser resolveUserByUsernameOrEmail(String identifier) {
        return userRepository.findByUsernameIgnoreCase(identifier)
                .or(() -> userRepository.findByEmailIgnoreCase(identifier))
                .or(() -> credentialRepository.findByUsernameIgnoreCase(identifier)
                        .flatMap(c -> userRepository.findById(c.getUserId())))
                .orElse(null);
    }

    private Map<String, Object> issueAuthResponse(
            AppUser user, Credential credential, HttpServletResponse response) {
        long ttlMinutes = accessTtlMinutes();
        boolean mustChange = credential.isOneTime();
        String accessToken = jwtService.createAccessToken(
                user.getId(), user.getUsername(), user.getRole(), mustChange, ttlMinutes);
        String refreshToken = refreshTokenService.issue(user.getId());
        Instant expiresAt = Instant.now().plusSeconds(ttlMinutes * 60);

        setAccessCookie(response, accessToken, ttlMinutes * 60L);
        setRefreshCookie(
                response,
                refreshToken,
                properties.getSecurity().getJwt().getRefreshTtlDays() * 24L * 60L * 60L);

        return buildSessionPayload(user, credential, mustChange, expiresAt, accessToken, refreshToken);
    }

    private Map<String, Object> buildSessionPayload(
            AppUser user,
            Credential credential,
            boolean mustChange,
            Instant expiresAt,
            String accessToken,
            String refreshToken) {
        Map<String, Object> session = new LinkedHashMap<>();
        session.put("userId", user.getId());
        session.put("username", user.getUsername());
        session.put("name", user.getName());
        session.put("role", user.getRole().name());
        session.put("mustChangePassword", mustChange);
        session.put("expiresAt", expiresAt.toString());
        session.put("expiresInSeconds", Math.max(0, expiresAt.getEpochSecond() - Instant.now().getEpochSecond()));
        session.put("sessionTimeoutMinutes", accessTtlMinutes());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("user", Map.of("name", user.getName(), "role", user.getRole().name()));
        body.put("mustChangePassword", mustChange);
        body.put("session", session);
        body.put("profile", userService.toDto(user));
        body.put("permissions", PermissionMatrix.permissionsFor(user.getRole()).stream()
                .map(p -> p.getCode())
                .toList());
        if (user.getRole() == Role.CONTRIBUTOR
                || user.getRole() == Role.LEXICAL_EDITOR
                || user.getRole() == Role.CHIEF_LEXICAL_EDITOR) {
            leaderboardService.leaderboardFor(user.getRole()).stream()
                    .filter(r -> user.getId().equals(r.get("userId")))
                    .findFirst()
                    .ifPresent(row -> {
                        Map<String, Object> standing = new LinkedHashMap<>(row);
                        standing.put("role", user.getRole().name());
                        body.put("standing", standing);
                    });
        }
        if (accessToken != null) {
            body.put("accessToken", accessToken);
            body.put("tokenType", "Bearer");
            body.put("expiresIn", accessTtlMinutes() * 60L);
        }
        if (refreshToken != null) {
            body.put("refreshToken", refreshToken);
        }
        return body;
    }

    private long accessTtlMinutes() {
        try {
            SystemConfig config = configService.getConfig();
            if (config.getSessionTimeoutMinutes() > 0) {
                return config.getSessionTimeoutMinutes();
            }
        } catch (Exception ignored) {
            // use property default
        }
        return properties.getSecurity().getJwt().getAccessTtlMinutes();
    }

    private void setAccessCookie(HttpServletResponse response, String token, long maxAgeSeconds) {
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

    private void setRefreshCookie(HttpServletResponse response, String token, long maxAgeSeconds) {
        boolean secure = isProd();
        ResponseCookie cookie = ResponseCookie.from(
                        properties.getSecurity().getJwt().getRefreshCookieName(), token)
                .httpOnly(true)
                .path("/")
                .maxAge(maxAgeSeconds)
                .secure(secure)
                .sameSite(secure ? "None" : "Lax")
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    private void clearSessionCookies(HttpServletResponse response) {
        boolean secure = isProd();
        ResponseCookie access = ResponseCookie.from(properties.getSecurity().getJwt().getCookieName(), "")
                .httpOnly(true)
                .path("/")
                .maxAge(0)
                .secure(secure)
                .sameSite(secure ? "None" : "Lax")
                .build();
        ResponseCookie refresh = ResponseCookie.from(properties.getSecurity().getJwt().getRefreshCookieName(), "")
                .httpOnly(true)
                .path("/")
                .maxAge(0)
                .secure(secure)
                .sameSite(secure ? "None" : "Lax")
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, access.toString());
        response.addHeader(HttpHeaders.SET_COOKIE, refresh.toString());
    }

    private String resolveRefreshToken(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        String header = request.getHeader("X-Refresh-Token");
        if (header != null && !header.isBlank()) {
            return header.trim();
        }
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            String name = properties.getSecurity().getJwt().getRefreshCookieName();
            for (Cookie cookie : cookies) {
                if (name.equals(cookie.getName())) {
                    return cookie.getValue();
                }
            }
        }
        return null;
    }

    private String resolveAccessToken(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        LkcProperties.Jwt jwt = properties.getSecurity().getJwt();
        String header = request.getHeader(jwt.getHeaderName() != null ? jwt.getHeaderName() : HttpHeaders.AUTHORIZATION);
        if (header != null && header.startsWith(jwt.getTokenPrefix())) {
            return header.substring(jwt.getTokenPrefix().length()).trim();
        }
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie cookie : cookies) {
                if (jwt.getCookieName().equals(cookie.getName())) {
                    return cookie.getValue();
                }
            }
        }
        return null;
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
