package com.bengalilkc.web.controller;

import com.bengalilkc.domain.enums.Permission;
import com.bengalilkc.security.RequirePermission;
import com.bengalilkc.security.SecurityUtils;
import com.bengalilkc.service.community.CommunityService;
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
@RequestMapping("/api/community")
@RequiredArgsConstructor
public class CommunityController {

    private final CommunityService communityService;

    @GetMapping
    @RequirePermission(Permission.COMMUNITY)
    public List<Map<String, Object>> feed() {
        return communityService.feed(SecurityUtils.requirePrincipal());
    }

    @PostMapping
    @RequirePermission(Permission.COMMUNITY)
    public Map<String, Object> create(@RequestBody Map<String, Object> body) {
        return communityService.createPost(SecurityUtils.requirePrincipal(), String.valueOf(body.getOrDefault("text", "")));
    }

    @PostMapping("/{id}")
    @RequirePermission(Permission.COMMUNITY)
    public Map<String, Object> action(@PathVariable String id, @RequestBody Map<String, Object> body) {
        return communityService.action(SecurityUtils.requirePrincipal(), id, body);
    }
}
