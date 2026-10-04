package com.bengalilkc.web.controller;

import com.bengalilkc.common.exception.ApiException;
import com.bengalilkc.domain.enums.EntryState;
import com.bengalilkc.domain.enums.Permission;
import com.bengalilkc.domain.enums.Role;
import com.bengalilkc.security.PermissionMatrix;
import com.bengalilkc.security.SecurityUtils;
import com.bengalilkc.security.UserPrincipal;
import com.bengalilkc.service.community.LeaderboardService;
import com.bengalilkc.service.dashboard.DashboardService;
import com.bengalilkc.service.workflow.ArbitrationService;
import com.bengalilkc.service.workflow.QuotaService;
import com.bengalilkc.service.workflow.SubmissionService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/dashboard")
@RequiredArgsConstructor
public class DashboardController {

    private final SubmissionService submissionService;
    private final ArbitrationService arbitrationService;
    private final LeaderboardService leaderboardService;
    private final DashboardService dashboardService;
    private final QuotaService quotaService;

    @GetMapping("/me")
    public Map<String, Object> me() {
        return dashboardService.forUser(SecurityUtils.requirePrincipal());
    }

    /** The caller's submission quota usage (session, 24 hours, suspension and anomaly thresholds). */
    @GetMapping("/quota")
    public Map<String, Object> quota() {
        return quotaService.usage(SecurityUtils.requirePrincipal().getId());
    }

    @GetMapping("/stats")
    public Map<String, Object> stats() {
        UserPrincipal principal = SecurityUtils.requirePrincipal();
        if (!PermissionMatrix.has(principal.getRole(), Permission.STATISTICS)) {
            throw ApiException.forbidden("You do not have access to platform statistics.");
        }
        return dashboardService.statistics();
    }

    @GetMapping("/queues")
    public Map<String, Object> queues() {
        UserPrincipal principal = SecurityUtils.requirePrincipal();
        if (!PermissionMatrix.has(principal.getRole(), Permission.STATISTICS)) {
            throw ApiException.forbidden("You do not have access to the review queues.");
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("pendingReview", submissionService.listByState(EntryState.PENDING_REVIEW));
        m.put("editorApproved", submissionService.listByState(EntryState.EDITOR_APPROVED));
        m.put("underReview", submissionService.listByState(EntryState.UNDER_REVIEW));
        m.put("arbitration", arbitrationService.list());
        return m;
    }

    @GetMapping("/leaderboard")
    public Map<String, Object> leaderboard(@RequestParam(required = false) String role) {
        return leaderboardService.boardFor(SecurityUtils.requirePrincipal(), role);
    }
}
