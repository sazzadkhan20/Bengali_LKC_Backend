package com.bengalilkc.bootstrap;

import com.bengalilkc.config.LkcProperties;
import com.bengalilkc.domain.entity.identity.AppUser;
import com.bengalilkc.domain.entity.identity.Credential;
import com.bengalilkc.domain.enums.AuditTargetType;
import com.bengalilkc.domain.enums.Role;
import com.bengalilkc.repository.identity.AppUserRepository;
import com.bengalilkc.repository.identity.CredentialRepository;
import com.bengalilkc.service.admin.IdGeneratorService;
import com.bengalilkc.service.admin.AuditService;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates the first Platform Architect of a fresh deployment from {@code LKC_INITIAL_ARCHITECT_*}.
 * Redeploys leave the account untouched, so a password changed in the app is never reset.
 */
@Slf4j
@Component
@Order(1)
@RequiredArgsConstructor
public class InitialArchitectBootstrap implements ApplicationRunner {

    private final LkcProperties properties;
    private final AppUserRepository userRepository;
    private final CredentialRepository credentialRepository;
    private final IdGeneratorService idGeneratorService;
    private final PasswordEncoder passwordEncoder;
    private final AuditService auditService;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        LkcProperties.InitialArchitect seed = properties.getBootstrap().getInitialArchitect();
        if (!seed.isConfigured()) {
            return;
        }
        String username = seed.getUsername().trim();
        String email = seed.getEmail().trim();
        if (userRepository.existsByUsernameIgnoreCase(username) || userRepository.existsByEmailIgnoreCase(email)) {
            log.info("Initial Platform Architect '{}' already exists; leaving it unchanged", username);
            return;
        }

        Instant now = Instant.now();
        String userId = "u-" + idGeneratorService.nextValue("user");
        userRepository.save(AppUser.builder()
                .id(userId)
                .username(username)
                .name(seed.getName().trim())
                .email(email)
                .role(Role.PLATFORM_ARCHITECT)
                .active(true)
                .suspended(false)
                .autoAnnounce(true)
                .joinedAt(now)
                .build());

        String credentialId = "cred-" + idGeneratorService.nextValue("credential");
        credentialRepository.save(Credential.builder()
                .id(credentialId)
                .userId(userId)
                .username(username)
                .passwordHash(passwordEncoder.encode(seed.getPassword()))
                .oneTime(false)
                .issuedBy(userId)
                .issuedAt(now)
                .lastChangedAt(now)
                .deactivated(false)
                .build());

        auditService.record(userId, Role.PLATFORM_ARCHITECT.name(), "USER_CREATED", AuditTargetType.USER, userId,
                seed.getName().trim() + " (PLATFORM_ARCHITECT) created at first deployment");
        log.info("Initial Platform Architect '{}' created", username);
    }
}
