package com.bengalilkc.web.controller;

import com.bengalilkc.domain.enums.Permission;
import com.bengalilkc.security.RequirePermission;
import com.bengalilkc.security.SecurityUtils;
import com.bengalilkc.service.auth.CredentialService;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/credentials")
@RequiredArgsConstructor
public class CredentialController {

    private final CredentialService credentialService;

    @GetMapping
    @RequirePermission(Permission.MANAGE_CREDENTIALS)
    public List<Map<String, Object>> list() {
        return credentialService.list();
    }

    @PostMapping
    @RequirePermission(Permission.MANAGE_CREDENTIALS)
    public Map<String, Object> issue(@RequestBody Map<String, Object> body) {
        return credentialService.issueOrReset(
                SecurityUtils.requirePrincipal(), String.valueOf(body.get("userId")));
    }

    @PatchMapping("/{id}")
    @RequirePermission(Permission.MANAGE_CREDENTIALS)
    public Map<String, Object> patch(@PathVariable String id, @RequestBody Map<String, Object> body) {
        return credentialService.patch(SecurityUtils.requirePrincipal(), id, body);
    }
}
