package com.bengalilkc.service.workflow;

import com.bengalilkc.common.exception.ApiException;
import com.bengalilkc.domain.entity.identity.AppUser;
import com.bengalilkc.domain.entity.lexicon.SynsetEntity;
import com.bengalilkc.domain.entity.workflow.ArbitrationCase;
import com.bengalilkc.domain.entity.workflow.ArbitrationVote;
import com.bengalilkc.domain.entity.workflow.StateTransition;
import com.bengalilkc.domain.entity.workflow.Submission;
import com.bengalilkc.domain.enums.ArbitrationOrigin;
import com.bengalilkc.domain.enums.ArbitrationStage;
import com.bengalilkc.domain.enums.ArbitrationStatus;
import com.bengalilkc.domain.enums.ArbitrationVerdict;
import com.bengalilkc.domain.enums.AuditTargetType;
import com.bengalilkc.domain.enums.DisputeType;
import com.bengalilkc.domain.enums.EntryState;
import com.bengalilkc.domain.enums.Permission;
import com.bengalilkc.domain.enums.Role;
import com.bengalilkc.domain.enums.VerdictBy;
import com.bengalilkc.repository.identity.AppUserRepository;
import com.bengalilkc.repository.lexicon.SynsetRepository;
import com.bengalilkc.repository.workflow.ArbitrationCaseRepository;
import com.bengalilkc.repository.workflow.ArbitrationVoteRepository;
import com.bengalilkc.repository.workflow.StateTransitionRepository;
import com.bengalilkc.security.PermissionMatrix;
import com.bengalilkc.security.UserPrincipal;
import com.bengalilkc.service.admin.AuditService;
import com.bengalilkc.service.admin.IdGeneratorService;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Arbitration between a contributor and the reviewer (Lexical Editor or Chief Lexical Editor) whose decision is
 * disputed. Each side keeps one editable note until the daily deadline check closes the 72-hour response window;
 * curators then vote in 7-day rounds until two thirds of the eligible panel has voted.
 */
@Service
public class ArbitrationService {

    public static final int NOTE_MIN = SubmissionService.DISPUTE_NOTE_MIN;
    public static final int NOTE_MAX = SubmissionService.DISPUTE_NOTE_MAX;
    public static final int RATIONALE_MIN = 20;
    public static final int RATIONALE_MAX = 1000;
    public static final int DEFAULT_SIZE = 15;
    public static final int MAX_SIZE = 100;
    private static final int MAX_QUERY_LENGTH = 100;
    static final String SYSTEM_ACTOR = "system";
    static final String SYSTEM_ROLE = "SYSTEM";

    private final ArbitrationCaseRepository caseRepository;
    private final ArbitrationVoteRepository voteRepository;
    private final AppUserRepository userRepository;
    private final StateTransitionRepository transitionRepository;
    private final SynsetRepository synsetRepository;
    private final NamedParameterJdbcTemplate jdbc;
    private final IdGeneratorService idGeneratorService;
    private final AuditService auditService;
    private final SubmissionService submissionService;

    public ArbitrationService(
            ArbitrationCaseRepository caseRepository,
            ArbitrationVoteRepository voteRepository,
            AppUserRepository userRepository,
            StateTransitionRepository transitionRepository,
            SynsetRepository synsetRepository,
            NamedParameterJdbcTemplate jdbc,
            IdGeneratorService idGeneratorService,
            AuditService auditService,
            @Lazy SubmissionService submissionService) {
        this.caseRepository = caseRepository;
        this.voteRepository = voteRepository;
        this.userRepository = userRepository;
        this.transitionRepository = transitionRepository;
        this.synsetRepository = synsetRepository;
        this.jdbc = jdbc;
        this.idGeneratorService = idGeneratorService;
        this.auditService = auditService;
        this.submissionService = submissionService;
    }

