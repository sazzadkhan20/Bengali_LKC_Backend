package com.bengalilkc.web.controller;

import com.bengalilkc.domain.enums.EntryState;
import com.bengalilkc.domain.enums.Permission;
import com.bengalilkc.domain.enums.Role;
import com.bengalilkc.repository.workflow.ArbitrationCaseRepository;
import com.bengalilkc.repository.workflow.SubmissionRepository;
import com.bengalilkc.security.PermissionMatrix;
import com.bengalilkc.security.SecurityUtils;
import com.bengalilkc.security.UserPrincipal;
import com.bengalilkc.service.community.LeaderboardService;
import com.bengalilkc.service.workflow.ArbitrationService;
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

    private final SubmissionRepository submissionRepository;
    private final ArbitrationCaseRepository caseRepository;
    private final SubmissionService submissionService;
    private final ArbitrationService arbitrationService;
    private final LeaderboardService leaderboardService;

    @GetMapping("/stats")
    public Map<String, Object> stats() {
        UserPrincipal principal = SecurityUtils.requirePrincipal();
        if (!PermissionMatrix.has(principal.getRole(), Permission.STATISTICS)) {
            return Map.of("error", "Not authorised.");
        }
        Map<String, Object> m = new LinkedHashMap<>();
        for (EntryState state : EntryState.values()) {
            m.put(state.name(), submissionRepository.countByState(state));
        }
        m.put("cases", caseRepository.count());
        return m;
    }

    @GetMapping("/queues")
    public Map<String, Object> queues() {
        SecurityUtils.requirePrincipal();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("pendingReview", submissionService.listByState(EntryState.PENDING_REVIEW));
        m.put("editorApproved", submissionService.listByState(EntryState.EDITOR_APPROVED));
        m.put("underReview", submissionService.listByState(EntryState.UNDER_REVIEW));
        m.put("arbitration", arbitrationService.list());
        return m;
    }

    @GetMapping("/leaderboard")
    public List<Map<String, Object>> leaderboard(@RequestParam(defaultValue = "CONTRIBUTOR") String role) {
        SecurityUtils.requirePrincipal();
        Role ranked;
        try {
            ranked = Role.valueOf(role);
        } catch (Exception e) {
            ranked = Role.CONTRIBUTOR;
        }
        return leaderboardService.leaderboardFor(ranked);
    }
}
