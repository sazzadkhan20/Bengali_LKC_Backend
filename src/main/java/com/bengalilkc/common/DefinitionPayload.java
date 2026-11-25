package com.bengalilkc.common;

/** One synset definition: language code ({@code ben} or {@code eng}) and its text. */
public record DefinitionPayload(String language, String text) {}
