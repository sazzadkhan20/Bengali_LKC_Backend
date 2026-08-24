package com.bengalilkc.web.controller;

import com.bengalilkc.domain.enums.Permission;
import com.bengalilkc.security.RequirePermission;
import com.bengalilkc.security.SecurityUtils;
import com.bengalilkc.service.workflow.SubmissionService;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/submissions")
@RequiredArgsConstructor
public class SubmissionController {

    private final SubmissionService submissionService;

    @GetMapping
    public List<Map<String, Object>> list() {
        SecurityUtils.requirePrincipal();
        return submissionService.list();
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable String id) {
        SecurityUtils.requirePrincipal();
        return submissionService.get(id);
    }

    @PostMapping
    @RequirePermission(Permission.QUICK_INPUT)
    public Map<String, Object> create(@RequestBody Map<String, Object> body) {
        return submissionService.create(SecurityUtils.requirePrincipal(), body);
    }

    @PostMapping("/{id}/action")
    public Map<String, Object> action(@PathVariable String id, @RequestBody Map<String, Object> body) {
        return submissionService.action(SecurityUtils.requirePrincipal(), id, body);
    }
}
