package com.bengalilkc.service.workflow;

import com.bengalilkc.common.SenseRelationPayload;
import com.bengalilkc.common.SynsetRelationPayload;
import com.bengalilkc.common.exception.ApiException;
import com.bengalilkc.domain.entity.identity.AppUser;
import com.bengalilkc.domain.entity.workflow.ArbitrationCase;
import com.bengalilkc.domain.entity.workflow.StateTransition;
import com.bengalilkc.domain.entity.workflow.Submission;
import com.bengalilkc.domain.enums.ArbitrationStatus;
import com.bengalilkc.domain.enums.AuditTargetType;
import com.bengalilkc.domain.enums.DefinitionLanguage;
import com.bengalilkc.domain.enums.EntryState;
import com.bengalilkc.domain.enums.PartOfSpeech;
import com.bengalilkc.domain.enums.Permission;
import com.bengalilkc.domain.enums.Role;
import com.bengalilkc.domain.enums.SynsetAction;
import com.bengalilkc.repository.identity.AppUserRepository;
import com.bengalilkc.repository.workflow.StateTransitionRepository;
import com.bengalilkc.repository.workflow.SubmissionRepository;
import com.bengalilkc.security.PermissionMatrix;
import com.bengalilkc.security.UserPrincipal;
import com.bengalilkc.service.admin.AuditService;
import com.bengalilkc.service.admin.CaptchaService;
import com.bengalilkc.service.admin.ConfigService;
import com.bengalilkc.service.admin.IdGeneratorService;
import com.bengalilkc.service.community.LeaderboardService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SubmissionService {

    public static final int DISPUTE_NOTE_MIN = 15;
    public static final int DISPUTE_NOTE_MAX = 1000;

    /**
     * States the current release treats as final: clients offer no further status change on them.
     * The {@code override} action deliberately still accepts them (with demotion out of the lexicon),
     * so exposing backtracking later needs no backend change.
     */
    public static final Set<EntryState> FINAL_STATES = EnumSet.of(EntryState.VALIDATED, EntryState.REJECTED);

    public static final int OVERRIDE_REASON_MIN = 15;
    public static final int OVERRIDE_REASON_MAX = 1000;

    /**
     * Forward-only override targets offered by clients in this release. The {@code override} action itself
     * accepts any target (backward included), so widening this needs no API change.
     */
    public static final Map<EntryState, List<EntryState>> FORWARD_OVERRIDE_TARGETS = Map.of(
            EntryState.PENDING_REVIEW, List.of(EntryState.EDITOR_APPROVED, EntryState.VALIDATED, EntryState.REJECTED),
            EntryState.UNDER_REVIEW, List.of(EntryState.EDITOR_APPROVED, EntryState.VALIDATED, EntryState.REJECTED),
            EntryState.FLAGGED, List.of(EntryState.EDITOR_APPROVED, EntryState.ARBITRATION, EntryState.VALIDATED,
                    EntryState.REJECTED),
            EntryState.EDITOR_APPROVED, List.of(EntryState.VALIDATED, EntryState.REJECTED),
            EntryState.C_FLAGGED, List.of(EntryState.ARBITRATION, EntryState.VALIDATED, EntryState.REJECTED),
            EntryState.ARBITRATION, List.of(EntryState.VALIDATED, EntryState.REJECTED));

    /** States in which a Lexical Editor / Chief Lexical Editor may be assigned. */
    public static final Set<EntryState> ASSIGNABLE_STATES = EnumSet.of(
            EntryState.PENDING_REVIEW, EntryState.UNDER_REVIEW, EntryState.FLAGGED,
            EntryState.EDITOR_APPROVED, EntryState.C_FLAGGED);

    private final SubmissionRepository submissionRepository;
    private final StateTransitionRepository transitionRepository;
    private final EntryValidator validator;
    private final LexiconPromotionService promotionService;
    private final IdGeneratorService idGeneratorService;
    private final AuditService auditService;
    private final ConfigService configService;
    private final ArbitrationService arbitrationService;
    private final LeaderboardService leaderboardService;
    private final ObjectMapper objectMapper;
    private final AppUserRepository userRepository;
    private final QuotaService quotaService;
    private final CaptchaService captchaService;

    public SubmissionService(
            SubmissionRepository submissionRepository,
            StateTransitionRepository transitionRepository,
            EntryValidator validator,
            LexiconPromotionService promotionService,
            IdGeneratorService idGeneratorService,
            AuditService auditService,
            ConfigService configService,
            @Lazy ArbitrationService arbitrationService,
            @Lazy LeaderboardService leaderboardService,
            ObjectMapper objectMapper,
            AppUserRepository userRepository,
            QuotaService quotaService,
            CaptchaService captchaService) {
        this.userRepository = userRepository;
        this.quotaService = quotaService;
        this.captchaService = captchaService;
        this.submissionRepository = submissionRepository;
        this.transitionRepository = transitionRepository;
        this.validator = validator;
        this.promotionService = promotionService;
        this.idGeneratorService = idGeneratorService;
        this.auditService = auditService;
        this.configService = configService;
        this.arbitrationService = arbitrationService;
        this.leaderboardService = leaderboardService;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listByState(EntryState state) {
        return toDtos(submissionRepository.findByStateOrderByUpdatedAtDesc(state));
    }

    /** DTOs for many submissions with all histories loaded in one query. */
    public List<Map<String, Object>> toDtos(List<Submission> subs) {
        if (subs.isEmpty()) {
            return List.of();
        }
        Map<String, List<StateTransition>> history = new java.util.HashMap<>();
        for (StateTransition t : transitionRepository.findBySubmissionIdInOrderByCreatedAtAsc(
                subs.stream().map(Submission::getId).toList())) {
            history.computeIfAbsent(t.getSubmissionId(), k -> new ArrayList<>()).add(t);
        }
        return subs.stream().map(s -> toDto(s, history.getOrDefault(s.getId(), List.of()))).toList();
    }

    @Transactional(readOnly = true)
    public Map<String, Object> get(String id) {
        return toDto(require(id));
    }

    @Transactional
    public Map<String, Object> create(UserPrincipal principal, Map<String, Object> p) {
        if (!PermissionMatrix.has(principal.getRole(), Permission.QUICK_INPUT)) {
            throw ApiException.forbidden("Your role cannot submit lexical entries.");
        }
        var config = configService.getConfig();
        boolean isModerator = principal.getRole() == Role.SYSTEM_MODERATOR
                || principal.getRole() == Role.PLATFORM_ARCHITECT;
        boolean saveAsDraft = bool(p.get("saveAsDraft"));
        Map<String, String> errors = new LinkedHashMap<>();

        // Anti-bot: too-fast or burst submissions are accepted but held UNDER_REVIEW (SRS §4.3 / §6.2).
        String holdNote = null;
        if (!isModerator) {
            if (config.isHoneypotEnabled() && str(p.get("honeypot")).length() > 0) {
                Map<String, Object> silent = new LinkedHashMap<>();
                silent.put("ok", true);
                silent.put("id", null);
                return silent;
            }
            if (config.isCaptchaEnabled()) {
                String captchaError = captchaService.verify(p, config.getCaptchaScoreThreshold());
                if (captchaError != null) {
                    throw ApiException.unprocessable(Map.of("captcha", captchaError));
                }
            }
            QuotaService.Gate gate = quotaService.check(principal);
            switch (gate.outcome()) {
                case SUSPENDED -> throw ApiException.forbidden(gate.message());
                case BLOCKED_SESSION, BLOCKED_DAY -> throw ApiException.tooManyRequests(gate.message());
                case ANOMALY_HOLD -> holdNote = "Auto-held: " + gate.lastHour()
                        + " entries in the last hour exceeds the anomaly alert threshold of "
                        + config.getAnomalyThresholdPerHour() + " (anti-bot).";
                case OK -> { }
            }
            Number elapsed = p.get("elapsedSeconds") instanceof Number n ? n : 0;
            if (holdNote == null && elapsed.intValue() < config.getMinSubmissionSeconds()) {
                holdNote = "Auto-held: form completed faster than minimum "
                        + config.getMinSubmissionSeconds() + "s (anti-bot).";
            }
        }
        boolean holdUnderReview = holdNote != null && !saveAsDraft;

        validator.lockFor(p);
        EntryValidator.Result result = validator.validate(p, null);
        errors.putAll(result.errors());
        EntryValidator.EntryInput in = result.input();

        boolean directValidate = validatesDirectly(principal.getRole());

        if (!errors.isEmpty()) {
            throw ApiException.unprocessable(errors);
        }
        requireNearConfirmed(result, p);

        String id = "sub-" + idGeneratorService.nextValue("submission");
        Instant now = Instant.now();
        Submission sub = Submission.builder()
                .id(id)
                .state(EntryState.DRAFT)
                .contributorId(principal.getId())
                .createdAt(now)
                .build();
        applyEntry(sub, in);
        if (directValidate) {
            // No reviewer ever reads it: these entries skip the review stages.
            sub.setReviewerNote(null);
        }
        sub.setUpdatedAt(now);
        sub.setLastUpdatedBy(principal.getId());
        submissionRepository.save(sub);
        recordTransition(sub, null, EntryState.DRAFT, principal.getId(), principal.getRole().name(), null);
        auditService.record(principal, "SUBMISSION_CREATED", AuditTargetType.SUBMISSION, id,
                "\"" + in.word() + "\" (" + in.pos().getCode() + ")");

        if (!saveAsDraft) {
            if (holdUnderReview) {
                // Bypass normal LE/CLE queue until a System Moderator clears the anti-bot hold.
                transition(sub, EntryState.UNDER_REVIEW, principal.getId(), principal.getRole().name(), holdNote);
            } else if (directValidate) {
                transition(sub, EntryState.PENDING_REVIEW, principal.getId(), principal.getRole().name(), null);
                validateDirectly(sub, principal);
            } else {
                transition(sub, EntryState.PENDING_REVIEW, principal.getId(), principal.getRole().name(), null);
            }
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("id", sub.getId());
        body.put("state", sub.getState().name());
        if (holdUnderReview) {
            body.put(
                    "message",
                    "Submitted but held UNDER_REVIEW for anti-bot checks. A System Moderator will clear it.");
        }
        return body;
    }

    @Transactional
    public Map<String, Object> action(UserPrincipal principal, String id, Map<String, Object> p) {
        Submission sub = require(id);
        String action = str(p.get("action")).trim();
        String comment = str(p.get("comment")).trim();
        Role role = principal.getRole();
        boolean isOwner = sub.getContributorId().equals(principal.getId());

        return switch (action) {
            case "approve" -> {
                requirePerm(role, Permission.PRIMARY_VALIDATE);
                requireState(sub, EntryState.PENDING_REVIEW, "Only PENDING_REVIEW entries can be approved.");
                transition(sub, EntryState.EDITOR_APPROVED, principal.getId(), role.name(),
                        comment.isEmpty() ? "Structure and data quality verified." : comment);
                yield okState(sub);
            }
            case "flag" -> {
                requirePerm(role, Permission.PRIMARY_VALIDATE);
                requireState(sub, EntryState.PENDING_REVIEW, "Only PENDING_REVIEW entries can be flagged.");
                if (comment.length() < 15) {
                    throw ApiException.unprocessable("Flag comment must be at least 15 characters.");
                }
                sub.setEditorComment(comment);
                transition(sub, EntryState.FLAGGED, principal.getId(), role.name(), comment);
                yield okState(sub);
            }
            case "reject" -> {
                requirePerm(role, Permission.PRIMARY_VALIDATE);
                requireState(sub, EntryState.PENDING_REVIEW, "Only PENDING_REVIEW entries can be rejected.");
                if (comment.isEmpty()) {
                    throw ApiException.unprocessable("A rejection reason is mandatory.");
                }
                sub.setRejectionReason(comment);
                transition(sub, EntryState.REJECTED, principal.getId(), role.name(), comment);
                yield okState(sub);
            }
            case "cflag" -> {
                requirePerm(role, Permission.FINAL_VALIDATE);
                requireState(sub, EntryState.EDITOR_APPROVED, "Only EDITOR_APPROVED entries can be C-flagged.");
                if (comment.length() < 15) {
                    throw ApiException.unprocessable("Linguistic note must be at least 15 characters.");
                }
                sub.setCleComment(comment);
                transition(sub, EntryState.C_FLAGGED, principal.getId(), role.name(), comment);
                auditService.recordSystem("NOTIFY", AuditTargetType.SUBMISSION, sub.getId(),
                        "C_FLAG notification sent to Lexical Editor and Contributor simultaneously");
                yield okState(sub);
            }
            case "validate" -> {
                requirePerm(role, Permission.FINAL_VALIDATE);
                requireState(sub, EntryState.EDITOR_APPROVED, "Only EDITOR_APPROVED entries can be validated.");
                transition(sub, EntryState.VALIDATED, principal.getId(), role.name(),
                        comment.isEmpty() ? "Linguistically correct. Approved for export." : comment);
                sub.setValidatedBy(principal.getId());
                sub.setValidatedAt(Instant.now());
                submissionRepository.save(sub);
                leaderboardService.announceValidationMilestones(sub);
                yield okState(sub);
            }
            case "escalate" -> arbitrationService.openEscalation(principal, sub, comment);
            case "resubmit" -> resubmit(principal, id, p);
            case "submit_draft" -> {
                if (!isOwner) {
                    throw ApiException.forbidden("Only the owner can submit a draft.");
                }
                requireState(sub, EntryState.DRAFT, "Entry is not a draft.");
                Map<String, Object> stored = storedPayload(sub);
                validator.lockFor(stored);
                Map<String, String> problems = validator.validate(stored, sub.getId()).errors();
                if (!problems.isEmpty()) {
                    throw ApiException.unprocessable(Map.of("form",
                            "This draft can no longer be submitted: " + problems.values().iterator().next()
                                    + " Delete it and create a corrected entry."));
                }
                transition(sub, EntryState.PENDING_REVIEW, principal.getId(), role.name(), null);
                if (validatesDirectly(role)) {
                    validateDirectly(sub, principal);
                }
                yield okState(sub);
            }
            case "delete_draft" -> {
                if (!isOwner) {
                    throw ApiException.forbidden("Only the owner can delete a draft.");
                }
                requireState(sub, EntryState.DRAFT, "Only DRAFT entries can be deleted.");
                transitionRepository.findBySubmissionIdOrderByCreatedAtAsc(sub.getId())
                        .forEach(transitionRepository::delete);
                submissionRepository.delete(sub);
                auditService.record(principal, "DRAFT_DELETED", AuditTargetType.SUBMISSION, sub.getId(),
                        "\"" + sub.getWrittenForm() + "\"");
                yield Map.of("ok", true);
            }
            case "dispute" -> {
                Map<String, Object> body = new LinkedHashMap<>(p);
                body.putIfAbsent("note", comment);
                yield dispute(principal, id, body);
            }
            case "clear_under_review" -> {
                requirePerm(role, Permission.OVERRIDE_STATE);
                requireState(sub, EntryState.UNDER_REVIEW, "Entry is not under anti-bot review.");
                transition(sub, EntryState.PENDING_REVIEW, principal.getId(), role.name(),
                        comment.isEmpty() ? "Anti-bot hold cleared after manual review." : comment);
                yield okState(sub);
            }
            // Backtracks to any state, final ones included; leaving VALIDATED removes the entry from the lexicon.
            case "override" -> {
                requirePerm(role, Permission.OVERRIDE_STATE);
                EntryState target;
                try {
                    target = EntryState.valueOf(str(p.get("targetState")));
                } catch (Exception e) {
                    throw ApiException.unprocessable("Target state is required.");
                }
                String reasonError = overrideReasonError(comment);
                if (reasonError != null) {
                    throw ApiException.unprocessable(Map.of("comment", reasonError));
                }
                EntryState from = sub.getState();
                if (target == from) {
                    throw ApiException.unprocessable(Map.of("targetState", "The entry is already " + from.name() + "."));
                }
                requireVersion(sub, p.get("expectedVersion"));
                String caseId = null;
                List<String> closedCases = List.of();
                if (target == EntryState.ARBITRATION) {
                    caseId = arbitrationService.openByOverride(principal, sub, comment);
                } else {
                    transition(sub, target, principal.getId(), role.name(), "EMERGENCY OVERRIDE: " + comment);
                    if (from == EntryState.ARBITRATION) {
                        closedCases = arbitrationService.closeByOverride(principal, sub, target, comment);
                    }
                }
                if (target == EntryState.VALIDATED) {
                    sub.setValidatedBy(principal.getId());
                    sub.setValidatedAt(Instant.now());
                    sub.setRewardEligible(false);
                } else if (from == EntryState.VALIDATED) {
                    sub.setValidatedBy(null);
                    sub.setValidatedAt(null);
                }
                if (target == EntryState.REJECTED) {
                    sub.setRejectionReason(comment);
                }
                submissionRepository.save(sub);
                auditService.record(principal, "STATE_OVERRIDE", AuditTargetType.SUBMISSION, sub.getId(),
                        "\"" + sub.getWrittenForm() + "\" " + from.name() + " → " + target.name()
                                + (from == EntryState.VALIDATED ? " (removed from the lexicon)"
                                        : target == EntryState.VALIDATED ? " (added to the lexicon)" : ""));
                Map<String, Object> res = new LinkedHashMap<>(okState(sub));
                res.put("previousState", from.name());
                res.put("lexicon", from == EntryState.VALIDATED ? "removed"
                        : target == EntryState.VALIDATED ? "added" : "unchanged");
                if (caseId != null) {
                    res.put("caseId", caseId);
                }
                if (!closedCases.isEmpty()) {
                    res.put("closedCases", closedCases);
                }
                submissionRepository.flush();
                res.put("version", sub.getVersion());
                yield res;
            }
            case "assign" -> assign(principal, sub, p);
            default -> throw ApiException.unprocessable("Unknown action.");
        };
    }

    /**
     * Corrected resubmission of the caller's own FLAGGED / C-FLAGGED entry. The full entry is
     * re-validated exactly as a new Quick Input submission; a FLAGGED entry returns to the
     * Lexical Editor queue, a C-FLAGGED entry returns to the Chief Editor queue.
     */
    @Transactional
    public Map<String, Object> resubmit(UserPrincipal principal, String id, Map<String, Object> p) {
        Submission sub = require(id);
        if (!sub.getContributorId().equals(principal.getId())) {
            throw ApiException.forbidden("Only the contributor who submitted this entry can resubmit it.");
        }
        EntryState from = sub.getState();
        EntryState target = resubmitTarget(from);
        if (target == null) {
            throw ApiException.conflict("This entry is now " + from.name()
                    + "; only FLAGGED or C_FLAGGED entries can be resubmitted.");
        }
        requireVersion(sub, p.get("expectedVersion"));

        Map<String, String> errors = new LinkedHashMap<>();
        boolean isModerator = principal.getRole() == Role.SYSTEM_MODERATOR
                || principal.getRole() == Role.PLATFORM_ARCHITECT;
        var config = configService.getConfig();
        if (!isModerator && config.isCaptchaEnabled()) {
            String captchaError = captchaService.verify(p, config.getCaptchaScoreThreshold());
            if (captchaError != null) {
                errors.put("captcha", captchaError);
            }
        }
        validator.lockFor(p);
        EntryValidator.Result result = validator.validate(p, sub.getId());
        errors.putAll(result.errors());
        EntryValidator.EntryInput in = result.input();
        if (!errors.isEmpty()) {
            throw ApiException.unprocessable(errors);
        }
        List<String> changed = changedFields(sub, in);
        if (changed.isEmpty()) {
            throw ApiException.unprocessable(Map.of("form",
                    "No changes detected. Edit the entry to address the reviewer's feedback, "
                            + "or dispute the decision instead."));
        }
        requireNearConfirmed(result, p);

        applyEntry(sub, in);
        String comment = "Corrected and resubmitted by contributor. Changed: " + String.join(", ", changed) + ".";
        transition(sub, target, principal.getId(), principal.getRole().name(), comment);
        if (validatesDirectly(principal.getRole())) {
            validateDirectly(sub, principal);
        }
        auditService.record(principal, "SUBMISSION_RESUBMITTED", AuditTargetType.SUBMISSION, sub.getId(),
                "\"" + sub.getWrittenForm() + "\" " + from.name() + " → " + sub.getState().name());
        submissionRepository.flush();

        Map<String, Object> res = new LinkedHashMap<>();
        res.put("ok", true);
        res.put("id", sub.getId());
        res.put("previousState", from.name());
        res.put("state", sub.getState().name());
        res.put("changed", changed);
        res.put("version", sub.getVersion());
        return res;
    }

    /** Fields a tie correction changed, plus a before/after value for each. */
    public record TieCorrection(List<String> changed, List<Map<String, Object>> details) {}

    /**
     * Tie resolution: the System Moderator corrects the tied entry (validated exactly like a Quick Input entry)
     * and it moves straight from ARBITRATION to VALIDATED.
     */
    @Transactional
    public TieCorrection correctAndValidateTiedEntry(
            UserPrincipal principal, Submission sub, Map<String, Object> p, String caseId) {
        if (sub.getState() != EntryState.ARBITRATION) {
            throw ApiException.conflict("This entry is now " + sub.getState().name()
                    + "; only entries still in arbitration can be corrected from a tied case.");
        }
        requireVersion(sub, p.get("expectedVersion"));
        Map<String, Object> payload = new LinkedHashMap<>(p);
        // The contributor's own note is part of the record; the moderator form does not edit it.
        if (str(payload.get("reviewerNote")).isBlank()) {
            payload.put("reviewerNote", sub.getReviewerNote());
        }
        validator.lockFor(payload);
        EntryValidator.Result result = validator.validate(payload, sub.getId());
        if (!result.errors().isEmpty()) {
            throw ApiException.unprocessable(result.errors());
        }
        EntryValidator.EntryInput in = result.input();
        requireNearConfirmed(result, payload);
        List<String> changed = changedFields(sub, in);
        List<Map<String, Object>> details = changeDetails(sub, in, changed);
        applyEntry(sub, in);
        sub.setValidatedBy(principal.getId());
        sub.setValidatedAt(Instant.now());
        sub.setRewardEligible(false);
        transition(sub, EntryState.VALIDATED, principal.getId(), principal.getRole().name(),
                "Tied arbitration " + caseId + " resolved by the System Moderator: entry "
                        + (changed.isEmpty() ? "validated without changes." : "corrected (" + String.join(", ", changed) + ") and validated."));
        submissionRepository.flush();
        return new TieCorrection(changed, details);
    }

    private List<Map<String, Object>> changeDetails(Submission sub, EntryValidator.EntryInput in, List<String> changed) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (String field : changed) {
            String before;
            String after;
            switch (field) {
                case "word" -> {
                    before = sub.getWrittenForm();
                    after = in.word();
                }
                case "part of speech" -> {
                    before = sub.getPartOfSpeech() == null ? null : sub.getPartOfSpeech().getCode();
                    after = in.pos() == null ? null : in.pos().getCode();
                }
                case "synset linkage" -> {
                    before = linkage(sub.getSynsetAction(), sub.getSynsetId(), sub.getIliCode());
                    after = linkage(in.action(), in.synsetId(), in.iliCode());
                }
                case "definition" -> {
                    before = definitionLabel(sub.getDefinitionLanguage(), sub.getDefinitionText());
                    after = definitionLabel(in.definitionLanguage(), in.definitionText());
                }
                case "sense relations" -> {
                    before = relationsLabel(readSense(sub.getSenseRelationsJson()).stream()
                            .map(r -> r.relType() + " → " + r.target()).toList());
                    after = relationsLabel(in.senseRelations().stream().map(r -> r.relType() + " → " + r.target()).toList());
                }
                case "synset relations" -> {
                    before = relationsLabel(readSynset(sub.getSynsetRelationsJson()).stream()
                            .map(r -> r.relType() + " → " + r.target()).toList());
                    after = relationsLabel(in.synsetRelations().stream().map(r -> r.relType() + " → " + r.target()).toList());
                }
                case "reviewer note" -> {
                    before = sub.getReviewerNote();
                    after = in.reviewerNote();
                }
                default -> {
                    before = null;
                    after = null;
                }
            }
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("field", field);
            d.put("before", before);
            d.put("after", after);
            out.add(d);
        }
        return out;
    }

    private static String linkage(SynsetAction action, String synsetId, String iliCode) {
        if (action == SynsetAction.CREATE) {
            return "New synset" + (iliCode == null || iliCode.isBlank() ? "" : " (ILI " + iliCode + ")");
        }
        return synsetId == null ? null : "Linked to " + synsetId;
    }

    private static String definitionLabel(DefinitionLanguage lang, String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        return (lang == null ? "" : "[" + lang.getCode() + "] ") + text;
    }

    private static String relationsLabel(List<String> rels) {
        return rels.isEmpty() ? null : String.join("; ", rels);
    }

    /** Contributor dispute of a FLAGGED / C-FLAGGED decision; opens an arbitration case. */
    @Transactional
    public Map<String, Object> dispute(UserPrincipal principal, String id, Map<String, Object> p) {
        Submission sub = require(id);
        String note = str(p.get("note")).strip();
        EntryState from = sub.getState();
        if (sub.getContributorId().equals(principal.getId())) {
            requireVersion(sub, p.get("expectedVersion"));
        }
        Map<String, Object> res = new LinkedHashMap<>(arbitrationService.openDispute(principal, sub, note));
        submissionRepository.flush();
        res.put("id", sub.getId());
        res.put("previousState", from.name());
        res.put("version", sub.getVersion());
        return res;
    }

    /**
     * Actions the owner may take on their own entry right now, so the detail page renders
     * exactly what the API accepts. Returns null when the caller is not the owner.
     */
    public Map<String, Object> ownerOptions(UserPrincipal principal, Submission sub, List<ArbitrationCase> cases) {
        if (!sub.getContributorId().equals(principal.getId())) {
            return null;
        }
        List<Map<String, Object>> actions = new ArrayList<>();
        switch (sub.getState()) {
            case DRAFT -> {
                actions.add(validatesDirectly(principal.getRole())
                        ? ownerAction("SUBMIT_DRAFT", "Submit & validate", EntryState.VALIDATED)
                        : ownerAction("SUBMIT_DRAFT", "Submit for review", EntryState.PENDING_REVIEW));
                actions.add(ownerAction("DELETE_DRAFT", "Delete draft", null));
            }
            case FLAGGED, C_FLAGGED -> {
                actions.add(ownerAction("RESUBMIT", "Resubmission", validatesDirectly(principal.getRole())
                        ? EntryState.VALIDATED
                        : resubmitTarget(sub.getState())));
                if (PermissionMatrix.has(principal.getRole(), Permission.DISPUTE) && !decidedByArbitration(sub)) {
                    Map<String, Object> d = ownerAction("DISPUTE", "Dispute to arbitration", EntryState.ARBITRATION);
                    d.put("noteMin", DISPUTE_NOTE_MIN);
                    d.put("noteMax", DISPUTE_NOTE_MAX);
                    actions.add(d);
                }
            }
            default -> { }
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("actions", actions);
        if (sub.getState() == EntryState.ARBITRATION) {
            cases.stream()
                    .filter(c -> c.getStatus() != ArbitrationStatus.RESOLVED)
                    .max(java.util.Comparator.comparing(ArbitrationCase::getOpenedAt,
                            java.util.Comparator.nullsFirst(java.util.Comparator.naturalOrder())))
                    .ifPresent(c -> m.put("activeCase", Map.of("id", c.getId(), "status", c.getStatus().name())));
        }
        return m;
    }

    /**
     * Lifecycle facts for the detail page: whether the state is final in this release and, for roles
     * holding OVERRIDE_STATE, every state the {@code override} action accepts from here (final states
     * included), so a client can expose backtracking without a backend change.
     */
    public Map<String, Object> lifecycle(UserPrincipal principal, Submission sub) {
        Map<String, Object> m = new LinkedHashMap<>();
        EntryState state = sub.getState();
        boolean isFinal = FINAL_STATES.contains(state);
        m.put("final", isFinal);
        if (isFinal) {
            m.put("finalReason", state == EntryState.VALIDATED
                    ? "Validated entries are part of the lexicon and their status is final."
                    : "Rejected entries are closed and their status is final.");
        }
        if (PermissionMatrix.has(principal.getRole(), Permission.OVERRIDE_STATE)) {
            m.put("forwardOverrideTargets", FORWARD_OVERRIDE_TARGETS.getOrDefault(state, List.of()).stream()
                    .map(Enum::name)
                    .toList());
            m.put("overrideTargets", Arrays.stream(EntryState.values())
                    .filter(s -> s != state)
                    .map(Enum::name)
                    .toList());
            m.put("overrideReasonMin", OVERRIDE_REASON_MIN);
            m.put("overrideReasonMax", OVERRIDE_REASON_MAX);
        }
        if (PermissionMatrix.has(principal.getRole(), Permission.ASSIGN_ENTRIES)) {
            m.put("assignable", ASSIGNABLE_STATES.contains(state));
        }
        return m;
    }

    public static String overrideReasonError(String reason) {
        int len = reason == null ? 0 : reason.strip().length();
        if (len == 0) {
            return "An override reason is required.";
        }
        if (len < OVERRIDE_REASON_MIN) {
            return "The override reason must be at least " + OVERRIDE_REASON_MIN + " characters.";
        }
        if (len > OVERRIDE_REASON_MAX) {
            return "The override reason must be at most " + OVERRIDE_REASON_MAX + " characters.";
        }
        return null;
    }

    /**
     * Sets the optional Lexical Editor ({@code editorId}) and/or Chief Lexical Editor ({@code chiefId}) slot.
     * A key that is present but blank clears that slot; an absent key leaves it unchanged.
     */
    private Map<String, Object> assign(UserPrincipal principal, Submission sub, Map<String, Object> p) {
        requirePerm(principal.getRole(), Permission.ASSIGN_ENTRIES);
        if (!ASSIGNABLE_STATES.contains(sub.getState())) {
            throw ApiException.unprocessable(Map.of("form",
                    "Reviewers can only be assigned while the entry is in review (not in "
                            + sub.getState().name() + ")."));
        }
        requireVersion(sub, p.get("expectedVersion"));
        Map<String, String> errors = new LinkedHashMap<>();
        List<String> changes = new ArrayList<>();
        if (p.containsKey("editorId")) {
            String id = blankToNull(str(p.get("editorId")).trim());
            AppUser u = assignee(sub, id, Role.LEXICAL_EDITOR, "editorId", errors);
            if (!errors.containsKey("editorId") && !java.util.Objects.equals(id, sub.getAssignedTo())) {
                sub.setAssignedTo(id);
                changes.add("Lexical Editor → " + (u != null ? u.getName() : "unassigned"));
            }
        }
        if (p.containsKey("chiefId")) {
            String id = blankToNull(str(p.get("chiefId")).trim());
            AppUser u = assignee(sub, id, Role.CHIEF_LEXICAL_EDITOR, "chiefId", errors);
            if (!errors.containsKey("chiefId") && !java.util.Objects.equals(id, sub.getAssignedChief())) {
                sub.setAssignedChief(id);
                changes.add("Chief Lexical Editor → " + (u != null ? u.getName() : "unassigned"));
            }
        }
        if (!errors.isEmpty()) {
            throw ApiException.unprocessable(errors);
        }
        if (changes.isEmpty()) {
            throw ApiException.unprocessable(Map.of("form", "The assignment is unchanged."));
        }
        sub.setLastUpdatedBy(principal.getId());
        sub.setUpdatedAt(Instant.now());
        submissionRepository.saveAndFlush(sub);
        auditService.record(principal, "ENTRY_ASSIGNED", AuditTargetType.SUBMISSION, sub.getId(),
                "\"" + sub.getWrittenForm() + "\" " + String.join("; ", changes));
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("ok", true);
        res.put("assignedTo", sub.getAssignedTo());
        res.put("assignedChief", sub.getAssignedChief());
        res.put("changes", changes);
        res.put("version", sub.getVersion());
        return res;
    }

    private AppUser assignee(Submission sub, String id, Role role, String field, Map<String, String> errors) {
        if (id == null) {
            return null;
        }
        AppUser u = userRepository.findById(id).orElse(null);
        String label = role == Role.LEXICAL_EDITOR ? "Lexical Editor" : "Chief Lexical Editor";
        if (u == null || u.getRole() != role) {
            errors.put(field, "Select an existing " + label + ".");
        } else if (!u.isActive() || u.isSuspended()) {
            errors.put(field, u.getName() + " is not an active " + label + ".");
        } else if (u.getId().equals(sub.getContributorId())) {
            errors.put(field, u.getName() + " submitted this entry and cannot review it.");
        }
        return u;
    }

    public static EntryState resubmitTarget(EntryState state) {
        return switch (state) {
            case FLAGGED -> EntryState.PENDING_REVIEW;
            case C_FLAGGED -> EntryState.EDITOR_APPROVED;
            default -> null;
        };
    }

    /** Platform Architect and System Moderator entries skip the review queues. */
    public static boolean validatesDirectly(Role role) {
        return PermissionMatrix.has(role, Permission.DIRECT_APPROVAL);
    }

    /** Moves a PENDING_REVIEW / EDITOR_APPROVED entry of a direct-validating role to VALIDATED (adds it to the lexicon). */
    private void validateDirectly(Submission sub, UserPrincipal principal) {
        String who = principal.getRole() == Role.PLATFORM_ARCHITECT ? "Platform Architect" : "System Moderator";
        transition(sub, EntryState.VALIDATED, principal.getId(), principal.getRole().name(),
                "Validated directly on entry by the " + who + ".");
        sub.setValidatedBy(principal.getId());
        sub.setValidatedAt(Instant.now());
        sub.setRewardEligible(false);
        submissionRepository.save(sub);
    }

    private static Map<String, Object> ownerAction(String action, String label, EntryState toState) {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("action", action);
        a.put("label", label);
        a.put("toState", toState != null ? toState.name() : null);
        return a;
    }

    private static void requireVersion(Submission sub, Object expected) {
        if (expected instanceof Number n && sub.getVersion() != null && n.longValue() != sub.getVersion()) {
            throw ApiException.conflict(
                    "This entry was updated after you opened it. Reload to see its current state.");
        }
    }

    /** Refuses with 409 {@code nearDuplicate} until the user confirms a polysemy warning. */
    private static void requireNearConfirmed(EntryValidator.Result result, Map<String, Object> p) {
        if (result.nearDuplicate() != null && !bool(p.get("confirmNearDuplicate"))) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("nearDuplicate", result.nearDuplicate());
            throw new NearDuplicateException(body);
        }
    }

    /** A stored entry as a Quick Input payload, for re-validating drafts. */
    private Map<String, Object> storedPayload(Submission sub) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("writtenForm", sub.getWrittenForm());
        p.put("pos", sub.getPartOfSpeech() != null ? sub.getPartOfSpeech().getCode() : null);
        p.put("synsetAction", sub.getSynsetAction() != null ? sub.getSynsetAction().getCode() : null);
        p.put("synsetId", sub.getSynsetId());
        p.put("iliCode", sub.getIliCode());
        p.put("definitionText", sub.getDefinitionText());
        p.put("definitionLanguage", sub.getDefinitionLanguage() != null ? sub.getDefinitionLanguage().getCode() : null);
        p.put("reviewerNote", sub.getReviewerNote());
        p.put("senseRelations", readSense(sub.getSenseRelationsJson()));
        p.put("synsetRelations", readSynset(sub.getSynsetRelationsJson()));
        return p;
    }

    private void applyEntry(Submission sub, EntryValidator.EntryInput in) {
        sub.setWrittenForm(in.word());
        sub.setPartOfSpeech(in.pos());
        sub.setSynsetAction(in.action());
        sub.setSynsetId(in.synsetId());
        sub.setIliCode(in.iliCode());
        sub.setDefinitionText(in.definitionText());
        sub.setDefinitionLanguage(in.definitionLanguage());
        sub.setReviewerNote(in.reviewerNote());
        sub.setSenseRelationsJson(writeJson(in.senseRelations()));
        sub.setSynsetRelationsJson(writeJson(in.synsetRelations()));
    }

    /** Human-readable names of the fields a resubmission changes. */
    private List<String> changedFields(Submission sub, EntryValidator.EntryInput in) {
        List<String> out = new ArrayList<>();
        if (!java.util.Objects.equals(sub.getWrittenForm(), in.word())) {
            out.add("word");
        }
        if (sub.getPartOfSpeech() != in.pos()) {
            out.add("part of speech");
        }
        if (sub.getSynsetAction() != in.action()
                || !java.util.Objects.equals(sub.getSynsetId(), in.synsetId())
                || !java.util.Objects.equals(sub.getIliCode(), in.iliCode())) {
            out.add("synset linkage");
        }
        if (!java.util.Objects.equals(sub.getDefinitionText(), in.definitionText())
                || (in.definitionText() != null && sub.getDefinitionLanguage() != in.definitionLanguage())) {
            out.add("definition");
        }
        if (!new java.util.HashSet<>(readSense(sub.getSenseRelationsJson()))
                .equals(new java.util.HashSet<>(in.senseRelations()))) {
            out.add("sense relations");
        }
        if (!new java.util.HashSet<>(readSynset(sub.getSynsetRelationsJson()))
                .equals(new java.util.HashSet<>(in.synsetRelations()))) {
            out.add("synset relations");
        }
        if (!java.util.Objects.equals(sub.getReviewerNote(), in.reviewerNote())) {
            out.add("reviewer note");
        }
        return out;
    }

    @Transactional
    public void transition(Submission sub, EntryState to, String actorId, String actorRole, String comment) {
        EntryState from = sub.getState();
        recordTransition(sub, from, to, actorId, actorRole, comment);
        auditService.record(actorId, actorRole, "STATE " + from + " → " + to, AuditTargetType.SUBMISSION, sub.getId(),
                "\"" + sub.getWrittenForm() + "\"" + (comment != null ? " — " + comment.substring(0, Math.min(80, comment.length())) : ""));
        sub.setState(to);
        sub.setUpdatedAt(Instant.now());
        sub.setLastUpdatedBy(actorId);
        submissionRepository.saveAndFlush(sub);
        if (to == EntryState.VALIDATED && from != EntryState.VALIDATED) {
            promotionService.promote(sub, actorId, actorRole);
        } else if (from == EntryState.VALIDATED && to != EntryState.VALIDATED) {
            promotionService.demote(sub, actorId, actorRole);
        }
        if (to == EntryState.VALIDATED && "CHIEF_LEXICAL_EDITOR".equals(actorRole)) {
            leaderboardService.announceValidationMilestones(sub);
        } else if (to == EntryState.VALIDATED && "SYSTEM".equals(actorRole)) {
            leaderboardService.announceValidationMilestones(sub);
        }
    }

    private void recordTransition(
            Submission sub, EntryState from, EntryState to, String actorId, String actorRole, String comment) {
        transitionRepository.save(StateTransition.builder()
                .submissionId(sub.getId())
                .fromState(from)
                .toState(to)
                .actorId(actorId)
                .actorRole(actorRole)
                .commentText(comment)
                .createdAt(Instant.now())
                .build());
    }

    public Map<String, Object> toDto(Submission s) {
        return toDto(s, transitionRepository.findBySubmissionIdOrderByCreatedAtAsc(s.getId()));
    }

    private Map<String, Object> toDto(Submission s, List<StateTransition> transitions) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.getId());
        m.put("state", s.getState().name());
        m.put("contributorId", s.getContributorId());
        m.put("createdAt", s.getCreatedAt() != null ? s.getCreatedAt().toString() : null);
        m.put("updatedAt", s.getUpdatedAt() != null ? s.getUpdatedAt().toString() : null);
        m.put("lastUpdatedBy", s.getLastUpdatedBy());
        m.put("assignedTo", s.getAssignedTo());
        m.put("assignedChief", s.getAssignedChief());
        m.put("writtenForm", s.getWrittenForm());
        m.put("pos", s.getPartOfSpeech() != null ? s.getPartOfSpeech().getCode() : null);
        m.put("synsetAction", s.getSynsetAction() != null ? s.getSynsetAction().getCode() : null);
        m.put("synsetId", s.getSynsetId());
        m.put("iliCode", s.getIliCode());
        m.put("definitionLanguage", s.getDefinitionLanguage() != null ? s.getDefinitionLanguage().getCode() : null);
        m.put("definitionText", s.getDefinitionText());
        m.put("senseRelations", readSense(s.getSenseRelationsJson()));
        m.put("synsetRelations", readSynset(s.getSynsetRelationsJson()));
        m.put("reviewerNote", s.getReviewerNote());
        m.put("editorComment", s.getEditorComment());
        m.put("cleComment", s.getCleComment());
        m.put("rejectionReason", s.getRejectionReason());
        m.put("validatedBy", s.getValidatedBy());
        m.put("validatedAt", s.getValidatedAt() != null ? s.getValidatedAt().toString() : null);
        m.put("version", s.getVersion());
        List<Map<String, Object>> history = new ArrayList<>();
        for (StateTransition t : transitions) {
            Map<String, Object> h = new LinkedHashMap<>();
            h.put("from", t.getFromState() != null ? t.getFromState().name() : null);
            h.put("to", t.getToState().name());
            h.put("actorId", t.getActorId());
            h.put("actorRole", t.getActorRole());
            h.put("timestamp", t.getCreatedAt() != null ? t.getCreatedAt().toString() : null);
            h.put("comment", t.getCommentText());
            history.add(h);
        }
        m.put("history", history);
        return m;
    }

    public Submission require(String id) {
        return submissionRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound("Submission not found"));
    }

    /** True when the entry's current state was set by an arbitration decision, which cannot be appealed. */
    public boolean decidedByArbitration(Submission sub) {
        List<StateTransition> history = transitionRepository.findBySubmissionIdOrderByCreatedAtAsc(sub.getId());
        for (int i = history.size() - 1; i >= 0; i--) {
            StateTransition t = history.get(i);
            if (t.getToState() == sub.getState()) {
                return t.getFromState() == EntryState.ARBITRATION && "SYSTEM".equals(t.getActorRole());
            }
        }
        return false;
    }

    @Transactional
    public void markValidated(String submissionId, String actorId) {
        Submission sub = require(submissionId);
        sub.setValidatedBy(actorId);
        sub.setValidatedAt(Instant.now());
        submissionRepository.save(sub);
    }

    private static void requirePerm(Role role, Permission permission) {
        if (!PermissionMatrix.has(role, permission)) {
            throw ApiException.forbidden("Not authorised.");
        }
    }

    private static void requireState(Submission sub, EntryState expected, String message) {
        if (sub.getState() != expected) {
            throw ApiException.unprocessable(message);
        }
    }

    private static Map<String, Object> okState(Submission sub) {
        return Map.of("ok", true, "state", sub.getState().name());
    }

    private String writeJson(Object value) {
        if (value == null) {
            return "[]";
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "[]";
        }
    }

    private List<SenseRelationPayload> readSense(String json) {
        try {
            if (json == null || json.isBlank()) {
                return List.of();
            }
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    private List<SynsetRelationPayload> readSynset(String json) {
        try {
            if (json == null || json.isBlank()) {
                return List.of();
            }
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static boolean bool(Object o) {
        return o instanceof Boolean b && b;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    /** Special conflict carrying nearDuplicate field (not errors map). */
    public static class NearDuplicateException extends RuntimeException {
        private final Map<String, Object> body;

        public NearDuplicateException(Map<String, Object> body) {
            this.body = body;
        }

        public Map<String, Object> getBody() {
            return body;
        }
    }
}
