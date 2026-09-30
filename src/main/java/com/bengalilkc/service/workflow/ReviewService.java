package com.bengalilkc.service.workflow;

import com.bengalilkc.common.exception.ApiException;
import com.bengalilkc.domain.entity.identity.AppUser;
import com.bengalilkc.domain.entity.workflow.Submission;
import com.bengalilkc.domain.enums.AuditTargetType;
import com.bengalilkc.domain.enums.EntryState;
import com.bengalilkc.service.admin.AuditService;
import com.bengalilkc.repository.identity.AppUserRepository;
import com.bengalilkc.repository.workflow.SubmissionRepository;
import com.bengalilkc.security.UserPrincipal;
import com.bengalilkc.service.workflow.ReviewPolicy.CommentField;
import com.bengalilkc.service.workflow.ReviewPolicy.Decision;
import com.bengalilkc.service.workflow.ReviewPolicy.RolePolicy;
import com.bengalilkc.service.workflow.ReviewPolicy.Rule;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Role-based review decisions (approve / flag / reject / escalate) on a submission.
 * Every decision is validated against {@link ReviewPolicy}, recorded as a state
 * transition with the reviewer's comment, and mirrored onto the submission row.
 */
@Service
@RequiredArgsConstructor
public class ReviewService {

    private final ReviewPolicy policy;
    private final SubmissionService submissionService;
    private final SubmissionRepository submissionRepository;
    private final ArbitrationService arbitrationService;
    private final AppUserRepository userRepository;
    private final AuditService auditService;

    public boolean handles(UserPrincipal principal, String action) {
        return policy.rule(principal.getRole(), Decision.parse(action)) != null;
    }

    @Transactional
    public Map<String, Object> review(UserPrincipal principal, String submissionId, Map<String, Object> body) {
        RolePolicy rolePolicy = policy.forRole(principal.getRole());
        if (rolePolicy == null) {
            throw ApiException.forbidden("Your role cannot review submissions.");
        }

        Decision decision = Decision.parse(str(body.get("decision")));
        if (decision == null) {
            throw ApiException.badRequest(Map.of("decision",
                    "Decision must be one of " + String.join(", ", rolePolicy.rules().keySet().stream().map(Enum::name).toList()) + "."));
        }
        Rule rule = rolePolicy.rules().get(decision);
        if (rule == null) {
            throw ApiException.forbidden("Your role cannot take the " + decision.name() + " decision.");
        }

        String comment = str(body.get("comment")).strip();
        String commentError = validateComment(rule, comment);
        if (commentError != null) {
            throw ApiException.unprocessable(Map.of(decision == Decision.ESCALATE ? "note" : "comment", commentError));
        }

        Submission sub = submissionService.require(submissionId);
        Object expected = body.get("expectedVersion");
        if (expected instanceof Number n && sub.getVersion() != null && n.longValue() != sub.getVersion()) {
            throw ApiException.conflict(
                    "This entry was updated by someone else after you opened it. Reload to see its current state.");
        }
        if (sub.getState() != rule.from()) {
            throw ApiException.conflict("This entry is now " + sub.getState().name()
                    + "; only " + rule.from().name() + " entries can be reviewed with this action.");
        }
        String assignee = otherAssignee(principal, sub);
        if (assignee != null) {
            throw ApiException.forbidden("This entry is assigned to " + assignee + ". Only the assigned reviewer can review it.");
        }
        if (sub.getContributorId().equals(principal.getId())) {
            throw ApiException.forbidden("You cannot review your own submission.");
        }

        EntryState from = sub.getState();
        String stored = comment.isEmpty() ? null : comment;
        // A dispute note lives on the arbitration case and in the history, not in the review comment fields.
        if (stored != null && decision != Decision.ESCALATE) {
            if (rolePolicy.commentField() == CommentField.EDITOR) {
                sub.setEditorComment(stored);
            } else {
                sub.setCleComment(stored);
            }
        }
        if (decision == Decision.REJECT) {
            sub.setRejectionReason(stored);
        }
        if (decision == Decision.VALIDATE) {
            sub.setValidatedBy(principal.getId());
            sub.setValidatedAt(Instant.now());
        }

        String caseId = null;
        if (decision == Decision.ESCALATE) {
            caseId = (String) arbitrationService.startEscalation(principal, sub, comment).get("caseId");
        } else {
            submissionService.transition(sub, rule.to(), principal.getId(), principal.getRole().name(), stored);
        }
        if (decision == Decision.C_FLAG) {
            auditService.recordSystem("NOTIFY", AuditTargetType.SUBMISSION, sub.getId(),
                    "C_FLAG notification sent to Lexical Editor and Contributor simultaneously");
        }
        submissionRepository.flush();

        Map<String, Object> res = new LinkedHashMap<>();
        res.put("ok", true);
        res.put("id", sub.getId());
        res.put("decision", decision.name());
        res.put("previousState", from.name());
        res.put("state", sub.getState().name());
        res.put("comment", stored);
        res.put("reviewedBy", principal.getId());
        res.put("reviewerRole", principal.getRole().name());
        res.put("reviewedAt", Instant.now().toString());
        res.put("version", sub.getVersion());
        if (caseId != null) {
            res.put("caseId", caseId);
        }
        return res;
    }

