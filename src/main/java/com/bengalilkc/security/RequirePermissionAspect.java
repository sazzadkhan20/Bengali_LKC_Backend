package com.bengalilkc.security;

import com.bengalilkc.common.exception.ApiException;
import com.bengalilkc.domain.enums.Permission;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Aspect
@Component
public class RequirePermissionAspect {

    @Before("@annotation(requirePermission)")
    public void check(RequirePermission requirePermission) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof UserPrincipal principal)) {
            throw ApiException.unauthorized("Unauthorized");
        }
        Permission needed = requirePermission.value();
        if (!principal.hasPermission(needed)) {
            throw ApiException.forbidden("Not authorised.");
        }
    }
}
