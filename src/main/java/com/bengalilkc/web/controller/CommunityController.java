package com.bengalilkc.web.controller;

import com.bengalilkc.domain.enums.Permission;
import com.bengalilkc.security.RequirePermission;
import com.bengalilkc.security.SecurityUtils;
import com.bengalilkc.service.community.CommunityService;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/community")
@RequiredArgsConstructor
public class CommunityController {

    private final CommunityService communityService;

    @GetMapping
    @RequirePermission(Permission.COMMUNITY)
    public Map<String, Object> feed(
            @RequestParam(required = false) String view,
            @RequestParam(required = false) String authorId,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return communityService.feed(SecurityUtils.requirePrincipal(),
                new CommunityService.FeedQuery(view, authorId, page, size));
    }

    @GetMapping("/profile/{userId}")
    @RequirePermission(Permission.COMMUNITY)
    public Map<String, Object> profile(@PathVariable String userId) {
        return communityService.profile(SecurityUtils.requirePrincipal(), userId);
    }

    @PostMapping
    @RequirePermission(Permission.COMMUNITY)
    public Map<String, Object> create(@RequestBody Map<String, Object> body) {
        Object text = body.get("text");
        return communityService.createPost(SecurityUtils.requirePrincipal(), text == null ? "" : String.valueOf(text));
    }

    @PostMapping("/{id}")
    @RequirePermission(Permission.COMMUNITY)
    public Map<String, Object> action(@PathVariable String id, @RequestBody Map<String, Object> body) {
        return communityService.action(SecurityUtils.requirePrincipal(), id, body);
    }
}
