package com.bengalilkc.security;

import com.bengalilkc.domain.enums.Permission;
import com.bengalilkc.domain.enums.Role;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Role → permission matrix matching frontend {@code auth.ts} (SRS §3.3).
 */
public final class PermissionMatrix {

    private static final Map<Role, Set<Permission>> BY_ROLE;

    static {
        Map<Role, Set<Permission>> map = new EnumMap<>(Role.class);
        for (Role role : Role.values()) {
            map.put(role, EnumSet.noneOf(Permission.class));
        }
        grant(map, Permission.MANAGE_USERS, Role.PLATFORM_ARCHITECT);
        grant(map, Permission.MANAGE_CREDENTIALS, Role.PLATFORM_ARCHITECT);
        grant(map, Permission.SYSTEM_CONFIG, Role.PLATFORM_ARCHITECT);
        grant(map, Permission.AUDIT_LOG, Role.PLATFORM_ARCHITECT, Role.SYSTEM_MODERATOR);
        grant(map, Permission.XML_EXPORT, Role.PLATFORM_ARCHITECT, Role.SYSTEM_MODERATOR);
        grant(map, Permission.QUICK_INPUT, Role.PLATFORM_ARCHITECT, Role.SYSTEM_MODERATOR, Role.CONTRIBUTOR);
        grant(map, Permission.DIRECT_APPROVAL, Role.PLATFORM_ARCHITECT, Role.SYSTEM_MODERATOR);
        grant(map, Permission.VIEW_PENDING_QUEUE, Role.PLATFORM_ARCHITECT, Role.SYSTEM_MODERATOR, Role.LEXICAL_EDITOR);
        grant(map, Permission.PRIMARY_VALIDATE, Role.PLATFORM_ARCHITECT, Role.SYSTEM_MODERATOR, Role.LEXICAL_EDITOR);
        grant(map, Permission.VIEW_EDITOR_APPROVED_QUEUE, Role.PLATFORM_ARCHITECT, Role.SYSTEM_MODERATOR, Role.CHIEF_LEXICAL_EDITOR);
        grant(map, Permission.FINAL_VALIDATE, Role.PLATFORM_ARCHITECT, Role.SYSTEM_MODERATOR, Role.CHIEF_LEXICAL_EDITOR);
        grant(map, Permission.ESCALATE, Role.PLATFORM_ARCHITECT, Role.SYSTEM_MODERATOR, Role.LEXICAL_EDITOR, Role.CHIEF_LEXICAL_EDITOR);
        grant(map, Permission.ARBITRATION_VOTE, Role.ARBITRATION_CURATOR);
        grant(map, Permission.TIE_BREAKER, Role.SYSTEM_MODERATOR);
        grant(map, Permission.APPLY_CORRECTIONS, Role.SYSTEM_MODERATOR);
        grant(map, Permission.OVERRIDE_STATE, Role.PLATFORM_ARCHITECT, Role.SYSTEM_MODERATOR);
        grant(map, Permission.ASSIGN_ENTRIES, Role.PLATFORM_ARCHITECT, Role.SYSTEM_MODERATOR);
        grant(map, Permission.BULK_OPERATIONS, Role.PLATFORM_ARCHITECT, Role.SYSTEM_MODERATOR);
        grant(map, Permission.SEARCH_INTERNAL,
                Role.PLATFORM_ARCHITECT, Role.SYSTEM_MODERATOR, Role.CONTRIBUTOR,
                Role.LEXICAL_EDITOR, Role.CHIEF_LEXICAL_EDITOR, Role.ARBITRATION_CURATOR);
        grant(map, Permission.STATISTICS,
                Role.PLATFORM_ARCHITECT, Role.SYSTEM_MODERATOR, Role.CONTRIBUTOR,
                Role.LEXICAL_EDITOR, Role.CHIEF_LEXICAL_EDITOR, Role.ARBITRATION_CURATOR);
        grant(map, Permission.DISPUTE, Role.CONTRIBUTOR);
        grant(map, Permission.COMMUNITY,
                Role.PLATFORM_ARCHITECT, Role.SYSTEM_MODERATOR, Role.CONTRIBUTOR,
                Role.LEXICAL_EDITOR, Role.CHIEF_LEXICAL_EDITOR, Role.ARBITRATION_CURATOR);
        grant(map, Permission.COMMUNITY_MODERATE, Role.SYSTEM_MODERATOR);
        grant(map, Permission.LEADERBOARD_RANKED, Role.CONTRIBUTOR, Role.LEXICAL_EDITOR, Role.CHIEF_LEXICAL_EDITOR);

        Map<Role, Set<Permission>> frozen = new EnumMap<>(Role.class);
        for (Map.Entry<Role, Set<Permission>> e : map.entrySet()) {
            frozen.put(e.getKey(), Collections.unmodifiableSet(e.getValue()));
        }
        BY_ROLE = Collections.unmodifiableMap(frozen);
    }

    private static void grant(Map<Role, Set<Permission>> map, Permission permission, Role... roles) {
        for (Role role : roles) {
            map.get(role).add(permission);
        }
    }

    public static Set<Permission> permissionsFor(Role role) {
        if (role == null) {
            return Set.of();
        }
        return BY_ROLE.getOrDefault(role, Set.of());
    }

    public static boolean has(Role role, Permission permission) {
        return permissionsFor(role).contains(permission);
    }

    private PermissionMatrix() {}
}