    /**
     * Decisions the caller may take on this entry right now, with comment rules, so the
     * UI renders exactly what the API will accept. {@code blockedReason} explains why none apply
     * in a state the role normally acts on (or under arbitration); when the role has no decision
     * in this state at all, both are empty so the UI shows no review section.
     */
    public Map<String, Object> options(UserPrincipal principal, Submission sub) {
        RolePolicy rolePolicy = policy.forRole(principal.getRole());
        if (rolePolicy == null) {
            return null;
        }
        List<Rule> rules = policy.available(principal.getRole(), sub.getState());
        String blocked = null;
        if (sub.getState() == EntryState.ARBITRATION) {
            blocked = "This entry is under arbitration. No review action is available until the panel reaches a verdict.";
        } else if (!rules.isEmpty()) {
            String assignee = otherAssignee(principal, sub);
            if (assignee != null) {
                blocked = "Assigned to " + assignee + ". Only the assigned reviewer can review this entry.";
            } else if (sub.getContributorId().equals(principal.getId())) {
                blocked = "You cannot review your own submission.";
            }
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("commentMax", ReviewPolicy.COMMENT_MAX);
        m.put("blockedReason", blocked);
        m.put("decisions", blocked != null ? List.of() : rules.stream().map(r -> {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("decision", r.decision().name());
            d.put("label", r.label());
            d.put("toState", r.to().name());
            d.put("commentRequired", r.commentRequired());
            d.put("commentMin", r.commentMin());
            return d;
        }).toList());
        return m;
    }

    /**
     * Name of another reviewer of the caller's own role holding the assignment, else null.
     * An assignment left over from an earlier stage (e.g. the Lexical Editor once the entry
     * reaches the Chief's queue) does not lock the entry.
     */
    private String otherAssignee(UserPrincipal principal, Submission sub) {
        if (sub.getAssignedTo() == null || sub.getAssignedTo().equals(principal.getId())) {
            return null;
        }
        return userRepository.findById(sub.getAssignedTo())
                .filter(u -> u.getRole() == principal.getRole())
                .map(AppUser::getName)
                .orElse(null);
    }

    private static String validateComment(Rule rule, String comment) {
        if (rule.decision() == Decision.ESCALATE) {
            if (comment.isEmpty()) {
                return "A dispute note is required.";
            }
            if (comment.length() < rule.commentMin()) {
                return "Dispute note must be at least " + rule.commentMin() + " characters.";
            }
            if (comment.length() > ReviewPolicy.COMMENT_MAX) {
                return "Dispute note must be at most " + ReviewPolicy.COMMENT_MAX + " characters.";
            }
            return null;
        }
        if (comment.length() > ReviewPolicy.COMMENT_MAX) {
            return "Comment must be at most " + ReviewPolicy.COMMENT_MAX + " characters.";
        }
        if (rule.commentRequired() && comment.isEmpty()) {
            return "A comment / reason is required to " + rule.label().toLowerCase() + ".";
        }
        if (!comment.isEmpty() && comment.length() < rule.commentMin()) {
            return "Comment must be at least " + rule.commentMin() + " characters.";
        }
        return null;
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }
}
