package com.bengalilkc.domain.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum DefinitionLanguage {
    BEN("ben"), ENG("eng");

    private final String code;
    DefinitionLanguage(String code) { this.code = code; }

    @JsonValue
    public String getCode() { return code; }

    @JsonCreator
    public static DefinitionLanguage from(String value) {
        if (value == null) return null;
        String v = value.trim().toLowerCase();
        for (DefinitionLanguage d : values()) {
            if (d.code.equals(v) || d.name().equalsIgnoreCase(v)) return d;
        }
        throw new IllegalArgumentException("Invalid definitionLanguage: " + value);
    }
}
