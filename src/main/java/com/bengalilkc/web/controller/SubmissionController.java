package com.bengalilkc.web.controller;

import com.bengalilkc.domain.enums.Permission;
import com.bengalilkc.security.RequirePermission;
import com.bengalilkc.security.SecurityUtils;
import com.bengalilkc.security.UserPrincipal;
import com.bengalilkc.service.common.IdempotencyService;
import com.bengalilkc.service.workflow.ReviewService;
import com.bengalilkc.service.workflow.SubmissionBrowseService;
import com.bengalilkc.service.workflow.SubmissionService;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/submissions")
@RequiredArgsConstructor
public class SubmissionController {

    /** Fields that legitimately differ between a request and its retry. */
    private static final Set<String> VOLATILE_CREATE_FIELDS = Set.of("elapsedSeconds", "captchaPassed");

    private final SubmissionService submissionService;
    private final SubmissionBrowseService browseService;
    private final IdempotencyService idempotencyService;
    private final ReviewService reviewService;

    /**
     * Paged browse for Queue Management, the editor/CLE review queues and My Submissions.
     * Filters: q (word or definition), state (ALL|state), assignedTo (me|unassigned|userId),
     * contributorId (me|userId), page (0-based), size (default 15, max 100).
     */
    @GetMapping
    public Map<String, Object> browse(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String state,
            @RequestParam(required = false) String assignedTo,
            @RequestParam(required = false) String contributorId,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return browseService.browse(
                SecurityUtils.requirePrincipal(),
                new SubmissionBrowseService.Criteria(q, state, assignedTo, contributorId, page, size));
    }

    /**
     * Reviewer history: entries whose status the caller changed. Lexical Editor and Chief Lexical
     * Editor only; each role has its own set of filterable current states (returned as availableStates).
     * Filters: q (word or definition), state (ALL|state), page (0-based), size (default 15, max 100).
     */
    @GetMapping("/history")
    public Map<String, Object> history(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String state,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return browseService.history(
                SecurityUtils.requirePrincipal(),
                new SubmissionBrowseService.HistoryCriteria(q, state, page, size));
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable String id) {
        return browseService.detail(SecurityUtils.requirePrincipal(), id);
    }

    @PostMapping
    @RequirePermission(Permission.QUICK_INPUT)
    public ResponseEntity<Map<String, Object>> create(
            @RequestBody Map<String, Object> body,
            @RequestHeader(value = IdempotencyService.HEADER, required = false) String idempotencyKey) {
        UserPrincipal principal = SecurityUtils.requirePrincipal();
        return idempotencyService.execute(
                principal.getId(),
                "POST /api/submissions",
                idempotencyKey,
                body,
                VOLATILE_CREATE_FIELDS,
                () -> submissionService.create(principal, body));
    }

    /**
     * Role-based review decision. Body: {@code decision} (APPROVE|FLAG|REJECT|ESCALATE),
     * {@code comment} (max 1000; min 10 for FLAG/REJECT/ESCALATE), optional {@code expectedVersion}.
     */
    @PostMapping("/{id}/review")
    public Map<String, Object> review(@PathVariable String id, @RequestBody Map<String, Object> body) {
        return reviewService.review(SecurityUtils.requirePrincipal(), id, body);
    }

    /**
     * Owner's corrected resubmission of a FLAGGED / C_FLAGGED entry. Body: the full Quick Input
     * payload plus {@code expectedVersion}; FLAGGED returns to PENDING_REVIEW, C_FLAGGED to EDITOR_APPROVED.
     */
    @PostMapping("/{id}/resubmit")
    public Map<String, Object> resubmit(@PathVariable String id, @RequestBody Map<String, Object> body) {
        return submissionService.resubmit(SecurityUtils.requirePrincipal(), id, body);
    }

    /** Owner disputes a FLAGGED / C_FLAGGED decision. Body: {@code note} (15–1000), {@code expectedVersion}. */
    @PostMapping("/{id}/dispute")
    public Map<String, Object> dispute(@PathVariable String id, @RequestBody Map<String, Object> body) {
        return submissionService.dispute(SecurityUtils.requirePrincipal(), id, body);
    }

    @PostMapping("/{id}/action")
    public Map<String, Object> action(@PathVariable String id, @RequestBody Map<String, Object> body) {
        UserPrincipal principal = SecurityUtils.requirePrincipal();
        String action = body.get("action") == null ? "" : String.valueOf(body.get("action"));
        // Roles with a review policy must go through the same rules whichever endpoint is used.
        if (reviewService.handles(principal, action)) {
            Map<String, Object> reviewBody = new java.util.HashMap<>(body);
            reviewBody.put("decision", action);
            return reviewService.review(principal, id, reviewBody);
        }
        return submissionService.action(principal, id, body);
    }
}
