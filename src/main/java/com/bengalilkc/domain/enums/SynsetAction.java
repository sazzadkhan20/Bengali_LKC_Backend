package com.bengalilkc.domain.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum SynsetAction {
    LINK("link"), CREATE("create");

    private final String code;
    SynsetAction(String code) { this.code = code; }

    @JsonValue
    public String getCode() { return code; }

    @JsonCreator
    public static SynsetAction from(String value) {
        if (value == null) return null;
        String v = value.trim().toLowerCase();
        for (SynsetAction a : values()) {
            if (a.code.equals(v) || a.name().equalsIgnoreCase(v)) return a;
        }
        throw new IllegalArgumentException("Invalid synsetAction: " + value);
    }
}
