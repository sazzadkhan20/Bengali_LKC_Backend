package com.bengalilkc.service.auth;

import com.bengalilkc.common.exception.ApiException;
import com.bengalilkc.config.LkcProperties;
import com.bengalilkc.domain.entity.identity.AppUser;
import com.bengalilkc.domain.entity.identity.Credential;
import com.bengalilkc.domain.enums.AuditTargetType;
import com.bengalilkc.repository.identity.AppUserRepository;
import com.bengalilkc.repository.identity.CredentialRepository;
import com.bengalilkc.security.UserPrincipal;
import com.bengalilkc.service.admin.AuditService;
import com.bengalilkc.service.admin.IdGeneratorService;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
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
    private final LkcProperties properties;

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list() {
        return credentialRepository.findAll().stream().map(this::toDto).toList();
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
        credential.setOtpCodeHash(null);
        credential.setOtpExpiresAt(null);
        credentialRepository.save(credential);

        auditService.record(
                principal,
                isReset ? "PASSWORD_FORCE_RESET" : "CREDENTIAL_ISSUED",
                AuditTargetType.CREDENTIAL,
                credential.getId(),
                user.getName() + " (" + user.getRole() + ") — one-time password emailed to " + user.getEmail()
                        + "; change required at first login");

        Map<String, Object> cred = new LinkedHashMap<>();
        cred.put("id", credential.getId());
        cred.put("username", credential.getUsername());
        cred.put("email", user.getEmail());
        if (properties.getMail().isDemoExposeSecrets()) {
            cred.put("demoOneTimePassword", oneTimePassword);
        }
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
        return m;
    }
}
