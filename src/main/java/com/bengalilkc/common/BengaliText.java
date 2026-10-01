package com.bengalilkc.common;

import java.text.Normalizer;
import java.util.regex.Pattern;

/** Bengali written-form helpers shared by validation, duplicate detection and lookups. */
public final class BengaliText {

    private static final Pattern INVISIBLE = Pattern.compile("[\\u200B-\\u200D\\u2060\\uFEFF]");
    private static final Pattern SPACES = Pattern.compile("\\s+");

    /** Letters, vowel signs and marks of the Bengali block (no digits, currency or fraction signs), joiners, space, hyphen. */
    private static final Pattern ALLOWED = Pattern.compile("^[\\u0981-\\u09E3\\u09F0\\u09F1\\u09FC\\u09FE\\u200C\\u200D \\-]+$");
    /** Independent vowels and consonants a word may start with. */
    private static final Pattern STARTS_WITH_LETTER = Pattern.compile("^[\\u0985-\\u09B9\\u09CE\\u09DC-\\u09E1\\u09F0\\u09F1]");
    private static final Pattern BENGALI_CHAR = Pattern.compile("[\\u0980-\\u09FF]");
    private static final Pattern LATIN_LETTER = Pattern.compile("[A-Za-z]");

    private BengaliText() {}

    /** Trimmed, single-spaced form as it is stored. */
    public static String clean(String raw) {
        return raw == null ? "" : SPACES.matcher(raw.strip()).replaceAll(" ");
    }

    /** Comparison key; must stay identical to the SQL function {@code lkc_form_key}. */
    public static String key(String raw) {
        if (raw == null) {
            return "";
        }
        String nfc = Normalizer.normalize(raw, Normalizer.Form.NFC);
        return SPACES.matcher(INVISIBLE.matcher(nfc).replaceAll("")).replaceAll(" ").strip();
    }

    public static boolean onlyAllowedChars(String s) {
        return ALLOWED.matcher(s).matches();
    }

    public static boolean startsWithLetter(String s) {
        return STARTS_WITH_LETTER.matcher(s).find();
    }

    public static boolean hasBengali(String s) {
        return s != null && BENGALI_CHAR.matcher(s).find();
    }

    public static boolean hasLatin(String s) {
        return s != null && LATIN_LETTER.matcher(s).find();
    }

    public static int length(String s) {
        return s == null ? 0 : s.codePointCount(0, s.length());
    }
}
