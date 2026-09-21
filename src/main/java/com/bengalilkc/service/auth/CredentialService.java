package com.bengalilkc.service.auth;

import com.bengalilkc.common.exception.ApiException;
import com.bengalilkc.domain.entity.identity.AppUser;
import com.bengalilkc.domain.entity.identity.Credential;
import com.bengalilkc.domain.enums.AuditTargetType;
import com.bengalilkc.domain.enums.Role;
import com.bengalilkc.repository.identity.AppUserRepository;
import com.bengalilkc.repository.identity.CredentialRepository;
import com.bengalilkc.security.UserPrincipal;
import com.bengalilkc.service.admin.AuditService;
import com.bengalilkc.service.admin.IdGeneratorService;
import com.bengalilkc.service.mail.EmailService;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CredentialService {

    private final CredentialRepository credentialRepository;
    private final AppUserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final IdGeneratorService idGeneratorService;
    private final AuditService auditService;
    private final EmailService emailService;
    private final RefreshTokenService refreshTokenService;

    @Transactional(readOnly = true)
    public Map<String, Object> listPaged(int page, int size, String q, String roleRaw) {
        int safeSize = size == 15 || size == 30 || size == 50 || size == 100 ? size : 15;
        int safePage = Math.max(page, 0);
        String query = q == null ? "" : q.trim();
        Role role = null;
        if (roleRaw != null && !roleRaw.isBlank() && !"ALL".equalsIgnoreCase(roleRaw.trim())) {
            try {
                role = Role.valueOf(roleRaw.trim());
            } catch (Exception e) {
                throw ApiException.unprocessable("Select a valid role filter.");
            }
        }
        Page<Credential> result = credentialRepository.search(
                query.isEmpty() ? null : query, role, PageRequest.of(safePage, safeSize));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", result.getContent().stream().map(this::toDto).toList());
        body.put("page", result.getNumber());
        body.put("size", result.getSize());
        body.put("totalElements", result.getTotalElements());
        body.put("totalPages", result.getTotalPages());
        body.put("q", query);
        body.put("role", role != null ? role.name() : "ALL");
        return body;
    }

    @Transactional
    public Map<String, Object> issueOrReset(UserPrincipal principal, String userId) {
        AppUser user = userRepository.findById(userId)
                .orElseThrow(() -> ApiException.unprocessable("Select a valid user."));
        String oneTimePassword = "lkc-" + UUID.randomUUID().toString().substring(0, 10);
        Credential credential = credentialRepository.findByUserId(user.getId()).orElse(null);
        boolean isReset = credential != null;
        if (credential == null) {
            credential = Credential.builder()
                    .id("cred-" + idGeneratorService.nextValue("credential"))
                    .userId(user.getId())
                    .username(user.getUsername())
                    .build();
        }
        credential.setPasswordHash(passwordEncoder.encode(oneTimePassword));
        credential.setOneTime(true);
        credential.setDeactivated(false);
        credential.setIssuedBy(principal.getId());
        credential.setIssuedAt(Instant.now());
        credential.setLastChangedAt(null);
        credential.setOtpCodeHash(null);
        credential.setOtpExpiresAt(null);
        credentialRepository.save(credential);

        refreshTokenService.revokeAllForUser(user.getId());

        auditService.record(
                principal,
                isReset ? "PASSWORD_FORCE_RESET" : "CREDENTIAL_ISSUED",
                AuditTargetType.CREDENTIAL,
                credential.getId(),
                user.getName() + " (" + user.getRole() + ") — one-time password emailed to " + user.getEmail()
                        + "; change required at first login");

        if (isReset) {
            emailService.sendCredentialReset(
                    user.getEmail(), user.getName(), user.getUsername(), oneTimePassword);
        } else {
            emailService.sendCredentialIssued(
                    user.getEmail(), user.getName(), user.getUsername(), oneTimePassword);
        }

        Map<String, Object> cred = new LinkedHashMap<>();
        cred.put("id", credential.getId());
        cred.put("username", credential.getUsername());
        cred.put("email", user.getEmail());
        cred.put("demoOneTimePassword", oneTimePassword);
        cred.put("emailSent", emailService.isConfigured());
        return Map.of("ok", true, "credential", cred);
    }

    @Transactional
    public Map<String, Object> patch(UserPrincipal principal, String id, Map<String, Object> payload) {
        Credential credential = credentialRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound("Credential not found."));
        if (!(payload.get("deactivated") instanceof Boolean deactivated)) {
            throw ApiException.unprocessable("Nothing to update.");
        }
        credential.setDeactivated(deactivated);
        credentialRepository.save(credential);
        if (deactivated) {
            refreshTokenService.revokeAllForUser(credential.getUserId());
        }
        AppUser holder = userRepository.findById(credential.getUserId()).orElse(null);
        String name = holder != null ? holder.getName() : credential.getUsername();
        auditService.record(
                principal,
                deactivated ? "CREDENTIAL_DEACTIVATED" : "CREDENTIAL_REACTIVATED",
                AuditTargetType.CREDENTIAL,
                credential.getId(),
                deactivated
                        ? name + " — all active sessions immediately invalidated"
                        : name + " — credential restored");
        return Map.of("ok", true);
    }

    private Map<String, Object> toDto(Credential c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.getId());
        m.put("userId", c.getUserId());
        m.put("username", c.getUsername());
        m.put("oneTime", c.isOneTime());
        m.put("issuedBy", c.getIssuedBy());
        m.put("issuedAt", c.getIssuedAt() != null ? c.getIssuedAt().toString() : null);
        m.put("lastChangedAt", c.getLastChangedAt() != null ? c.getLastChangedAt().toString() : null);
        m.put("deactivated", c.isDeactivated());
        userRepository.findById(c.getUserId()).ifPresent(u -> {
            m.put("holderName", u.getName());
            m.put("holderRole", u.getRole().name());
            m.put("holderEmail", u.getEmail());
        });
        return m;
    }
}
