package com.bengalilkc.domain.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum Permission {
    MANAGE_USERS("manage_users"),
    MANAGE_CREDENTIALS("manage_credentials"),
    SYSTEM_CONFIG("system_config"),
    AUDIT_LOG("audit_log"),
    XML_EXPORT("xml_export"),
    QUICK_INPUT("quick_input"),
    DIRECT_APPROVAL("direct_approval"),
    VIEW_PENDING_QUEUE("view_pending_queue"),
    PRIMARY_VALIDATE("primary_validate"),
    VIEW_EDITOR_APPROVED_QUEUE("view_editor_approved_queue"),
    FINAL_VALIDATE("final_validate"),
    ESCALATE("escalate"),
    ARBITRATION_VOTE("arbitration_vote"),
    TIE_BREAKER("tie_breaker"),
    APPLY_CORRECTIONS("apply_corrections"),
    OVERRIDE_STATE("override_state"),
    ASSIGN_ENTRIES("assign_entries"),
    BULK_OPERATIONS("bulk_operations"),
    SEARCH_INTERNAL("search_internal"),
    STATISTICS("statistics"),
    DISPUTE("dispute"),
    COMMUNITY("community"),
    COMMUNITY_MODERATE("community_moderate"),
    LEADERBOARD_RANKED("leaderboard_ranked");

    private final String code;

    Permission(String code) {
        this.code = code;
    }

    @JsonValue
    public String getCode() {
        return code;
    }

    @JsonCreator
    public static Permission from(String value) {
        if (value == null) {
            return null;
        }
        String v = value.trim();
        for (Permission p : values()) {
            if (p.code.equalsIgnoreCase(v) || p.name().equalsIgnoreCase(v)) {
                return p;
            }
        }
        throw new IllegalArgumentException("Unknown permission: " + value);
    }
}
