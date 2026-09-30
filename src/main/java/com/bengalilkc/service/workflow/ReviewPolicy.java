package com.bengalilkc.service.workflow;

import com.bengalilkc.domain.enums.EntryState;
import com.bengalilkc.domain.enums.Role;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Which review decisions each role may take, from which state, into which state, and
 * what comment is required. Adding a role (e.g. Chief Lexical Editor) is a new entry here.
 */
@Component
public class ReviewPolicy {

    public static final int COMMENT_MAX = 1000;
    public static final int REASON_MIN = 10;
    /** The ESCALATE note becomes the arbitration case's dispute note, so it follows the dispute limits. */
    public static final int DISPUTE_NOTE_MIN = SubmissionService.DISPUTE_NOTE_MIN;
    private static final String DISPUTE_LABEL = "Dispute to arbitration";

    public enum Decision {
        APPROVE, FLAG, REJECT, VALIDATE, C_FLAG, ESCALATE;

        /** Accepts enum names and the legacy action names ("cflag", "validate", …). */
        public static Decision parse(String raw) {
            if (raw == null) {
                return null;
            }
            String key = raw.trim().toUpperCase(Locale.ROOT).replace('-', '_');
            if ("CFLAG".equals(key)) {
                return C_FLAG;
            }
            try {
                return valueOf(key);
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
    }

    /** Where the reviewer's latest comment is kept on the submission row. */
    public enum CommentField { EDITOR, CLE }

    /**
     * {@code commentMin} applies whenever a comment is given; {@code commentRequired}
     * decides whether it may be left empty.
     */
    public record Rule(
            Decision decision,
            String label,
            EntryState from,
            EntryState to,
            boolean commentRequired,
            int commentMin) {}

    public record RolePolicy(CommentField commentField, Map<Decision, Rule> rules) {}

    private final Map<Role, RolePolicy> policies = new EnumMap<>(Role.class);

    public ReviewPolicy() {
        policies.put(Role.LEXICAL_EDITOR, new RolePolicy(CommentField.EDITOR, ordered(
                new Rule(Decision.APPROVE, "Approve", EntryState.PENDING_REVIEW, EntryState.EDITOR_APPROVED, false, 0),
                new Rule(Decision.FLAG, "Flag for correction", EntryState.PENDING_REVIEW, EntryState.FLAGGED, true, REASON_MIN),
                new Rule(Decision.REJECT, "Reject", EntryState.PENDING_REVIEW, EntryState.REJECTED, true, REASON_MIN),
                new Rule(Decision.ESCALATE, DISPUTE_LABEL, EntryState.PENDING_REVIEW, EntryState.ARBITRATION, true, DISPUTE_NOTE_MIN))));
        policies.put(Role.CHIEF_LEXICAL_EDITOR, new RolePolicy(CommentField.CLE, ordered(
                new Rule(Decision.VALIDATE, "Final approve", EntryState.EDITOR_APPROVED, EntryState.VALIDATED, false, REASON_MIN),
                new Rule(Decision.C_FLAG, "C-Flag", EntryState.EDITOR_APPROVED, EntryState.C_FLAGGED, true, REASON_MIN),
                new Rule(Decision.ESCALATE, DISPUTE_LABEL, EntryState.EDITOR_APPROVED, EntryState.ARBITRATION, true, DISPUTE_NOTE_MIN))));
    }

    public RolePolicy forRole(Role role) {
        return role == null ? null : policies.get(role);
    }

    public Rule rule(Role role, Decision decision) {
        RolePolicy p = forRole(role);
        return p == null || decision == null ? null : p.rules().get(decision);
    }

    /** Decisions the role can take on an entry currently in {@code state}, in display order. */
    public List<Rule> available(Role role, EntryState state) {
        RolePolicy p = forRole(role);
        if (p == null) {
            return List.of();
        }
        return p.rules().values().stream().filter(r -> r.from() == state).toList();
    }

    private static Map<Decision, Rule> ordered(Rule... rules) {
        Map<Decision, Rule> m = new LinkedHashMap<>();
        for (Rule r : rules) {
            m.put(r.decision(), r);
        }
        return m;
    }
}
