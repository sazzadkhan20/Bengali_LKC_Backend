package com.bengalilkc.web.controller;

import com.bengalilkc.domain.enums.Permission;
import com.bengalilkc.security.RequirePermission;
import com.bengalilkc.security.SecurityUtils;
import com.bengalilkc.security.UserPrincipal;
import com.bengalilkc.service.auth.UserService;
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
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @GetMapping
    @RequirePermission(Permission.MANAGE_USERS)
    public List<Map<String, Object>> list() {
        return userService.listUsers();
    }

    @PostMapping
    @RequirePermission(Permission.MANAGE_USERS)
    public Map<String, Object> create(@RequestBody Map<String, Object> body) {
        return userService.createUser(SecurityUtils.requirePrincipal(), body);
    }

    @PatchMapping("/{id}")
    public Map<String, Object> patch(@PathVariable String id, @RequestBody Map<String, Object> body) {
        UserPrincipal principal = SecurityUtils.requirePrincipal();
        return userService.patchUser(principal, id, body);
    }
}
