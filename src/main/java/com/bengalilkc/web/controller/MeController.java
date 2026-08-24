package com.bengalilkc.web.controller;

import com.bengalilkc.domain.enums.Role;
import com.bengalilkc.security.PermissionMatrix;
import com.bengalilkc.security.SecurityUtils;
import com.bengalilkc.security.UserPrincipal;
import com.bengalilkc.service.auth.UserService;
import com.bengalilkc.service.community.LeaderboardService;
import com.bengalilkc.repository.identity.AppUserRepository;
import com.bengalilkc.repository.identity.CredentialRepository;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/me")
@RequiredArgsConstructor
public class MeController {

    private final AppUserRepository userRepository;
    private final CredentialRepository credentialRepository;
    private final UserService userService;
    private final LeaderboardService leaderboardService;

    @GetMapping
    public Map<String, Object> me() {
        UserPrincipal principal = SecurityUtils.requirePrincipal();
        var user = userRepository.findById(principal.getId()).orElseThrow();
        var credential = credentialRepository.findByUserId(principal.getId()).orElse(null);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("user", userService.toDto(user));
        body.put("mustChangePassword", credential != null && credential.isOneTime());
        body.put("permissions", PermissionMatrix.permissionsFor(user.getRole()).stream()
                .map(p -> p.getCode())
                .toList());
        if (user.getRole() == Role.CONTRIBUTOR
                || user.getRole() == Role.LEXICAL_EDITOR
                || user.getRole() == Role.CHIEF_LEXICAL_EDITOR) {
            leaderboardService.leaderboardFor(user.getRole()).stream()
                    .filter(r -> principal.getId().equals(r.get("userId")))
                    .findFirst()
                    .ifPresent(row -> {
                        Map<String, Object> standing = new LinkedHashMap<>(row);
                        standing.put("role", user.getRole().name());
                        body.put("standing", standing);
                    });
        }
        return body;
    }
}
