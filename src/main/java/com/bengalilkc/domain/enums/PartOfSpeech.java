package com.bengalilkc.domain.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum PartOfSpeech {
    N("n"), V("v"), A("a"), R("r");

    private final String code;

    PartOfSpeech(String code) { this.code = code; }

    @JsonValue
    public String getCode() { return code; }

    @JsonCreator
    public static PartOfSpeech from(String value) {
        if (value == null) return null;
        String v = value.trim().toLowerCase();
        for (PartOfSpeech p : values()) {
            if (p.code.equals(v) || p.name().equalsIgnoreCase(v)) return p;
        }
        throw new IllegalArgumentException("Invalid POS: " + value);
    }
}
