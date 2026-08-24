package com.bengalilkc.web.controller;

import com.bengalilkc.domain.enums.Permission;
import com.bengalilkc.security.RequirePermission;
import com.bengalilkc.security.SecurityUtils;
import com.bengalilkc.service.admin.ConfigService;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/config")
@RequiredArgsConstructor
public class ConfigController {

    private final ConfigService configService;

    @GetMapping
    @RequirePermission(Permission.SYSTEM_CONFIG)
    public Map<String, Object> get() {
        return configService.toDto(configService.getConfig());
    }

    @PutMapping
    @RequirePermission(Permission.SYSTEM_CONFIG)
    public Map<String, Object> put(@RequestBody Map<String, Object> body) {
        Map<String, Object> config = configService.update(SecurityUtils.requirePrincipal(), body);
        return Map.of("ok", true, "config", config);
    }
}
