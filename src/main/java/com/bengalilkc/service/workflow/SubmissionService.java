package com.bengalilkc.service.workflow;

import com.bengalilkc.common.SenseRelationPayload;
import com.bengalilkc.common.SynsetRelationPayload;
import com.bengalilkc.common.exception.ApiException;
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
import com.bengalilkc.domain.entity.lexicon.SynsetEntity;
import com.bengalilkc.repository.lexicon.SenseRepository;
import com.bengalilkc.repository.lexicon.SynsetRepository;
import com.bengalilkc.repository.workflow.StateTransitionRepository;
import com.bengalilkc.repository.workflow.SubmissionRepository;
import com.bengalilkc.security.PermissionMatrix;
import com.bengalilkc.security.UserPrincipal;
import com.bengalilkc.service.admin.AuditService;
import com.bengalilkc.service.admin.ConfigService;
import com.bengalilkc.service.admin.IdGeneratorService;
import com.bengalilkc.service.community.LeaderboardService;
import com.bengalilkc.service.lexicon.LexiconValidationService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SubmissionService {

    private static final Pattern BENGALI = Pattern.compile("[\\u0980-\\u09ff]");
    private static final Pattern ILI = Pattern.compile("^\\d{8}-[nvar]$");

    public static final int DISPUTE_NOTE_MIN = 15;
    public static final int DISPUTE_NOTE_MAX = 1000;

    private static final Set<String> SENSE_REL_TYPES = Set.of("derivation", "antonym", "metonym");
    private static final Set<String> SYNSET_REL_TYPES =
            Set.of("hypernym", "mero_part", "mero_substance", "mero_member", "attribute");

    private final SubmissionRepository submissionRepository;
    private final StateTransitionRepository transitionRepository;
    private final SynsetRepository synsetRepository;
    private final SenseRepository senseRepository;
    private final IdGeneratorService idGeneratorService;
    private final AuditService auditService;
    private final ConfigService configService;
    private final ArbitrationService arbitrationService;
    private final LeaderboardService leaderboardService;
    private final ObjectMapper objectMapper;

    public SubmissionService(
            SubmissionRepository submissionRepository,
            StateTransitionRepository transitionRepository,
            SynsetRepository synsetRepository,
            SenseRepository senseRepository,
            IdGeneratorService idGeneratorService,
            AuditService auditService,
            ConfigService configService,
            @Lazy ArbitrationService arbitrationService,
            @Lazy LeaderboardService leaderboardService,
            ObjectMapper objectMapper) {
        this.submissionRepository = submissionRepository;
        this.transitionRepository = transitionRepository;
        this.synsetRepository = synsetRepository;
        this.senseRepository = senseRepository;
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

        // Anti-bot: too-fast submissions are accepted but held UNDER_REVIEW (SRS §4.3 / §6.2).
        boolean holdUnderReview = false;
        if (!isModerator) {
            if (config.isHoneypotEnabled() && str(p.get("honeypot")).length() > 0) {
                Map<String, Object> silent = new LinkedHashMap<>();
                silent.put("ok", true);
                silent.put("id", null);
                return silent;
            }
            Number elapsed = p.get("elapsedSeconds") instanceof Number n ? n : 0;
            if (elapsed.intValue() < config.getMinSubmissionSeconds() && !saveAsDraft) {
                holdUnderReview = true;
            }
            if (config.isCaptchaEnabled() && !bool(p.get("captchaPassed")) && !saveAsDraft) {
                errors.put("captcha", "Please complete the CAPTCHA verification.");
            }
            long last24h = submissionRepository.countByContributorIdAndCreatedAtAfter(
                    principal.getId(), Instant.now().minus(24, ChronoUnit.HOURS));
            if (last24h >= config.getMaxEntriesPerDay()) {
                throw ApiException.tooManyRequests(
                        "Daily entry limit reached (" + config.getMaxEntriesPerDay()
                                + "/24h). Repeated violations trigger automatic suspension.");
            }
        }

        EntryInput in = validateEntry(p, errors);

        boolean directApprove = bool(p.get("directApprove"))
                && PermissionMatrix.has(principal.getRole(), Permission.DIRECT_APPROVAL);
        String justification = str(p.get("directApproveJustification")).trim();
        if (directApprove && justification.length() < 20) {
            errors.put("directApproveJustification",
                    "Direct approval requires a written justification of at least 20 characters.");
        }

        if (!errors.isEmpty()) {
            throw ApiException.unprocessable(errors);
        }

        checkDuplicates(in, null, bool(p.get("confirmNearDuplicate")));

        String id = "sub-" + idGeneratorService.nextValue("submission");
        Instant now = Instant.now();
        Submission sub = Submission.builder()
                .id(id)
                .state(EntryState.DRAFT)
                .contributorId(principal.getId())
                .createdAt(now)
                .build();
        applyEntry(sub, in);
        sub.setUpdatedAt(now);
        sub.setLastUpdatedBy(principal.getId());
        submissionRepository.save(sub);
        recordTransition(sub, null, EntryState.DRAFT, principal.getId(), principal.getRole().name(), null);
        auditService.record(principal, "SUBMISSION_CREATED", AuditTargetType.SUBMISSION, id,
                "\"" + in.word() + "\" (" + in.pos().getCode() + ")");

        if (!saveAsDraft) {
            if (holdUnderReview) {
                // Bypass normal LE/CLE queue until a System Moderator clears the anti-bot hold.
                transition(
                        sub,
                        EntryState.UNDER_REVIEW,
                        principal.getId(),
                        principal.getRole().name(),
                        "Auto-held: form completed faster than minimum "
                                + config.getMinSubmissionSeconds()
                                + "s (anti-bot).");
            } else if (directApprove) {
                transition(sub, EntryState.PENDING_REVIEW, principal.getId(), principal.getRole().name(), null);
                transition(sub, EntryState.VALIDATED, principal.getId(), principal.getRole().name(),
                        "Direct approval: " + justification);
                sub.setValidatedBy(principal.getId());
                sub.setValidatedAt(Instant.now());
                sub.setRewardEligible(false);
                submissionRepository.save(sub);
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
                transition(sub, EntryState.PENDING_REVIEW, principal.getId(), role.name(), null);
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
            case "override" -> {
                requirePerm(role, Permission.OVERRIDE_STATE);
                EntryState target;
                try {
                    target = EntryState.valueOf(str(p.get("targetState")));
                } catch (Exception e) {
                    throw ApiException.unprocessable("Target state is required.");
                }
                if (comment.isEmpty()) {
                    throw ApiException.unprocessable("Emergency overrides require a mandatory reason.");
                }
                transition(sub, target, principal.getId(), role.name(), "EMERGENCY OVERRIDE: " + comment);
                if (target == EntryState.VALIDATED) {
                    sub.setValidatedBy(principal.getId());
                    sub.setValidatedAt(Instant.now());
                    sub.setRewardEligible(false);
                }
                if (target == EntryState.REJECTED) {
                    sub.setRejectionReason(comment);
                }
                submissionRepository.save(sub);
                yield okState(sub);
            }
            case "assign" -> {
                requirePerm(role, Permission.ASSIGN_ENTRIES);
                String assignTo = blankToNull(str(p.get("assignTo")));
                sub.setAssignedTo(assignTo);
                sub.setLastUpdatedBy(principal.getId());
                sub.setUpdatedAt(Instant.now());
                submissionRepository.save(sub);
                auditService.record(principal, "ENTRY_ASSIGNED", AuditTargetType.SUBMISSION, sub.getId(),
                        "Assigned to " + (assignTo != null ? assignTo : "(unassigned)"));
                yield Map.of("ok", true);
            }
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
        if (!isModerator && configService.getConfig().isCaptchaEnabled() && !bool(p.get("captchaPassed"))) {
            errors.put("captcha", "Please complete the CAPTCHA verification.");
        }
        EntryInput in = validateEntry(p, errors);
        if (!errors.isEmpty()) {
            throw ApiException.unprocessable(errors);
        }
        List<String> changed = changedFields(sub, in);
        if (changed.isEmpty()) {
            throw ApiException.unprocessable(Map.of("form",
                    "No changes detected. Edit the entry to address the reviewer's feedback, "
                            + "or dispute the decision instead."));
        }
        checkDuplicates(in, sub.getId(), bool(p.get("confirmNearDuplicate")));

        applyEntry(sub, in);
        String comment = "Corrected and resubmitted by contributor. Changed: " + String.join(", ", changed) + ".";
        transition(sub, target, principal.getId(), principal.getRole().name(), comment);
        auditService.record(principal, "SUBMISSION_RESUBMITTED", AuditTargetType.SUBMISSION, sub.getId(),
                "\"" + sub.getWrittenForm() + "\" " + from.name() + " → " + target.name());
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
                actions.add(ownerAction("SUBMIT_DRAFT", "Submit for review", EntryState.PENDING_REVIEW));
                actions.add(ownerAction("DELETE_DRAFT", "Delete draft", null));
            }
            case FLAGGED, C_FLAGGED -> {
                actions.add(ownerAction("RESUBMIT", "Resubmission", resubmitTarget(sub.getState())));
                if (PermissionMatrix.has(principal.getRole(), Permission.DISPUTE)) {
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

    public static EntryState resubmitTarget(EntryState state) {
        return switch (state) {
            case FLAGGED -> EntryState.PENDING_REVIEW;
            case C_FLAGGED -> EntryState.EDITOR_APPROVED;
            default -> null;
        };
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

    /** Parsed Quick Input fields; relation lists are normalised to rows with a target. */
    private record EntryInput(
            String word,
            PartOfSpeech pos,
            SynsetAction action,
            String synsetId,
            String iliCode,
            String definitionText,
            DefinitionLanguage definitionLanguage,
            String reviewerNote,
            List<SenseRelationPayload> senseRelations,
            List<SynsetRelationPayload> synsetRelations) {}

    /** Field validation shared by new entries and resubmissions; problems are added to {@code errors}. */
    private EntryInput validateEntry(Map<String, Object> p, Map<String, String> errors) {
        String word = str(p.get("writtenForm")).trim();
        if (word.isEmpty()) {
            errors.put("writtenForm", "Bengali word is required.");
        } else if (!BENGALI.matcher(word).find()) {
            errors.put("writtenForm", "Must contain at least one Bengali Unicode character (U+0980–U+09FF).");
        } else if (word.length() > 25) {
            errors.put("writtenForm", "Maximum 25 characters allowed.");
        }

        PartOfSpeech pos = null;
        try {
            pos = PartOfSpeech.from(str(p.get("pos")));
        } catch (Exception e) {
            errors.put("pos", "Part of speech is required.");
        }

        SynsetAction action = null;
        try {
            action = SynsetAction.from(str(p.get("synsetAction")));
        } catch (Exception e) {
            errors.put("synsetAction", "Choose to link an existing synset or create a new one.");
        }

        String synsetId = null;
        String iliCode = null;
        if (action == SynsetAction.LINK) {
            synsetId = str(p.get("synsetId")).trim();
            SynsetEntity syn = synsetId.isEmpty() ? null : synsetRepository.findById(synsetId).orElse(null);
            if (syn == null) {
                errors.put("synsetId", "Synset ID must match an existing synset.");
            } else if (pos != null && syn.getPartOfSpeech() != pos) {
                errors.put("synsetId",
                        "POS mismatch: selected synset is \"" + syn.getPartOfSpeech().getCode()
                                + "\" but the lemma is \"" + pos.getCode() + "\".");
            } else if (pos != null && !word.isEmpty()
                    && senseRepository.existsByLemmaPosAndSynset(word, pos, syn.getId())) {
                errors.put("duplicate",
                        "Exact duplicate: \"" + word + "\" (" + pos.getCode()
                                + ") is already linked to synset " + syn.getId() + " in the lexicon.");
            }
        } else if (action == SynsetAction.CREATE) {
            iliCode = LexiconValidationService.normalizeIli(str(p.get("iliCode")));
            if (LexiconValidationService.isNa(iliCode) || "n/a".equals(iliCode)) {
                iliCode = "n/a";
            } else if (!ILI.matcher(iliCode).matches()) {
                errors.put("iliCode",
                        "ILI code must follow the format {8-digit-offset}-{n|v|a|r}, e.g. 04192858-n — or select N/A.");
            } else if (pos != null && !iliCode.endsWith(pos.getCode())) {
                errors.put("iliCode", "ILI code POS suffix must match the selected part of speech.");
            } else if (synsetRepository.existsByIli(iliCode)) {
                errors.put("iliCode",
                        "This ILI code already has a Bengali synset — use “Link to existing synset” instead.");
            }
        }

        validateRelations(p, errors, action == SynsetAction.LINK ? synsetId : null);

        String definitionText = blankToNull(str(p.get("definitionText")).trim());
        DefinitionLanguage defLang = null;
        if (definitionText != null) {
            if (definitionText.length() > 500) {
                errors.put("definitionText", "Definition must be at most 500 characters.");
            }
            try {
                defLang = DefinitionLanguage.from(str(p.get("definitionLanguage")));
            } catch (Exception e) {
                defLang = DefinitionLanguage.BEN;
            }
            if (defLang == DefinitionLanguage.BEN && !BENGALI.matcher(definitionText).find()) {
                errors.put("definitionText", "Bengali definitions must contain Bengali script characters.");
            }
        }
        String reviewerNote = blankToNull(str(p.get("reviewerNote")).trim());
        if (reviewerNote != null && reviewerNote.length() > 500) {
            errors.put("reviewerNote", "Note must be at most 500 characters.");
        }

        List<SenseRelationPayload> senseRels = readSense(writeJson(p.get("senseRelations"))).stream()
                .filter(r -> r != null && r.relType() != null && r.target() != null && !r.target().isBlank())
                .map(r -> new SenseRelationPayload(r.relType(), r.target().trim()))
                .toList();
        List<SynsetRelationPayload> synsetRels = readSynset(writeJson(p.get("synsetRelations"))).stream()
                .filter(r -> r != null && r.relType() != null && r.target() != null && !r.target().isBlank())
                .map(r -> new SynsetRelationPayload(r.relType(), r.target().trim()))
                .toList();

        return new EntryInput(word, pos, action,
                action == SynsetAction.LINK ? synsetId : null,
                action == SynsetAction.CREATE ? iliCode : null,
                definitionText, definitionText != null ? defLang : null,
                reviewerNote, senseRels, synsetRels);
    }

    /**
     * Exact duplicates against other open submissions are blocked (409 {@code duplicate});
     * near duplicates need {@code confirmNearDuplicate}. {@code excludeId} skips the entry being edited.
     */
    private void checkDuplicates(EntryInput in, String excludeId, boolean confirmNear) {
        List<Submission> open = submissionRepository.findActiveByLemmaAndPos(in.word(), in.pos()).stream()
                .filter(s -> !s.getId().equals(excludeId))
                .toList();
        String targetIli = in.action() == SynsetAction.LINK
                ? synsetRepository.findById(in.synsetId()).map(SynsetEntity::getIli).orElse(null)
                : in.iliCode();
        for (Submission existing : open) {
            String existingIli = existing.getSynsetAction() == SynsetAction.LINK
                    ? (existing.getSynsetId() == null
                            ? null
                            : synsetRepository.findById(existing.getSynsetId()).map(SynsetEntity::getIli).orElse(null))
                    : existing.getIliCode();
            if (sameConcept(targetIli, existingIli, in.synsetId(), existing.getSynsetId())) {
                throw ApiException.conflict(Map.of(
                        "duplicate",
                        "Exact duplicate blocked: \"" + in.word() + "\" (" + in.pos().getCode()
                                + ") is already linked to the same concept (submission " + existing.getId() + ")."));
            }
        }
        boolean lexiconNear = senseRepository.existsByLemmaAndPos(in.word(), in.pos());
        if ((lexiconNear || !open.isEmpty()) && !confirmNear) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("nearDuplicate",
                    "\"" + in.word() + "\" (" + in.pos().getCode()
                            + ") already exists with a different concept in the lexicon or an open submission. "
                            + "Confirm to proceed — this may be legitimate polysemy.");
            throw new NearDuplicateException(body);
        }
    }

    private void applyEntry(Submission sub, EntryInput in) {
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
    private List<String> changedFields(Submission sub, EntryInput in) {
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
        submissionRepository.save(sub);
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

    @Transactional
    public void markValidated(String submissionId, String actorId) {
        Submission sub = require(submissionId);
        sub.setValidatedBy(actorId);
        sub.setValidatedAt(Instant.now());
        submissionRepository.save(sub);
    }

    private void validateRelations(Map<String, Object> p, Map<String, String> errors, String linkedSynsetId) {
        List<SenseRelationPayload> senseRels = readSense(writeJson(p.get("senseRelations")));
        if (senseRels.size() > 20) {
            errors.put("senseRelations", "At most 20 sense relations are allowed per entry.");
        } else {
            Set<String> seen = new java.util.HashSet<>();
            int i = 0;
            for (SenseRelationPayload rel : senseRels) {
                i++;
                if (rel == null || rel.relType() == null || rel.target() == null || rel.target().isBlank()) {
                    continue;
                }
                String target = rel.target().trim();
                if (!SENSE_REL_TYPES.contains(rel.relType())) {
                    errors.put("senseRelations", "Invalid sense relation type: " + rel.relType());
                    break;
                }
                if (!seen.add(rel.relType() + "|" + target)) {
                    errors.put("senseRelations", "Sense relation #" + i + " is a duplicate of an earlier row.");
                    break;
                }
                if (!senseRepository.existsById(target)) {
                    errors.put("senseRelations",
                            "Sense relation #" + i + " target was not found. Search and pick an existing word sense.");
                    break;
                }
            }
        }

        List<SynsetRelationPayload> synRels = readSynset(writeJson(p.get("synsetRelations")));
        if (synRels.size() > 20) {
            errors.put("synsetRelations", "At most 20 synset relations are allowed per entry.");
            return;
        }
        Set<String> seen = new java.util.HashSet<>();
        int i = 0;
        for (SynsetRelationPayload rel : synRels) {
            i++;
            if (rel == null || rel.relType() == null || rel.target() == null || rel.target().isBlank()) {
                continue;
            }
            String target = rel.target().trim();
            if (!SYNSET_REL_TYPES.contains(rel.relType())) {
                errors.put("synsetRelations", "Invalid synset relation type: " + rel.relType());
                break;
            }
            if (target.equals(linkedSynsetId)) {
                errors.put("synsetRelations",
                        "Synset relation #" + i + " points to the same synset this word is linked to.");
                break;
            }
            if (!seen.add(rel.relType() + "|" + target)) {
                errors.put("synsetRelations", "Synset relation #" + i + " is a duplicate of an earlier row.");
                break;
            }
            if (!synsetRepository.existsById(target)) {
                errors.put("synsetRelations",
                        "Synset relation #" + i + " target was not found. Search and pick an existing concept.");
                break;
            }
        }
    }

    private static boolean sameConcept(
            String iliA, String iliB, String synsetIdA, String synsetIdB) {
        if (synsetIdA != null && synsetIdA.equals(synsetIdB)) {
            return true;
        }
        if (iliA == null || iliB == null) {
            return false;
        }
        if (LexiconValidationService.isNa(iliA) || LexiconValidationService.isNa(iliB)) {
            return false;
        }
        return iliA.equalsIgnoreCase(iliB);
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
