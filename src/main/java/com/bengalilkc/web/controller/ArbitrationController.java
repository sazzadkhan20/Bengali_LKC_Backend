package com.bengalilkc.web.controller;

import com.bengalilkc.security.SecurityUtils;
import com.bengalilkc.service.workflow.ArbitrationService;
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
@RequestMapping("/api/arbitration")
@RequiredArgsConstructor
public class ArbitrationController {

    private final ArbitrationService arbitrationService;

    @GetMapping
    public List<Map<String, Object>> list() {
        SecurityUtils.requirePrincipal();
        return arbitrationService.list();
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable String id) {
        SecurityUtils.requirePrincipal();
        return arbitrationService.get(id);
    }

    @PostMapping("/{id}")
    public Map<String, Object> action(@PathVariable String id, @RequestBody Map<String, Object> body) {
        return arbitrationService.action(SecurityUtils.requirePrincipal(), id, body);
    }
}
