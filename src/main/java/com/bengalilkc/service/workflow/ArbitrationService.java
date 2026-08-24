package com.bengalilkc.service.workflow;

import com.bengalilkc.common.exception.ApiException;
import com.bengalilkc.domain.entity.identity.AppUser;
import com.bengalilkc.domain.entity.workflow.ArbitrationCase;
import com.bengalilkc.domain.entity.workflow.ArbitrationVote;
import com.bengalilkc.domain.entity.workflow.StateTransition;
import com.bengalilkc.domain.entity.workflow.Submission;
import com.bengalilkc.domain.enums.ArbitrationStatus;
import com.bengalilkc.domain.enums.ArbitrationVerdict;
import com.bengalilkc.domain.enums.AuditTargetType;
import com.bengalilkc.domain.enums.DisputeType;
import com.bengalilkc.domain.enums.EntryState;
import com.bengalilkc.domain.enums.Permission;
import com.bengalilkc.domain.enums.Role;
import com.bengalilkc.domain.enums.VerdictBy;
import com.bengalilkc.repository.identity.AppUserRepository;
import com.bengalilkc.repository.workflow.ArbitrationCaseRepository;
import com.bengalilkc.repository.workflow.ArbitrationVoteRepository;
import com.bengalilkc.repository.workflow.StateTransitionRepository;
import com.bengalilkc.security.PermissionMatrix;
import com.bengalilkc.security.UserPrincipal;
import com.bengalilkc.service.admin.AuditService;
import com.bengalilkc.service.admin.IdGeneratorService;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ArbitrationService {

    private final ArbitrationCaseRepository caseRepository;
    private final ArbitrationVoteRepository voteRepository;
    private final AppUserRepository userRepository;
    private final StateTransitionRepository transitionRepository;
    private final IdGeneratorService idGeneratorService;
    private final AuditService auditService;
    private final SubmissionService submissionService;

    public ArbitrationService(
            ArbitrationCaseRepository caseRepository,
            ArbitrationVoteRepository voteRepository,
            AppUserRepository userRepository,
            StateTransitionRepository transitionRepository,
            IdGeneratorService idGeneratorService,
            AuditService auditService,
            @Lazy SubmissionService submissionService) {
        this.caseRepository = caseRepository;
        this.voteRepository = voteRepository;
        this.userRepository = userRepository;
        this.transitionRepository = transitionRepository;
        this.idGeneratorService = idGeneratorService;
        this.auditService = auditService;
        this.submissionService = submissionService;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list() {
        return caseRepository.findAllByOrderByOpenedAtDesc().stream().map(this::toDto).toList();
    }

    @Transactional(readOnly = true)
    public Map<String, Object> get(String id) {
        return toDto(require(id));
    }

    @Transactional
    public Map<String, Object> openEscalation(UserPrincipal principal, Submission sub, String comment) {
        if (!PermissionMatrix.has(principal.getRole(), Permission.ESCALATE)) {
            throw ApiException.forbidden("Not authorised.");
        }
        if (!List.of(EntryState.PENDING_REVIEW, EntryState.EDITOR_APPROVED, EntryState.C_FLAGGED)
                .contains(sub.getState())) {
            throw ApiException.unprocessable("This entry's state cannot be escalated.");
        }
        if (comment.length() < 30) {
            throw ApiException.unprocessable("Escalation notes must be at least 30 characters.");
        }
        String caseId = createCase(sub, principal.getId(), sub.getContributorId(), DisputeType.ESCALATION, comment, sub.getState());
        submissionService.transition(sub, EntryState.ARBITRATION, principal.getId(), principal.getRole().name(), comment);
        auditService.record(principal, "CASE_OPENED", AuditTargetType.ARBITRATION, caseId,
                "Escalation for \"" + sub.getWrittenForm() + "\" — 72-hour response window started");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("state", sub.getState().name());
        body.put("caseId", caseId);
        return body;
    }

    @Transactional
    public Map<String, Object> openDispute(UserPrincipal principal, Submission sub, String comment) {
        if (!sub.getContributorId().equals(principal.getId())
                || !PermissionMatrix.has(principal.getRole(), Permission.DISPUTE)) {
            throw ApiException.forbidden("Only the submitting contributor may dispute.");
        }
        if (!List.of(EntryState.REJECTED, EntryState.FLAGGED, EntryState.C_FLAGGED).contains(sub.getState())) {
            throw ApiException.unprocessable(
                    "Disputes are only available on REJECTED, FLAGGED or C-FLAGGED entries.");
        }
        if (comment.length() < 30) {
            throw ApiException.unprocessable(
                    "A dispute note of at least 30 characters is mandatory (SRS §7.3.3).");
        }
        String accusedId = transitionRepository.findBySubmissionIdOrderByCreatedAtAsc(sub.getId()).stream()
                .sorted((a, b) -> b.getCreatedAt().compareTo(a.getCreatedAt()))
                .filter(h -> List.of(EntryState.REJECTED, EntryState.FLAGGED, EntryState.C_FLAGGED).contains(h.getToState()))
                .map(StateTransition::getActorId)
                .findFirst()
                .orElse("u-le1");
        DisputeType type = sub.getState() == EntryState.REJECTED ? DisputeType.DISPUTE_REJECTION : DisputeType.DISPUTE_FLAG;
        String caseId = createCase(sub, principal.getId(), accusedId, type, comment, sub.getState());
        submissionService.transition(sub, EntryState.ARBITRATION, principal.getId(), principal.getRole().name(), comment);
        auditService.record(principal, "CASE_OPENED", AuditTargetType.ARBITRATION, caseId,
                "Contributor dispute for \"" + sub.getWrittenForm()
                        + "\" — accused party has 72 hours to respond");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("state", sub.getState().name());
        body.put("caseId", caseId);
        return body;
    }

    private String createCase(
            Submission sub,
            String claimantId,
            String accusedId,
            DisputeType type,
            String note,
            EntryState preState) {
        String caseId = "case-" + idGeneratorService.nextValue("case");
        String excluded = userRepository.findByRoleAndActiveTrueAndSuspendedFalse(Role.ARBITRATION_CURATOR).stream()
                .map(AppUser::getId)
                .filter(id -> id.equals(claimantId) || id.equals(accusedId))
                .collect(Collectors.joining(","));
        ArbitrationCase c = ArbitrationCase.builder()
                .id(caseId)
                .submissionId(sub.getId())
                .claimantId(claimantId)
                .accusedId(accusedId)
                .disputeType(type)
                .disputeNote(note)
                .openedAt(Instant.now())
                .responseDeadline(Instant.now().plus(72, ChronoUnit.HOURS))
                .status(ArbitrationStatus.RESPONSE_WINDOW)
                .excludedCuratorIds(excluded)
                .preArbitrationState(preState)
                .build();
        caseRepository.save(c);
        return caseId;
    }

    @Transactional
    public Map<String, Object> action(UserPrincipal principal, String id, Map<String, Object> p) {
        ArbitrationCase c = require(id);
        String action = str(p.get("action"));
        return switch (action) {
            case "vote" -> vote(principal, c, p);
            case "tiebreak" -> tiebreak(principal, c, p);
            case "respond" -> respond(principal, c, p);
            case "activate" -> activate(principal, c);
            case "apply_correction" -> applyCorrection(principal, c);
            default -> throw ApiException.unprocessable("Unknown action.");
        };
    }

    private Map<String, Object> vote(UserPrincipal principal, ArbitrationCase c, Map<String, Object> p) {
        if (!PermissionMatrix.has(principal.getRole(), Permission.ARBITRATION_VOTE)) {
            throw ApiException.forbidden("Only Arbitration Curators may vote.");
        }
        if (c.getStatus() != ArbitrationStatus.ACTIVE) {
            throw ApiException.unprocessable(
                    c.getStatus() == ArbitrationStatus.RESPONSE_WINDOW
                            ? "The 72-hour response window has not closed yet."
                            : "This case is no longer open for voting.");
        }
        List<String> excluded = parseExcluded(c.getExcludedCuratorIds());
        if (excluded.contains(principal.getId())) {
            throw ApiException.forbidden("Conflict of interest: you are a party to this case.");
        }
        if (voteRepository.existsByCaseIdAndCuratorId(c.getId(), principal.getId())) {
            throw ApiException.unprocessable("You have already voted on this case.");
        }
        ArbitrationVerdict verdict;
        try {
            verdict = ArbitrationVerdict.valueOf(str(p.get("verdict")));
        } catch (Exception e) {
            throw ApiException.unprocessable("A verdict is required.");
        }
        String rationale = str(p.get("rationale")).trim();
        if (rationale.length() < 30) {
            throw ApiException.unprocessable("A rationale of at least 30 characters is mandatory.");
        }
        voteRepository.save(ArbitrationVote.builder()
                .caseId(c.getId())
                .curatorId(principal.getId())
                .verdict(verdict)
                .rationale(rationale)
                .correctionNote(blankToNull(str(p.get("correctionNote")).trim()))
                .votedAt(Instant.now())
                .build());
        auditService.record(principal, "ARBITRATION_VOTE", AuditTargetType.ARBITRATION, c.getId(),
                "Vote recorded (verdicts hidden from parties)");
        evaluateCase(c);
        if (c.getStatus() == ArbitrationStatus.RESOLVED) {
            applyVerdict(c, principal.getId());
        }
        if (c.getStatus() == ArbitrationStatus.TIE_PENDING) {
            auditService.recordSystem("TIE_DETECTED", AuditTargetType.ARBITRATION, c.getId(),
                    "Quorum met with a draw — System Moderator tie-break required");
        }
        return Map.of("ok", true, "status", c.getStatus().name());
    }

    private Map<String, Object> tiebreak(UserPrincipal principal, ArbitrationCase c, Map<String, Object> p) {
        if (!PermissionMatrix.has(principal.getRole(), Permission.TIE_BREAKER)) {
            throw ApiException.forbidden("Only the System Moderator may break ties.");
        }
        if (c.getStatus() != ArbitrationStatus.TIE_PENDING) {
            throw ApiException.unprocessable("This case is not awaiting a tie-break.");
        }
        ArbitrationVerdict verdict;
        try {
            verdict = ArbitrationVerdict.valueOf(str(p.get("verdict")));
        } catch (Exception e) {
            throw ApiException.unprocessable("A verdict is required.");
        }
        String rationale = str(p.get("rationale")).trim();
        if (rationale.length() < 30) {
            throw ApiException.unprocessable("A tie-break rationale of at least 30 characters is mandatory.");
        }
        c.setFinalVerdict(verdict);
        c.setVerdictBy(VerdictBy.TIE_BREAK);
        c.setTieBrokenBy(principal.getId());
        c.setStatus(ArbitrationStatus.RESOLVED);
        c.setResolvedAt(Instant.now());
        c.setCorrectionNote(voteRepository.findByCaseId(c.getId()).stream()
                .map(ArbitrationVote::getCorrectionNote)
                .filter(n -> n != null && !n.isBlank())
                .findFirst()
                .orElse(null));
        caseRepository.save(c);
        auditService.record(principal, "TIE_BREAK_VERDICT", AuditTargetType.ARBITRATION, c.getId(),
                "Final verdict: " + verdict + " — " + rationale.substring(0, Math.min(60, rationale.length())));
        applyVerdict(c, principal.getId());
        return Map.of("ok", true, "status", c.getStatus().name());
    }

    private Map<String, Object> respond(UserPrincipal principal, ArbitrationCase c, Map<String, Object> p) {
        if (!principal.getId().equals(c.getAccusedId())) {
            throw ApiException.forbidden("Only the accused party may submit a counter-claim.");
        }
        if (c.getStatus() != ArbitrationStatus.RESPONSE_WINDOW) {
            throw ApiException.unprocessable("The response window for this case has closed.");
        }
        String counter = str(p.get("counterClaim")).trim();
        if (counter.length() < 15) {
            throw ApiException.unprocessable("Counter-claim must be at least 15 characters.");
        }
        c.setCounterClaim(counter);
        c.setStatus(ArbitrationStatus.ACTIVE);
        caseRepository.save(c);
        auditService.record(principal, "COUNTER_CLAIM", AuditTargetType.ARBITRATION, c.getId(),
                "Counter-claim submitted — case now active for voting");
        return Map.of("ok", true, "status", c.getStatus().name());
    }

    private Map<String, Object> activate(UserPrincipal principal, ArbitrationCase c) {
        if (!PermissionMatrix.has(principal.getRole(), Permission.OVERRIDE_STATE)) {
            throw ApiException.forbidden("Not authorised.");
        }
        if (c.getStatus() != ArbitrationStatus.RESPONSE_WINDOW) {
            throw ApiException.unprocessable("Case is not in a response window.");
        }
        c.setStatus(ArbitrationStatus.ACTIVE);
        caseRepository.save(c);
        auditService.record(principal, "CASE_ACTIVATED", AuditTargetType.ARBITRATION, c.getId(),
                "Response window closed without counter-claim — case active for voting");
        return Map.of("ok", true, "status", c.getStatus().name());
    }

    private Map<String, Object> applyCorrection(UserPrincipal principal, ArbitrationCase c) {
        if (!PermissionMatrix.has(principal.getRole(), Permission.APPLY_CORRECTIONS)) {
            throw ApiException.forbidden("Only the System Moderator may apply panel corrections.");
        }
        if (c.getStatus() != ArbitrationStatus.RESOLVED || c.getCorrectionNote() == null) {
            throw ApiException.unprocessable("No pending correction on this case.");
        }
        if (c.isCorrectionApplied()) {
            throw ApiException.unprocessable("Correction already applied.");
        }
        c.setCorrectionApplied(true);
        caseRepository.save(c);
        auditService.record(principal, "CORRECTION_APPLIED", AuditTargetType.ARBITRATION, c.getId(),
                c.getCorrectionNote().substring(0, Math.min(80, c.getCorrectionNote().length())));
        return Map.of("ok", true);
    }

    public void evaluateCase(ArbitrationCase c) {
        List<AppUser> eligible = userRepository.findByRoleAndActiveTrueAndSuspendedFalse(Role.ARBITRATION_CURATOR).stream()
                .filter(u -> !parseExcluded(c.getExcludedCuratorIds()).contains(u.getId()))
                .toList();
        List<ArbitrationVote> votes = voteRepository.findByCaseId(c.getId());
        int quorum = (int) Math.ceil((eligible.size() * 2.0) / 3.0);
        if (votes.size() < quorum) {
            return;
        }
        long approve = votes.stream().filter(v -> v.getVerdict() != ArbitrationVerdict.REJECT).count();
        long reject = votes.stream().filter(v -> v.getVerdict() == ArbitrationVerdict.REJECT).count();
        if (votes.size() >= eligible.size() || Math.abs(approve - reject) > eligible.size() - votes.size()) {
            if (approve == reject) {
                c.setStatus(ArbitrationStatus.TIE_PENDING);
                caseRepository.save(c);
                return;
            }
            long claimant = votes.stream().filter(v -> v.getVerdict() == ArbitrationVerdict.APPROVE_CLAIMANT).count();
            long accused = votes.stream().filter(v -> v.getVerdict() == ArbitrationVerdict.APPROVE_ACCUSED).count();
            c.setFinalVerdict(approve > reject
                    ? (claimant >= accused ? ArbitrationVerdict.APPROVE_CLAIMANT : ArbitrationVerdict.APPROVE_ACCUSED)
                    : ArbitrationVerdict.REJECT);
            c.setVerdictBy(VerdictBy.PANEL);
            c.setStatus(ArbitrationStatus.RESOLVED);
            c.setResolvedAt(Instant.now());
            c.setCorrectionNote(votes.stream()
                    .map(ArbitrationVote::getCorrectionNote)
                    .filter(n -> n != null && !n.isBlank())
                    .findFirst()
                    .orElse(null));
            caseRepository.save(c);
        }
    }

    private void applyVerdict(ArbitrationCase c, String actorId) {
        Submission sub = submissionService.require(c.getSubmissionId());
        if (c.getFinalVerdict() == null) {
            return;
        }
        if (c.getFinalVerdict() == ArbitrationVerdict.REJECT) {
            EntryState prior = c.getPreArbitrationState();
            if (prior == null) {
                prior = transitionRepository.findBySubmissionIdOrderByCreatedAtAsc(sub.getId()).stream()
                        .sorted((a, b) -> b.getCreatedAt().compareTo(a.getCreatedAt()))
                        .filter(h -> h.getToState() == EntryState.ARBITRATION)
                        .map(StateTransition::getFromState)
                        .findFirst()
                        .orElse(EntryState.REJECTED);
            }
            submissionService.transition(sub, prior, actorId, "SYSTEM",
                    "Arbitration verdict: previous decision upheld. No further appeals possible.");
        } else {
            submissionService.transition(sub, EntryState.VALIDATED, actorId, "SYSTEM",
                    "Arbitration verdict: "
                            + (c.getFinalVerdict() == ArbitrationVerdict.APPROVE_CLAIMANT
                                    ? "claimant's"
                                    : "accused party's")
                            + " version approved by panel.");
            submissionService.markValidated(sub.getId(), actorId);
        }
    }

    public Map<String, Object> toDto(ArbitrationCase c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.getId());
        m.put("submissionId", c.getSubmissionId());
        m.put("claimantId", c.getClaimantId());
        m.put("accusedId", c.getAccusedId());
        m.put("disputeType", c.getDisputeType().name());
        m.put("disputeNote", c.getDisputeNote());
        m.put("counterClaim", c.getCounterClaim());
        m.put("openedAt", c.getOpenedAt() != null ? c.getOpenedAt().toString() : null);
        m.put("responseDeadline", c.getResponseDeadline() != null ? c.getResponseDeadline().toString() : null);
        m.put("status", c.getStatus().name());
        m.put("excludedCuratorIds", parseExcluded(c.getExcludedCuratorIds()));
        m.put("votes", voteRepository.findByCaseId(c.getId()).stream().map(v -> {
            Map<String, Object> vote = new LinkedHashMap<>();
            vote.put("curatorId", v.getCuratorId());
            vote.put("verdict", v.getVerdict().name());
            vote.put("rationale", v.getRationale());
            vote.put("correctionNote", v.getCorrectionNote());
            vote.put("votedAt", v.getVotedAt() != null ? v.getVotedAt().toString() : null);
            return vote;
        }).toList());
        m.put("finalVerdict", c.getFinalVerdict() != null ? c.getFinalVerdict().name() : null);
        m.put("verdictBy", c.getVerdictBy() != null ? c.getVerdictBy().name() : null);
        m.put("tieBrokenBy", c.getTieBrokenBy());
        m.put("resolvedAt", c.getResolvedAt() != null ? c.getResolvedAt().toString() : null);
        m.put("correctionApplied", c.isCorrectionApplied());
        m.put("correctionNote", c.getCorrectionNote());
        return m;
    }

    private ArbitrationCase require(String id) {
        return caseRepository.findById(id).orElseThrow(() -> ApiException.notFound("Case not found"));
    }

    private static List<String> parseExcluded(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return Arrays.stream(raw.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
