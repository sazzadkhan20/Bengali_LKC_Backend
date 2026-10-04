package com.bengalilkc.scheduling;

import com.bengalilkc.domain.enums.ArbitrationStatus;
import com.bengalilkc.domain.enums.AuditTargetType;
import com.bengalilkc.repository.workflow.ArbitrationCaseRepository;
import com.bengalilkc.service.admin.AuditService;
import com.bengalilkc.service.workflow.ArbitrationSchedule;
import com.bengalilkc.service.workflow.ArbitrationService;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.locks.ReentrantLock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Daily arbitration deadline check (10:00 Asia/Dhaka): closes expired 72-hour response windows and 7-day voting
 * rounds. Each case is processed in its own transaction so one failure never blocks the rest.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ArbitrationDeadlineJob {

    private final ArbitrationCaseRepository caseRepository;
    private final ArbitrationService arbitrationService;
    private final AuditService auditService;
    private final ReentrantLock running = new ReentrantLock();

    @Scheduled(cron = ArbitrationSchedule.CHECK_CRON, zone = ArbitrationSchedule.ZONE_ID)
    public void scheduledRun() {
        run("SCHEDULED");
    }

    /** Runs the check now; returns null if a run is already in progress. */
    public Map<String, Object> run(String trigger) {
        if (!running.tryLock()) {
            log.warn("Arbitration deadline check skipped ({}): a run is already in progress", trigger);
            return null;
        }
        try {
            // Minute precision keeps a voting round opened by this run due exactly at the same check 7 days later.
            Instant runAt = Instant.now().truncatedTo(ChronoUnit.MINUTES);
            Map<String, Integer> outcomes = new TreeMap<>();
            int failed = 0;
            List<String> windows = caseRepository.findIdsWithResponseWindowDue(ArbitrationStatus.RESPONSE_WINDOW, runAt);
            for (String id : windows) {
                failed += process(id, outcomes, () -> arbitrationService.closeResponseWindow(id, runAt));
            }
            List<String> rounds = caseRepository.findIdsWithVotingDue(ArbitrationStatus.ACTIVE, runAt);
            for (String id : rounds) {
                failed += process(id, outcomes, () -> arbitrationService.closeVotingRound(id, runAt));
            }
            int processed = outcomes.values().stream().mapToInt(Integer::intValue).sum();
            if (processed > 0 || failed > 0) {
                auditService.recordSystem("ARBITRATION_DEADLINE_CHECK", AuditTargetType.ARBITRATION, null,
                        trigger + " run: " + processed + " case(s) processed " + outcomes
                                + (failed > 0 ? ", " + failed + " failed" : ""));
            }
            log.info("Arbitration deadline check ({}): {} processed {}, {} failed", trigger, processed, outcomes, failed);
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("trigger", trigger);
            summary.put("ranAt", runAt.toString());
            summary.put("processed", processed);
            summary.put("failed", failed);
            summary.put("outcomes", outcomes);
            return summary;
        } finally {
            running.unlock();
        }
    }

    private int process(String caseId, Map<String, Integer> outcomes, java.util.function.Supplier<String> step) {
        try {
            String outcome = step.get();
            if (outcome != null) {
                outcomes.merge(outcome, 1, Integer::sum);
            }
            return 0;
        } catch (RuntimeException e) {
            log.error("Arbitration deadline check failed for {}", caseId, e);
            return 1;
        }
    }
}
