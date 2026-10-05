package com.bengalilkc.web.controller;

import com.bengalilkc.domain.enums.Permission;
import com.bengalilkc.security.RequirePermission;
import com.bengalilkc.security.SecurityUtils;
import com.bengalilkc.service.admin.ApplicationService;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Platform Architect review of "Apply to contribute" requests. Approval is done by issuing an account
 * through {@code POST /api/users} with {@code applicationId}.
 */
@RestController
@RequestMapping("/api/applications")
@RequiredArgsConstructor
public class ApplicationController {

    private final ApplicationService applicationService;

    @GetMapping
    @RequirePermission(Permission.MANAGE_CREDENTIALS)
    public Map<String, Object> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return applicationService.list(status, q, page, size);
    }

    @GetMapping("/{id}")
    @RequirePermission(Permission.MANAGE_CREDENTIALS)
    public Map<String, Object> get(@PathVariable String id) {
        return applicationService.get(id);
    }

    @PostMapping("/{id}/reject")
    @RequirePermission(Permission.MANAGE_CREDENTIALS)
    public Map<String, Object> reject(@PathVariable String id, @RequestBody Map<String, Object> body) {
        return applicationService.reject(SecurityUtils.requirePrincipal(), id, body);
    }
}