    // ---------------------------------------------------------------- opening a case

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
        return startEscalation(principal, sub, comment);
    }

    /** Reviewer dispute: the reviewer's note is in, the contributor has 72 hours to respond. */
    @Transactional
    public Map<String, Object> startEscalation(UserPrincipal principal, Submission sub, String comment) {
        EntryState preState = sub.getState();
        ArbitrationStage stage = switch (principal.getRole()) {
            case LEXICAL_EDITOR -> ArbitrationStage.EDITOR;
            case CHIEF_LEXICAL_EDITOR -> ArbitrationStage.CHIEF;
            default -> stageOf(preState);
        };
        String caseId = createCase(sub, ArbitrationOrigin.REVIEWER_ESCALATION, stage, principal.getId(),
                sub.getContributorId(), principal.getId(), comment, null, comment, preState);
        submissionService.transition(sub, EntryState.ARBITRATION, principal.getId(), principal.getRole().name(), comment);
        auditService.record(principal, "CASE_OPENED", AuditTargetType.ARBITRATION, caseId,
                "Reviewer dispute for \"" + sub.getWrittenForm() + "\" — the contributor has 72 hours to respond");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("state", sub.getState().name());
        body.put("caseId", caseId);
        return body;
    }

    /** Emergency override into ARBITRATION: neither side has a note yet, both have 72 hours. */
    @Transactional
    public String openByOverride(UserPrincipal principal, Submission sub, String reason) {
        EntryState preState = sub.getState();
        String reviewerId = lastReviewerInto(sub, preState);
        String caseId = createCase(sub, ArbitrationOrigin.EMERGENCY_OVERRIDE, stageOf(preState), principal.getId(),
                sub.getContributorId(), reviewerId, reason, null, null, preState);
        submissionService.transition(sub, EntryState.ARBITRATION, principal.getId(), principal.getRole().name(),
                "EMERGENCY OVERRIDE: " + reason);
        auditService.record(principal, "CASE_OPENED", AuditTargetType.ARBITRATION, caseId,
                "Opened by emergency override for \"" + sub.getWrittenForm()
                        + "\" — the contributor and the reviewer have 72 hours to submit their notes");
        return caseId;
    }

    /** Emergency override out of ARBITRATION: every open case of the entry is closed so curators can no longer vote. */
    @Transactional
    public List<String> closeByOverride(UserPrincipal principal, Submission sub, EntryState target, String reason) {
        String who = principal.getRole() == Role.PLATFORM_ARCHITECT ? "Platform Architect" : "System Moderator";
        List<String> closed = new ArrayList<>();
        for (ArbitrationCase c : caseRepository.findBySubmissionId(sub.getId())) {
            if (c.getStatus() == ArbitrationStatus.RESOLVED) {
                continue;
            }
            c.setStatus(ArbitrationStatus.RESOLVED);
            c.setVerdictBy(VerdictBy.EMERGENCY_OVERRIDE);
            c.setResolvedAt(Instant.now());
            c.setResultingState(target);
            if (c.getWindowClosedAt() == null) {
                c.setWindowClosedAt(Instant.now());
            }
            c.setResolutionNote("Closed by emergency override: the " + who + " moved the entry to "
                    + target.name() + ". Reason: " + reason);
            caseRepository.save(c);
            auditService.record(principal, "CASE_CLOSED_BY_OVERRIDE", AuditTargetType.ARBITRATION, c.getId(),
                    "Entry moved to " + target.name() + " by emergency override");
            closed.add(c.getId());
        }
        return closed;
    }

    /** Contributor dispute of a FLAGGED / C-FLAGGED decision: the contributor's note is in, the reviewer has 72 hours. */
    @Transactional
    public Map<String, Object> openDispute(UserPrincipal principal, Submission sub, String comment) {
        if (!sub.getContributorId().equals(principal.getId())
                || !PermissionMatrix.has(principal.getRole(), Permission.DISPUTE)) {
            throw ApiException.forbidden("Only the contributor who submitted this entry can dispute it.");
        }
        String note = comment == null ? "" : comment.strip();
        String noteError = noteError(note, "Dispute note");
        if (noteError != null) {
            throw ApiException.unprocessable(Map.of("note", noteError));
        }
        if (sub.getState() != EntryState.FLAGGED && sub.getState() != EntryState.C_FLAGGED) {
            throw ApiException.conflict("This entry is now " + sub.getState().name()
                    + "; only FLAGGED or C_FLAGGED entries can be disputed.");
        }
        if (submissionService.decidedByArbitration(sub)) {
            throw ApiException.conflict("This decision was made by arbitration and cannot be disputed again. "
                    + "Correct the entry and resubmit it instead.");
        }
        EntryState flaggedState = sub.getState();
        String reviewerId = lastActorInto(sub, flaggedState);
        if (reviewerId == null) {
            reviewerId = sub.getLastUpdatedBy();
        }
        String caseId = createCase(sub, ArbitrationOrigin.CONTRIBUTOR_DISPUTE, stageOf(flaggedState), principal.getId(),
                principal.getId(), reviewerId, note, note, null, flaggedState);
        submissionService.transition(sub, EntryState.ARBITRATION, principal.getId(), principal.getRole().name(), note);
        auditService.record(principal, "CASE_OPENED", AuditTargetType.ARBITRATION, caseId,
                "Contributor dispute for \"" + sub.getWrittenForm() + "\" — the reviewer has 72 hours to respond");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("state", sub.getState().name());
        body.put("caseId", caseId);
        return body;
    }

    private String createCase(
            Submission sub,
            ArbitrationOrigin origin,
            ArbitrationStage stage,
            String openedBy,
            String contributorId,
            String reviewerId,
            String openingNote,
            String contributorNote,
            String reviewerNote,
            EntryState preState) {
        String caseId = "case-" + idGeneratorService.nextValue("case");
        Instant now = Instant.now();
        String excluded = userRepository.findByRoleAndActiveTrueAndSuspendedFalse(Role.ARBITRATION_CURATOR).stream()
                .map(AppUser::getId)
                .filter(id -> id.equals(openedBy) || id.equals(contributorId) || id.equals(reviewerId))
                .collect(Collectors.joining(","));
        ArbitrationCase c = ArbitrationCase.builder()
                .id(caseId)
                .submissionId(sub.getId())
                .claimantId(openedBy)
                .accusedId(origin == ArbitrationOrigin.CONTRIBUTOR_DISPUTE && reviewerId != null ? reviewerId : contributorId)
                .disputeType(origin == ArbitrationOrigin.CONTRIBUTOR_DISPUTE ? DisputeType.DISPUTE_FLAG : DisputeType.ESCALATION)
                .disputeNote(openingNote)
                .origin(origin)
                .stage(stage)
                .contributorId(contributorId)
                .reviewerId(reviewerId)
                .contributorNote(contributorNote)
                .contributorNoteAt(contributorNote != null ? now : null)
                .reviewerNote(reviewerNote)
                .reviewerNoteAt(reviewerNote != null ? now : null)
                .openedAt(now)
                .responseDeadline(now.plus(ArbitrationSchedule.RESPONSE_WINDOW))
                .status(ArbitrationStatus.RESPONSE_WINDOW)
                .excludedCuratorIds(excluded)
                .preArbitrationState(preState)
                .build();
        caseRepository.save(c);
        return caseId;
    }

    // ---------------------------------------------------------------- party and curator actions

    @Transactional
    public Map<String, Object> action(UserPrincipal principal, String id, Map<String, Object> p) {
        String action = str(p.get("action"));
        return switch (action) {
            case "note" -> note(principal, lockCase(id), p);
            case "vote" -> vote(principal, lockCase(id), p);
            case "resolve_tie" -> resolveTie(principal, lockCase(id), p);
            case "apply_correction" -> applyCorrection(principal, requireCase(id));
            default -> throw ApiException.unprocessable("Unknown action.");
        };
    }

    /** A party submits or edits its note; accepted until the deadline check closes the response window. */
    private Map<String, Object> note(UserPrincipal principal, ArbitrationCase c, Map<String, Object> p) {
        String side = sideOf(c, principal.getId());
        if (side == null) {
            throw ApiException.forbidden("Only the contributor and the reviewer of this case can submit a note.");
        }
        if (c.getStatus() != ArbitrationStatus.RESPONSE_WINDOW) {
            throw ApiException.conflict("The response window for this case has closed; notes are now read-only.");
        }
        String note = str(p.get("note")).strip();
        String error = noteError(note, "Your note");
        if (error != null) {
            throw ApiException.unprocessable(Map.of("note", error));
        }
        Instant now = Instant.now();
        boolean edited;
        if ("CONTRIBUTOR".equals(side)) {
            edited = c.getContributorNote() != null;
            if (note.equals(c.getContributorNote())) {
                throw ApiException.unprocessable(Map.of("note", "Your note is unchanged."));
            }
            c.setContributorNote(note);
            c.setContributorNoteAt(now);
        } else {
            edited = c.getReviewerNote() != null;
            if (note.equals(c.getReviewerNote())) {
                throw ApiException.unprocessable(Map.of("note", "Your note is unchanged."));
            }
            c.setReviewerNote(note);
            c.setReviewerNoteAt(now);
        }
        caseRepository.save(c);
        auditService.record(principal, edited ? "ARBITRATION_NOTE_UPDATED" : "ARBITRATION_NOTE_SUBMITTED",
                AuditTargetType.ARBITRATION, c.getId(),
                ("CONTRIBUTOR".equals(side) ? "Contributor" : "Reviewer") + " note " + (edited ? "updated" : "submitted"));
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("ok", true);
        res.put("side", side);
        res.put("edited", edited);
        res.put("noteAt", now.toString());
        return res;
    }

    private Map<String, Object> vote(UserPrincipal principal, ArbitrationCase c, Map<String, Object> p) {
        if (!PermissionMatrix.has(principal.getRole(), Permission.ARBITRATION_VOTE)) {
            throw ApiException.forbidden("Only Arbitration Curators can vote.");
        }
        if (c.getStatus() != ArbitrationStatus.ACTIVE) {
            throw ApiException.conflict(c.getStatus() == ArbitrationStatus.RESPONSE_WINDOW
                    ? "Voting has not started yet; it opens after the response window closes."
                    : "Voting on this case has closed.");
        }
        if (parseExcluded(c.getExcludedCuratorIds()).contains(principal.getId())) {
            throw ApiException.forbidden("Conflict of interest: you are a party to this case.");
        }
        if (voteRepository.existsByCaseIdAndCuratorId(c.getId(), principal.getId())) {
            throw ApiException.conflict("You have already voted on this case.");
        }
        Map<String, String> errors = new LinkedHashMap<>();
        ArbitrationVerdict verdict = null;
        String rawVerdict = str(p.get("verdict"));
        if ("CONTRIBUTOR".equals(rawVerdict) || "REVIEWER".equals(rawVerdict)) {
            verdict = ArbitrationVerdict.valueOf(rawVerdict);
        } else {
            errors.put("verdict", "Choose which side you support.");
        }
        String rationale = str(p.get("rationale")).strip();
        if (rationale.isEmpty()) {
            errors.put("rationale", "A rationale is required.");
        } else if (rationale.length() < RATIONALE_MIN) {
            errors.put("rationale", "Rationale must be at least " + RATIONALE_MIN + " characters.");
        } else if (rationale.length() > RATIONALE_MAX) {
            errors.put("rationale", "Rationale must be at most " + RATIONALE_MAX + " characters.");
        }
        if (!errors.isEmpty()) {
            throw ApiException.unprocessable(errors);
        }
        ArbitrationVote vote = voteRepository.save(ArbitrationVote.builder()
                .caseId(c.getId())
                .curatorId(principal.getId())
                .verdict(verdict)
                .rationale(rationale)
                .votedAt(Instant.now())
                .build());
        auditService.record(principal, "ARBITRATION_VOTE", AuditTargetType.ARBITRATION, c.getId(),
                "Vote recorded (individual votes stay hidden until voting closes)");
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("ok", true);
        res.put("votedAt", vote.getVotedAt().toString());
        res.put("votesCast", voteRepository.findByCaseId(c.getId()).size());
        return res;
    }

    /**
     * Tied case: the System Moderator submits the corrected entry (full Quick Input payload); the entry is
     * validated directly and the case is closed as a tie-break.
     */
    private Map<String, Object> resolveTie(UserPrincipal principal, ArbitrationCase c, Map<String, Object> p) {
        if (!PermissionMatrix.has(principal.getRole(), Permission.TIE_BREAKER)) {
            throw ApiException.forbidden("Only the System Moderator can resolve a tied arbitration case.");
        }
        if (c.getStatus() != ArbitrationStatus.TIE_PENDING) {
            throw ApiException.conflict(c.getStatus() == ArbitrationStatus.RESOLVED
                    ? "This case has already been resolved."
                    : "Only tied cases can be resolved by the System Moderator.");
        }
        Submission sub = submissionService.require(c.getSubmissionId());
        List<String> changed = submissionService.correctAndValidateTiedEntry(principal, sub, p, c.getId());
        Instant now = Instant.now();
        c.setStatus(ArbitrationStatus.RESOLVED);
        c.setVerdictBy(VerdictBy.TIE_BREAK);
        c.setTieBrokenBy(principal.getId());
        c.setResolvedAt(now);
        c.setResultingState(EntryState.VALIDATED);
        c.setResolutionNote("The tie was resolved by the System Moderator, who "
                + (changed.isEmpty() ? "validated the entry without changes."
                        : "corrected the entry (" + String.join(", ", changed) + ") and validated it."));
        caseRepository.save(c);
        auditService.record(principal, "TIE_RESOLVED", AuditTargetType.ARBITRATION, c.getId(),
                "Tied case resolved — \"" + sub.getWrittenForm() + "\" validated"
                        + (changed.isEmpty() ? "" : " (changed: " + String.join(", ", changed) + ")"));
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("ok", true);
        res.put("caseId", c.getId());
        res.put("submissionId", sub.getId());
        res.put("state", sub.getState().name());
        res.put("changed", changed);
        return res;
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

    // ---------------------------------------------------------------- daily deadline check

    /**
     * Closes an expired response window: both notes in starts a 7-day voting round, a single note upholds that
     * side, no note returns the entry to its pre-arbitration state. Returns what happened, or null if not due.
     */
    @Transactional
    public String closeResponseWindow(String caseId, Instant runAt) {
        ArbitrationCase c = caseRepository.findForUpdate(caseId).orElse(null);
        if (c == null || c.getStatus() != ArbitrationStatus.RESPONSE_WINDOW || c.getResponseDeadline().isAfter(runAt)) {
            return null;
        }
        c.setWindowClosedAt(runAt);
        boolean contributorIn = c.getContributorNote() != null;
        boolean reviewerIn = c.getReviewerNote() != null;
        if (contributorIn && reviewerIn) {
            c.setStatus(ArbitrationStatus.ACTIVE);
            c.setVotingOpenedAt(runAt);
            c.setVotingDeadline(runAt.plus(ArbitrationSchedule.VOTING_ROUND));
            caseRepository.save(c);
            auditService.recordSystem("VOTING_OPENED", AuditTargetType.ARBITRATION, c.getId(),
                    "Both notes received — curator voting open until " + c.getVotingDeadline());
            return "VOTING_OPENED";
        }
        String reviewer = reviewerLabel(c.getStage());
        if (contributorIn) {
            decide(c, ArbitrationVerdict.CONTRIBUTOR, VerdictBy.NO_RESPONSE,
                    "The " + reviewer + " did not submit a note within the 72-hour response window, so the "
                            + "contributor's position stands.");
            return "UPHELD_CONTRIBUTOR";
        }
        if (reviewerIn) {
            decide(c, ArbitrationVerdict.REVIEWER, VerdictBy.NO_RESPONSE,
                    "The contributor did not submit a note within the 72-hour response window, so the "
                            + reviewer + "'s decision stands.");
            return "UPHELD_REVIEWER";
        }
        decide(c, null, VerdictBy.LAPSED,
                "Neither the contributor nor the " + reviewer + " submitted a note within the 72-hour response "
                        + "window. The case lapsed and the entry returned to its previous status.");
        return "LAPSED";
    }

    /**
     * Closes an expired voting round: with two thirds of the eligible curators voted the majority side is upheld
     * (a draw is recorded as a tie); otherwise voting is extended by another 7 days.
     */
    @Transactional
    public String closeVotingRound(String caseId, Instant runAt) {
        ArbitrationCase c = caseRepository.findForUpdate(caseId).orElse(null);
        if (c == null || c.getStatus() != ArbitrationStatus.ACTIVE || c.getVotingDeadline() == null
                || c.getVotingDeadline().isAfter(runAt)) {
            return null;
        }
        Tally t = tally(c);
        if (t.cast() < t.quorum()) {
            c.setVotingExtensions(c.getVotingExtensions() + 1);
            c.setVotingDeadline(runAt.plus(ArbitrationSchedule.VOTING_ROUND));
            caseRepository.save(c);
            auditService.recordSystem("VOTING_EXTENDED", AuditTargetType.ARBITRATION, c.getId(),
                    t.cast() + " of " + t.eligible() + " curators voted (" + t.quorum()
                            + " needed) — voting extended until " + c.getVotingDeadline());
            return "EXTENDED";
        }
        String score = t.contributor() + "–" + t.reviewer();
        if (t.contributor() == t.reviewer()) {
            c.setStatus(ArbitrationStatus.TIE_PENDING);
            c.setResolutionNote("The curator panel is tied " + score + ".");
            caseRepository.save(c);
            auditService.recordSystem("TIE_DETECTED", AuditTargetType.ARBITRATION, c.getId(),
                    "Voting closed with a tie (" + score + ")");
            return "TIE";
        }
        boolean contributorWins = t.contributor() > t.reviewer();
        decide(c, contributorWins ? ArbitrationVerdict.CONTRIBUTOR : ArbitrationVerdict.REVIEWER, VerdictBy.PANEL,
                "The curator panel upheld the " + (contributorWins ? "contributor's position" : reviewerLabel(c.getStage())
                        + "'s decision") + " (" + score + ", " + t.cast() + " of " + t.eligible() + " curators voted).");
        return contributorWins ? "UPHELD_CONTRIBUTOR" : "UPHELD_REVIEWER";
    }

    /** Resolves the case and moves the entry; {@code winner} null returns it to its pre-arbitration state. */
    private void decide(ArbitrationCase c, ArbitrationVerdict winner, VerdictBy by, String note) {
        Instant now = Instant.now();
        Submission sub = submissionService.require(c.getSubmissionId());
        EntryState target = winner == null ? preArbitrationState(c) : outcomeState(c.getStage(), winner);
        if (sub.getState() == EntryState.ARBITRATION) {
            if (target == EntryState.VALIDATED) {
                sub.setValidatedBy(SYSTEM_ACTOR);
                sub.setValidatedAt(now);
            }
            if (winner == ArbitrationVerdict.REVIEWER && c.getReviewerNote() != null) {
                if (c.getStage() == ArbitrationStage.CHIEF) {
                    sub.setCleComment(c.getReviewerNote());
                } else {
                    sub.setEditorComment(c.getReviewerNote());
                }
            }
            submissionService.transition(sub, target, SYSTEM_ACTOR, SYSTEM_ROLE,
                    "Arbitration " + c.getId() + ": " + note
                            + (winner != null ? " No further appeals are possible on this decision." : ""));
        } else {
            target = sub.getState();
        }
        c.setStatus(ArbitrationStatus.RESOLVED);
        c.setFinalVerdict(winner);
        c.setVerdictBy(by);
        c.setResolvedAt(now);
        c.setResultingState(target);
        c.setResolutionNote(note);
        caseRepository.save(c);
        auditService.recordSystem("CASE_RESOLVED", AuditTargetType.ARBITRATION, c.getId(),
                by.name() + " — entry moved to " + target.name());
    }

    /** Contributor upheld: the entry passes the disputed stage. Reviewer upheld: the entry is flagged at that stage. */
    static EntryState outcomeState(ArbitrationStage stage, ArbitrationVerdict winner) {
        boolean chief = stage == ArbitrationStage.CHIEF;
        return winner == ArbitrationVerdict.CONTRIBUTOR
                ? (chief ? EntryState.VALIDATED : EntryState.EDITOR_APPROVED)
                : (chief ? EntryState.C_FLAGGED : EntryState.FLAGGED);
    }

    // ---------------------------------------------------------------- list

    public record Criteria(String q, String phase, Integer page, Integer size) {}

    private static final List<String> PHASES = List.of("OPEN", "RESPONSE_WINDOW", "ACTIVE", "TIE_PENDING", "RESOLVED", "ALL");

    /**
     * Paged arbitration cases. Curators and managers see every case; contributors and reviewers only the cases
     * they are a party to.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> browse(UserPrincipal principal, Criteria cr) {
        Role role = principal.getRole();
        String me = principal.getId();
        String phase = cr.phase() == null || cr.phase().isBlank() ? "OPEN" : cr.phase().trim().toUpperCase(Locale.ROOT);
        if (!PHASES.contains(phase)) {
            throw ApiException.badRequest(Map.of("phase", "Unknown filter: " + cr.phase()));
        }
        int size = cr.size() == null ? DEFAULT_SIZE : Math.max(1, Math.min(MAX_SIZE, cr.size()));
        int page = cr.page() == null ? 0 : Math.max(0, cr.page());
        String q = normalizeQuery(cr.q());

        MapSqlParameterSource params = new MapSqlParameterSource().addValue("me", me);
        StringBuilder where = new StringBuilder(" where 1=1");
        String scope = scopeOf(role);
        switch (scope) {
            case "ALL" -> { }
            case "CONTRIBUTOR" -> where.append(" and c.contributor_id = :me");
            case "REVIEWER" -> where.append(" and (c.reviewer_id = :me or c.claimant_id = :me)");
            default -> throw ApiException.forbidden("Your role has no access to arbitration cases.");
        }
        if (q != null) {
            where.append(" and (lower(s.written_form) like :like escape '!'")
                    .append(" or lower(coalesce(s.definition_text, '')) like :like escape '!'")
                    .append(" or lower(coalesce(sy.definition_text, '')) like :like escape '!'")
                    .append(" or lower(c.id) = :exact or lower(s.id) = :exact")
                    .append(" or lower(cu.name) like :like escape '!'")
                    .append(" or lower(coalesce(ru.name, '')) like :like escape '!')");
            String lower = q.toLowerCase(Locale.ROOT);
            params.addValue("like", "%" + escapeLike(lower) + "%");
            params.addValue("exact", lower);
        }
        String from = """
                 from arbitration_case c
                 join submission s on s.id = c.submission_id
                 left join synset sy on sy.id = s.synset_id
                 left join app_user cu on cu.id = c.contributor_id
                 left join app_user ru on ru.id = c.reviewer_id
                """;

        Map<String, Object> agg = jdbc.queryForMap("""
                select count(*) filter (where c.status <> 'RESOLVED') as open,
                       count(*) filter (where c.status = 'RESPONSE_WINDOW') as response_window,
                       count(*) filter (where c.status = 'ACTIVE') as active,
                       count(*) filter (where c.status = 'TIE_PENDING') as tie_pending,
                       count(*) filter (where c.status = 'RESOLVED') as resolved,
                       count(*) as total
                """ + from + where, params);
        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("OPEN", num(agg.get("open")));
        counts.put("RESPONSE_WINDOW", num(agg.get("response_window")));
        counts.put("ACTIVE", num(agg.get("active")));
        counts.put("TIE_PENDING", num(agg.get("tie_pending")));
        counts.put("RESOLVED", num(agg.get("resolved")));
        counts.put("ALL", num(agg.get("total")));

        switch (phase) {
            case "OPEN" -> where.append(" and c.status <> 'RESOLVED'");
            case "ALL" -> { }
            default -> {
                where.append(" and c.status = :status");
                params.addValue("status", phase);
            }
        }
        String order = switch (phase) {
            case "RESOLVED" -> " order by c.resolved_at desc nulls last, c.opened_at desc";
            case "ALL", "TIE_PENDING" -> " order by c.opened_at desc";
            default -> " order by case c.status when 'ACTIVE' then c.voting_deadline"
                    + " when 'RESPONSE_WINDOW' then c.response_deadline end asc nulls last, c.opened_at desc";
        };
        long total = (Long) counts.get(phase);
        List<Map<String, Object>> items = List.of();
        if (total > (long) page * size) {
            params.addValue("limit", size);
            params.addValue("offset", (long) page * size);
            items = jdbc.query("""
                    select c.id, c.submission_id, c.status, c.origin, c.stage, c.opened_at, c.response_deadline,
                           c.window_closed_at, c.voting_opened_at, c.voting_deadline, c.voting_extensions,
                           c.resolved_at, c.final_verdict, c.verdict_by, c.resulting_state,
                           c.contributor_id, cu.name as contributor_name, c.contributor_note_at,
                           c.reviewer_id, ru.name as reviewer_name, ru.role as reviewer_role, c.reviewer_note_at,
                           s.state as entry_state, s.written_form, s.part_of_speech,
                           coalesce(s.definition_text, sy.definition_text) as definition,
                           (select count(*) from arbitration_vote v where v.case_id = c.id) as votes_cast,
                           exists (select 1 from arbitration_vote v where v.case_id = c.id and v.curator_id = :me)
                               as voted_by_me
                    """ + from + where + order + " limit :limit offset :offset",
                    params,
                    (rs, i) -> listRow(rs, me));
        }

        int eligible = eligibleCurators(List.of()).size();
        Map<String, Object> panel = new LinkedHashMap<>();
        panel.put("eligible", eligible);
        panel.put("quorum", quorum(eligible));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", items);
        body.put("page", page);
        body.put("size", size);
        body.put("totalElements", total);
        body.put("totalPages", (int) ((total + size - 1) / size));
        body.put("phase", phase);
        body.put("q", q);
        body.put("counts", counts);
        body.put("scope", scope);
        body.put("panel", panel);
        body.put("schedule", scheduleInfo());
        return body;
    }

    private Map<String, Object> listRow(ResultSet rs, String me) throws SQLException {
        Map<String, Object> m = new LinkedHashMap<>();
        String status = rs.getString("status");
        String contributorId = rs.getString("contributor_id");
        String reviewerId = rs.getString("reviewer_id");
        m.put("id", rs.getString("id"));
        m.put("submissionId", rs.getString("submission_id"));
        m.put("status", status);
        m.put("origin", rs.getString("origin"));
        m.put("stage", rs.getString("stage"));
        m.put("writtenForm", rs.getString("written_form"));
        m.put("pos", rs.getString("part_of_speech"));
        m.put("definition", rs.getString("definition"));
        m.put("entryState", rs.getString("entry_state"));
        m.put("contributor", party(contributorId, rs.getString("contributor_name"), "CONTRIBUTOR"));
        m.put("reviewer", reviewerId != null ? party(reviewerId, rs.getString("reviewer_name"), rs.getString("reviewer_role")) : null);
        m.put("contributorNoteAt", instant(rs.getTimestamp("contributor_note_at")));
        m.put("reviewerNoteAt", instant(rs.getTimestamp("reviewer_note_at")));
        Timestamp deadline = rs.getTimestamp("response_deadline");
        Timestamp votingDeadline = rs.getTimestamp("voting_deadline");
        m.put("openedAt", instant(rs.getTimestamp("opened_at")));
        m.put("responseDeadline", instant(deadline));
        m.put("windowClosesAt", deadline != null ? ArbitrationSchedule.checkAtOrAfter(deadline.toInstant()).toString() : null);
        m.put("windowClosedAt", instant(rs.getTimestamp("window_closed_at")));
        m.put("votingOpenedAt", instant(rs.getTimestamp("voting_opened_at")));
        m.put("votingDeadline", instant(votingDeadline));
        m.put("votingClosesAt", votingDeadline != null
                ? ArbitrationSchedule.checkAtOrAfter(votingDeadline.toInstant()).toString() : null);
        m.put("votingRound", rs.getInt("voting_extensions") + 1);
        m.put("votesCast", rs.getLong("votes_cast"));
        m.put("votedByMe", rs.getBoolean("voted_by_me"));
        m.put("resolvedAt", instant(rs.getTimestamp("resolved_at")));
        m.put("finalVerdict", rs.getString("final_verdict"));
        m.put("verdictBy", rs.getString("verdict_by"));
        m.put("resultingState", rs.getString("resulting_state"));
        String side = me.equals(contributorId) ? "CONTRIBUTOR" : me.equals(reviewerId) ? "REVIEWER" : null;
        m.put("viewerSide", side);
        boolean myNoteIn = "CONTRIBUTOR".equals(side) ? m.get("contributorNoteAt") != null
                : "REVIEWER".equals(side) && m.get("reviewerNoteAt") != null;
        m.put("awaitingMyNote", side != null && "RESPONSE_WINDOW".equals(status) && !myNoteIn);
        return m;
    }

    // ---------------------------------------------------------------- detail

    @Transactional(readOnly = true)
    public Map<String, Object> detail(UserPrincipal principal, String id) {
        ArbitrationCase c = requireCase(id);
        Role role = principal.getRole();
        String me = principal.getId();
        String side = sideOf(c, me);
        boolean curator = role == Role.ARBITRATION_CURATOR;
        boolean manager = PermissionMatrix.has(role, Permission.OVERRIDE_STATE);
        boolean opener = me.equals(c.getClaimantId());
        if (!curator && !manager && side == null && !opener) {
            throw ApiException.forbidden("This arbitration case is not available to you.");
        }
        Submission sub = submissionService.require(c.getSubmissionId());
        Map<String, AppUser> users = new HashMap<>();
        List<ArbitrationVote> votes = voteRepository.findByCaseId(c.getId());
        List<String> userIds = new ArrayList<>(List.of(c.getContributorId(), c.getClaimantId()));
        if (c.getReviewerId() != null) {
            userIds.add(c.getReviewerId());
        }
        if (c.getTieBrokenBy() != null) {
            userIds.add(c.getTieBrokenBy());
        }
        votes.forEach(v -> userIds.add(v.getCuratorId()));
        userRepository.findAllById(userIds).forEach(u -> users.put(u.getId(), u));

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.getId());
        m.put("status", c.getStatus().name());
        m.put("origin", c.getOrigin().name());
        m.put("stage", c.getStage().name());
        m.put("preArbitrationState", c.getPreArbitrationState() != null ? c.getPreArbitrationState().name() : null);
        m.put("openedAt", c.getOpenedAt().toString());
        m.put("openedBy", userRef(users.get(c.getClaimantId()), c.getClaimantId()));
        m.put("openingNote", c.getOrigin() == ArbitrationOrigin.EMERGENCY_OVERRIDE ? c.getDisputeNote() : null);

        Map<String, Object> s = new LinkedHashMap<>();
        s.put("id", sub.getId());
        s.put("writtenForm", sub.getWrittenForm());
        s.put("pos", sub.getPartOfSpeech() != null ? sub.getPartOfSpeech().getCode() : null);
        s.put("state", sub.getState().name());
        s.put("synsetAction", sub.getSynsetAction() != null ? sub.getSynsetAction().getCode() : null);
        s.put("synsetId", sub.getSynsetId());
        String definition = sub.getDefinitionText();
        String ili = sub.getIliCode();
        if (sub.getSynsetId() != null) {
            SynsetEntity sy = synsetRepository.findById(sub.getSynsetId()).orElse(null);
            if (sy != null) {
                definition = definition != null ? definition : sy.getDefinitionText();
                ili = sy.getIli();
            }
        }
        s.put("definition", definition);
        s.put("ili", ili);
        s.put("createdAt", sub.getCreatedAt() != null ? sub.getCreatedAt().toString() : null);
        m.put("submission", s);

        Map<String, Object> contributor = new LinkedHashMap<>(userRef(users.get(c.getContributorId()), c.getContributorId()));
        contributor.put("note", c.getContributorNote());
        contributor.put("noteAt", c.getContributorNoteAt() != null ? c.getContributorNoteAt().toString() : null);
        m.put("contributor", contributor);
        Map<String, Object> reviewer = null;
        if (c.getReviewerId() != null) {
            reviewer = new LinkedHashMap<>(userRef(users.get(c.getReviewerId()), c.getReviewerId()));
            reviewer.put("note", c.getReviewerNote());
            reviewer.put("noteAt", c.getReviewerNoteAt() != null ? c.getReviewerNoteAt().toString() : null);
        }
        m.put("reviewer", reviewer);

        Map<String, Object> window = new LinkedHashMap<>();
        window.put("deadline", c.getResponseDeadline().toString());
        window.put("closesAt", ArbitrationSchedule.checkAtOrAfter(c.getResponseDeadline()).toString());
        window.put("closedAt", c.getWindowClosedAt() != null ? c.getWindowClosedAt().toString() : null);
        m.put("responseWindow", window);

        boolean votingClosed = c.getStatus() == ArbitrationStatus.TIE_PENDING
                || (c.getStatus() == ArbitrationStatus.RESOLVED
                        && (c.getVerdictBy() == VerdictBy.PANEL || c.getVerdictBy() == VerdictBy.TIE_BREAK));
        Map<String, Object> voting = null;
        if (c.getVotingOpenedAt() != null) {
            Tally t = tally(c, votes);
            Map<String, Object> v0 = new LinkedHashMap<>();
            voting = v0;
            voting.put("openedAt", c.getVotingOpenedAt().toString());
            voting.put("deadline", c.getVotingDeadline() != null ? c.getVotingDeadline().toString() : null);
            voting.put("closesAt", c.getVotingDeadline() != null
                    ? ArbitrationSchedule.checkAtOrAfter(c.getVotingDeadline()).toString() : null);
            voting.put("round", c.getVotingExtensions() + 1);
            voting.put("extensions", c.getVotingExtensions());
            voting.put("eligible", t.eligible());
            voting.put("quorum", t.quorum());
            voting.put("cast", t.cast());
            voting.put("closed", votingClosed || c.getStatus() == ArbitrationStatus.RESOLVED);
            if (votingClosed) {
                voting.put("contributorVotes", t.contributor());
                voting.put("reviewerVotes", t.reviewer());
                if (curator || manager) {
                    voting.put("votes", votes.stream()
                            .sorted(Comparator.comparing(ArbitrationVote::getVotedAt))
                            .map(v -> {
                                Map<String, Object> vm = new LinkedHashMap<>();
                                vm.put("curator", userRef(users.get(v.getCuratorId()), v.getCuratorId()));
                                vm.put("verdict", v.getVerdict().name());
                                vm.put("rationale", v.getRationale());
                                vm.put("votedAt", v.getVotedAt().toString());
                                return vm;
                            })
                            .toList());
                }
            }
            if (curator) {
                votes.stream().filter(v -> v.getCuratorId().equals(me)).findFirst().ifPresent(v -> {
                    Map<String, Object> mine = new LinkedHashMap<>();
                    mine.put("verdict", v.getVerdict().name());
                    mine.put("rationale", v.getRationale());
                    mine.put("votedAt", v.getVotedAt().toString());
                    v0.put("myVote", mine);
                });
            }
        }
        m.put("voting", voting);

        Map<String, Object> outcome = null;
        if (c.getStatus() == ArbitrationStatus.RESOLVED || c.getStatus() == ArbitrationStatus.TIE_PENDING) {
            outcome = new LinkedHashMap<>();
            outcome.put("verdict", c.getFinalVerdict() != null ? c.getFinalVerdict().name() : null);
            outcome.put("verdictBy", c.getVerdictBy() != null ? c.getVerdictBy().name() : null);
            outcome.put("resolvedAt", c.getResolvedAt() != null ? c.getResolvedAt().toString() : null);
            outcome.put("resultingState", c.getResultingState() != null ? c.getResultingState().name() : null);
            outcome.put("note", c.getResolutionNote());
            outcome.put("resolvedBy", c.getTieBrokenBy() != null
                    ? userRef(users.get(c.getTieBrokenBy()), c.getTieBrokenBy()) : null);
        }
        m.put("outcome", outcome);

        boolean excluded = parseExcluded(c.getExcludedCuratorIds()).contains(me);
        boolean hasVoted = votes.stream().anyMatch(v -> v.getCuratorId().equals(me));
        String voteBlocked = null;
        if (curator) {
            if (c.getStatus() == ArbitrationStatus.RESPONSE_WINDOW) {
                voteBlocked = "Voting opens after the response window closes and both notes are in.";
            } else if (c.getStatus() != ArbitrationStatus.ACTIVE) {
                voteBlocked = "Voting on this case has closed.";
            } else if (excluded) {
                voteBlocked = "Conflict of interest: you are a party to this case.";
            } else if (hasVoted) {
                voteBlocked = "You have already voted on this case.";
            }
        }
        Map<String, Object> viewer = new LinkedHashMap<>();
        viewer.put("side", side);
        viewer.put("isCurator", curator);
        viewer.put("isManager", manager);
        viewer.put("canEditNote", side != null && c.getStatus() == ArbitrationStatus.RESPONSE_WINDOW);
        viewer.put("canVote", curator && voteBlocked == null);
        viewer.put("voteBlockedReason", voteBlocked);
        viewer.put("canResolveTie", PermissionMatrix.has(role, Permission.TIE_BREAKER)
                && c.getStatus() == ArbitrationStatus.TIE_PENDING
                && sub.getState() == EntryState.ARBITRATION);
        m.put("viewer", viewer);

        Map<String, Object> rules = new LinkedHashMap<>();
        rules.put("noteMin", NOTE_MIN);
        rules.put("noteMax", NOTE_MAX);
        rules.put("rationaleMin", RATIONALE_MIN);
        rules.put("rationaleMax", RATIONALE_MAX);
        m.put("rules", rules);
        m.put("schedule", scheduleInfo());
        return m;
    }

    /** Legacy flat case list (dashboard summaries). */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> list() {
        return caseRepository.findAllByOrderByOpenedAtDesc().stream().map(this::toDto).toList();
    }

    public Map<String, Object> toDto(ArbitrationCase c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.getId());
        m.put("submissionId", c.getSubmissionId());
        m.put("origin", c.getOrigin() != null ? c.getOrigin().name() : null);
        m.put("stage", c.getStage() != null ? c.getStage().name() : null);
        m.put("contributorId", c.getContributorId());
        m.put("reviewerId", c.getReviewerId());
        m.put("disputeType", c.getDisputeType().name());
        m.put("openedAt", c.getOpenedAt() != null ? c.getOpenedAt().toString() : null);
        m.put("responseDeadline", c.getResponseDeadline() != null ? c.getResponseDeadline().toString() : null);
        m.put("votingDeadline", c.getVotingDeadline() != null ? c.getVotingDeadline().toString() : null);
        m.put("status", c.getStatus().name());
        m.put("finalVerdict", c.getFinalVerdict() != null ? c.getFinalVerdict().name() : null);
        m.put("verdictBy", c.getVerdictBy() != null ? c.getVerdictBy().name() : null);
        m.put("resolvedAt", c.getResolvedAt() != null ? c.getResolvedAt().toString() : null);
        m.put("resultingState", c.getResultingState() != null ? c.getResultingState().name() : null);
        return m;
    }

    // ---------------------------------------------------------------- helpers

    private record Tally(int eligible, int quorum, int cast, long contributor, long reviewer) {}

    private Tally tally(ArbitrationCase c) {
        return tally(c, voteRepository.findByCaseId(c.getId()));
    }

    private Tally tally(ArbitrationCase c, List<ArbitrationVote> votes) {
        int eligible = eligibleCurators(parseExcluded(c.getExcludedCuratorIds())).size();
        long contributor = votes.stream().filter(v -> v.getVerdict() == ArbitrationVerdict.CONTRIBUTOR).count();
        long reviewer = votes.stream().filter(v -> v.getVerdict() == ArbitrationVerdict.REVIEWER).count();
        return new Tally(eligible, quorum(eligible), votes.size(), contributor, reviewer);
    }

    private List<AppUser> eligibleCurators(List<String> excluded) {
        return userRepository.findByRoleAndActiveTrueAndSuspendedFalse(Role.ARBITRATION_CURATOR).stream()
                .filter(u -> !excluded.contains(u.getId()))
                .toList();
    }

    /** Two thirds of the eligible panel, never below one vote. */
    public static int quorum(int eligible) {
        return Math.max(1, (int) Math.ceil(eligible * 2.0 / 3.0));
    }

    private static Map<String, Object> scheduleInfo() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("checkTime", ArbitrationSchedule.CHECK_TIME.toString());
        m.put("timeZone", ArbitrationSchedule.ZONE_ID);
        m.put("nextCheckAt", ArbitrationSchedule.checkAtOrAfter(Instant.now()).toString());
        m.put("responseWindowHours", ArbitrationSchedule.RESPONSE_WINDOW.toHours());
        m.put("votingRoundDays", ArbitrationSchedule.VOTING_ROUND.toDays());
        return m;
    }

    private static String scopeOf(Role role) {
        return switch (role) {
            case ARBITRATION_CURATOR, PLATFORM_ARCHITECT, SYSTEM_MODERATOR -> "ALL";
            case CONTRIBUTOR -> "CONTRIBUTOR";
            case LEXICAL_EDITOR, CHIEF_LEXICAL_EDITOR -> "REVIEWER";
            default -> "NONE";
        };
    }

    private static String sideOf(ArbitrationCase c, String userId) {
        if (userId.equals(c.getContributorId())) {
            return "CONTRIBUTOR";
        }
        if (userId.equals(c.getReviewerId())) {
            return "REVIEWER";
        }
        return null;
    }

    static ArbitrationStage stageOf(EntryState preState) {
        return preState == EntryState.EDITOR_APPROVED || preState == EntryState.C_FLAGGED
                ? ArbitrationStage.CHIEF
                : ArbitrationStage.EDITOR;
    }

    private static String reviewerLabel(ArbitrationStage stage) {
        return stage == ArbitrationStage.CHIEF ? "Chief Lexical Editor" : "Lexical Editor";
    }

    private static String noteError(String note, String label) {
        if (note.isEmpty()) {
            return label + " is required.";
        }
        if (note.length() < NOTE_MIN) {
            return label + " must be at least " + NOTE_MIN + " characters.";
        }
        if (note.length() > NOTE_MAX) {
            return label + " must be at most " + NOTE_MAX + " characters.";
        }
        return null;
    }

    private EntryState preArbitrationState(ArbitrationCase c) {
        if (c.getPreArbitrationState() != null) {
            return c.getPreArbitrationState();
        }
        return transitionRepository.findBySubmissionIdOrderByCreatedAtAsc(c.getSubmissionId()).stream()
                .sorted((a, b) -> b.getCreatedAt().compareTo(a.getCreatedAt()))
                .filter(h -> h.getToState() == EntryState.ARBITRATION && h.getFromState() != null)
                .map(StateTransition::getFromState)
                .findFirst()
                .orElse(c.getStage() == ArbitrationStage.CHIEF ? EntryState.C_FLAGGED : EntryState.FLAGGED);
    }

    private String lastActorInto(Submission sub, EntryState state) {
        return transitionRepository.findBySubmissionIdOrderByCreatedAtAsc(sub.getId()).stream()
                .sorted((a, b) -> b.getCreatedAt().compareTo(a.getCreatedAt()))
                .filter(h -> h.getToState() == state)
                .map(StateTransition::getActorId)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
    }

    /** The Lexical Editor / Chief Lexical Editor whose decision put the entry into {@code state}. */
    private String lastReviewerInto(Submission sub, EntryState state) {
        String fromHistory = transitionRepository.findBySubmissionIdOrderByCreatedAtAsc(sub.getId()).stream()
                .sorted((a, b) -> b.getCreatedAt().compareTo(a.getCreatedAt()))
                .filter(h -> h.getToState() == state)
                .filter(h -> Role.LEXICAL_EDITOR.name().equals(h.getActorRole())
                        || Role.CHIEF_LEXICAL_EDITOR.name().equals(h.getActorRole()))
                .map(StateTransition::getActorId)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
        if (fromHistory != null) {
            return fromHistory;
        }
        return stageOf(state) == ArbitrationStage.CHIEF ? sub.getAssignedChief() : sub.getAssignedTo();
    }

    private ArbitrationCase requireCase(String id) {
        return caseRepository.findById(id).orElseThrow(() -> ApiException.notFound("Arbitration case not found."));
    }

    private ArbitrationCase lockCase(String id) {
        return caseRepository.findForUpdate(id).orElseThrow(() -> ApiException.notFound("Arbitration case not found."));
    }

    private static Map<String, Object> userRef(AppUser u, String id) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("name", u != null ? u.getName() : null);
        m.put("role", u != null ? u.getRole().name() : null);
        return m;
    }

    private static Map<String, Object> party(String id, String name, String role) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("name", name);
        m.put("role", role);
        return m;
    }

    private static List<String> parseExcluded(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return Arrays.stream(raw.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    private static long num(Object o) {
        return o == null ? 0L : ((Number) o).longValue();
    }

    private static String instant(Timestamp ts) {
        return ts != null ? ts.toInstant().toString() : null;
    }

    private static String normalizeQuery(String raw) {
        if (raw == null) {
            return null;
        }
        String q = raw.strip().replaceAll("\\s+", " ");
        if (q.isEmpty()) {
            return null;
        }
        return q.length() > MAX_QUERY_LENGTH ? q.substring(0, MAX_QUERY_LENGTH) : q;
    }

    private static String escapeLike(String s) {
        return s.replace("!", "!!").replace("%", "!%").replace("_", "!_");
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }
}
