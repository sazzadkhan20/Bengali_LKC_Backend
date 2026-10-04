package com.bengalilkc.domain.enums;

/**
 * NO_RESPONSE: only one side submitted a note within the response window, so that side is upheld.
 * LAPSED: neither side submitted a note; the entry returns to its pre-arbitration state.
 */
public enum VerdictBy {
    PANEL, TIE_BREAK, EMERGENCY_OVERRIDE, NO_RESPONSE, LAPSED
}
