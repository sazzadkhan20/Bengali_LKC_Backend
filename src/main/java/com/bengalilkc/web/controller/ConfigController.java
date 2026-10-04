package com.bengalilkc.web.controller;

import com.bengalilkc.domain.enums.Permission;
import com.bengalilkc.security.RequirePermission;
import com.bengalilkc.security.SecurityUtils;
import com.bengalilkc.service.admin.CaptchaService;
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
    private final CaptchaService captchaService;

    @GetMapping
    @RequirePermission(Permission.SYSTEM_CONFIG)
    public Map<String, Object> get() {
        return configService.view();
    }

    @PutMapping
    @RequirePermission(Permission.SYSTEM_CONFIG)
    public Map<String, Object> put(@RequestBody Map<String, Object> body) {
        return configService.update(SecurityUtils.requirePrincipal(), body);
    }

    /** CAPTCHA settings the entry forms need; available to every signed-in user. */
    @GetMapping("/captcha")
    public Map<String, Object> captcha() {
        SecurityUtils.requirePrincipal();
        return captchaService.clientConfig(configService.getConfig().isCaptchaEnabled());
    }
}
