package com.bengalilkc.web.controller;

import com.bengalilkc.common.exception.ApiException;
import com.bengalilkc.domain.enums.Role;
import com.bengalilkc.scheduling.ArbitrationDeadlineJob;
import com.bengalilkc.security.SecurityUtils;
import com.bengalilkc.security.UserPrincipal;
import com.bengalilkc.service.workflow.ArbitrationService;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/arbitration")
@RequiredArgsConstructor
public class ArbitrationController {

    private final ArbitrationService arbitrationService;
    private final ArbitrationDeadlineJob deadlineJob;

    /**
     * Paged cases. Curators and managers see every case, contributors and reviewers the cases they are party to.
     * Filters: q (word, definition, case/entry id, party name), phase (OPEN|RESPONSE_WINDOW|ACTIVE|TIE_PENDING|
     * RESOLVED|ALL, default OPEN), page (0-based), size (default 15, max 100).
     */
    @GetMapping
    public Map<String, Object> browse(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String phase,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return arbitrationService.browse(
                SecurityUtils.requirePrincipal(), new ArbitrationService.Criteria(q, phase, page, size));
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable String id) {
        return arbitrationService.detail(SecurityUtils.requirePrincipal(), id);
    }

    /** Actions: note {note} for the contributor / reviewer; vote {verdict: CONTRIBUTOR|REVIEWER, rationale}. */
    @PostMapping("/{id}")
    public Map<String, Object> action(@PathVariable String id, @RequestBody Map<String, Object> body) {
        return arbitrationService.action(SecurityUtils.requirePrincipal(), id, body);
    }

    /** Runs the daily deadline check immediately (Platform Architect only, for operations and recovery). */
    @PostMapping("/scheduler/run")
    public Map<String, Object> runDeadlineCheck() {
        UserPrincipal principal = SecurityUtils.requirePrincipal();
        if (principal.getRole() != Role.PLATFORM_ARCHITECT) {
            throw ApiException.forbidden("Only the Platform Architect can run the deadline check manually.");
        }
        Map<String, Object> summary = deadlineJob.run("MANUAL by " + principal.getId());
        if (summary == null) {
            throw ApiException.conflict("A deadline check is already running. Try again shortly.");
        }
        return summary;
    }
}
