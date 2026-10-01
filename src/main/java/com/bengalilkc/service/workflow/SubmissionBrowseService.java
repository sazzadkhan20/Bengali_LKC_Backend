package com.bengalilkc.service.workflow;

import com.bengalilkc.common.SenseRelationPayload;
import com.bengalilkc.common.SynsetRelationPayload;
import com.bengalilkc.common.exception.ApiException;
import com.bengalilkc.domain.entity.identity.AppUser;
import com.bengalilkc.domain.entity.lexicon.SynsetEntity;
import com.bengalilkc.domain.entity.workflow.ArbitrationCase;
import com.bengalilkc.domain.entity.workflow.Submission;
import com.bengalilkc.domain.enums.ArbitrationStatus;
import com.bengalilkc.domain.enums.EntryState;
import com.bengalilkc.domain.enums.PartOfSpeech;
import com.bengalilkc.domain.enums.Permission;
import com.bengalilkc.domain.enums.Role;
import com.bengalilkc.repository.identity.AppUserRepository;
import com.bengalilkc.repository.lexicon.SenseRepository;
import com.bengalilkc.repository.lexicon.SynsetRepository;
import com.bengalilkc.repository.lexicon.WordnetIliRepository;
import com.bengalilkc.repository.workflow.ArbitrationCaseRepository;
import com.bengalilkc.security.PermissionMatrix;
import com.bengalilkc.security.UserPrincipal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Paged browse/search over user submissions (never the imported XML lexicon).
 * One endpoint serves every role; filters a role may not choose are forced server-side.
 */
@Service
@RequiredArgsConstructor
public class SubmissionBrowseService {

    public static final int DEFAULT_SIZE = 15;
    public static final int MAX_SIZE = 100;
    private static final int MAX_QUERY_LENGTH = 100;
    private static final int MAX_MEMBERS = 12;

    private final NamedParameterJdbcTemplate jdbc;
    private final SubmissionService submissionService;
    private final AppUserRepository userRepository;
    private final SynsetRepository synsetRepository;
    private final ArbitrationCaseRepository caseRepository;
    private final SenseRepository senseRepository;
    private final WordnetIliRepository wordnetIliRepository;
    private final ReviewService reviewService;

    /**
     * Full submission for the detail page, with display names, the linked synset,
     * related arbitration cases and (for assigners) the reviewer list.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> detail(UserPrincipal principal, String id) {
        Submission sub = submissionService.require(id);
        boolean isOwner = sub.getContributorId().equals(principal.getId());
        boolean reviewer = switch (principal.getRole()) {
            case PLATFORM_ARCHITECT, SYSTEM_MODERATOR, LEXICAL_EDITOR, CHIEF_LEXICAL_EDITOR, ARBITRATION_CURATOR -> true;
            default -> false;
        };
        if (!isOwner && !reviewer) {
            throw ApiException.forbidden("You can only view your own submissions.");
        }
        String hidden = isOwner ? null : assignedElsewhere(principal, sub);
        if (hidden != null) {
            throw ApiException.forbidden(hidden);
        }

        Map<String, Object> m = new LinkedHashMap<>(submissionService.toDto(sub));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> history = (List<Map<String, Object>>) m.get("history");
        Set<String> userIds = new HashSet<>();
        Stream.of(sub.getContributorId(), sub.getAssignedTo(), sub.getAssignedChief(), sub.getValidatedBy(),
                        sub.getLastUpdatedBy())
                .filter(Objects::nonNull)
                .forEach(userIds::add);
        history.stream().map(h -> (String) h.get("actorId")).filter(Objects::nonNull).forEach(userIds::add);
        Map<String, Object> users = new LinkedHashMap<>();
        for (AppUser u : userRepository.findAllById(userIds)) {
            users.put(u.getId(), Map.of("name", u.getName(), "role", u.getRole().name()));
        }
        m.put("users", users);

        // Each history step with the actor's name and how long the entry sat in the previous state.
        Instant previous = null;
        for (Map<String, Object> h : history) {
            @SuppressWarnings("unchecked")
            Map<String, Object> actor = (Map<String, Object>) users.get((String) h.get("actorId"));
            h.put("actorName", actor != null ? actor.get("name") : null);
            Instant at = h.get("timestamp") != null ? Instant.parse((String) h.get("timestamp")) : null;
            h.put("secondsInPreviousState",
                    previous != null && at != null ? Duration.between(previous, at).getSeconds() : null);
            previous = at != null ? at : previous;
        }
        m.put("currentStateSince", previous != null ? previous.toString() : m.get("updatedAt"));
        history.stream()
                .filter(h -> EntryState.PENDING_REVIEW.name().equals(h.get("to")))
                .findFirst()
                .ifPresent(h -> m.put("submittedForReviewAt", h.get("timestamp")));

        String ili = sub.getIliCode();
        if (sub.getSynsetId() != null) {
            SynsetEntity sy = synsetRepository.findById(sub.getSynsetId()).orElse(null);
            if (sy != null) {
                Map<String, Object> s = new LinkedHashMap<>();
                s.put("id", sy.getId());
                s.put("ili", sy.getIli());
                s.put("pos", sy.getPartOfSpeech() != null ? sy.getPartOfSpeech().getCode() : null);
                s.put("definitionText", sy.getDefinitionText());
                s.put("definitionLanguage",
                        sy.getDefinitionLanguage() != null ? sy.getDefinitionLanguage().getCode() : null);
                s.put("members", lemmasBySynset(List.of(sy.getId())).getOrDefault(sy.getId(), List.of()));
                m.put("synset", s);
                ili = sy.getIli();
            }
        }
        if (ili != null && !ili.isBlank() && !"n/a".equalsIgnoreCase(ili)) {
            wordnetIliRepository.findById(ili).ifPresent(w -> {
                Map<String, Object> i = new LinkedHashMap<>();
                i.put("ili", w.getIli());
                i.put("pos", w.getPos() != null ? w.getPos().getCode() : null);
                i.put("words", w.getWordsCsv());
                i.put("gloss", w.getGloss());
                m.put("iliInfo", i);
            });
        }

        @SuppressWarnings("unchecked")
        List<SenseRelationPayload> senseRels = (List<SenseRelationPayload>) m.get("senseRelations");
        @SuppressWarnings("unchecked")
        List<SynsetRelationPayload> synsetRels = (List<SynsetRelationPayload>) m.get("synsetRelations");
        m.put("senseRelationDetails", senseRelationDetails(senseRels));
        m.put("synsetRelationDetails", synsetRelationDetails(synsetRels));

        var cases = caseRepository.findBySubmissionId(sub.getId());
        Map<String, Object> ownerActions = submissionService.ownerOptions(principal, sub, cases);
        if (ownerActions != null) {
            m.put("ownerActions", ownerActions);
        }
        m.put("lifecycle", submissionService.lifecycle(principal, sub));
        ArbitrationCase activeCase = sub.getState() == EntryState.ARBITRATION
                ? cases.stream()
                        .filter(c -> c.getStatus() != ArbitrationStatus.RESOLVED)
                        .max(Comparator.comparing(ArbitrationCase::getOpenedAt,
                                Comparator.nullsFirst(Comparator.naturalOrder())))
                        .orElse(null)
                : null;
        boolean otherStagesDispute = activeCase != null
                && !ownsArbitrationStage(principal.getRole(), preArbitrationState(activeCase, history));
        m.put("cases", cases.stream()
                .filter(c -> !(otherStagesDispute && c == activeCase))
                .map(c -> {
                    Map<String, Object> cm = new LinkedHashMap<>();
                    cm.put("id", c.getId());
                    cm.put("status", c.getStatus().name());
                    cm.put("disputeType", c.getDisputeType() != null ? c.getDisputeType().name() : null);
                    cm.put("openedAt", c.getOpenedAt() != null ? c.getOpenedAt().toString() : null);
                    cm.put("finalVerdict", c.getFinalVerdict() != null ? c.getFinalVerdict().name() : null);
                    return cm;
                })
                .toList());

        if (PermissionMatrix.has(principal.getRole(), Permission.ASSIGN_ENTRIES)) {
            List<Map<String, Object>> reviewers = new ArrayList<>();
            for (Role r : List.of(Role.LEXICAL_EDITOR, Role.CHIEF_LEXICAL_EDITOR)) {
                for (AppUser u : userRepository.findByRoleAndActiveTrueAndSuspendedFalse(r)) {
                    reviewers.add(Map.of("id", u.getId(), "name", u.getName(), "role", r.name()));
                }
            }
            m.put("reviewers", reviewers);
        }
        Map<String, Object> review = otherStagesDispute ? null : reviewService.options(principal, sub);
        if (review != null) {
            m.put("review", review);
        }
        return m;
    }

    /**
     * An arbitration belongs to the review stage it came from: PENDING_REVIEW / FLAGGED to the Lexical
     * Editor, EDITOR_APPROVED / C_FLAGGED to the Chief Lexical Editor. Other roles, and disputes from
     * any other state, are not restricted.
     */
    private static boolean ownsArbitrationStage(Role role, EntryState preState) {
        if (preState == null || (role != Role.LEXICAL_EDITOR && role != Role.CHIEF_LEXICAL_EDITOR)) {
            return true;
        }
        return switch (preState) {
            case PENDING_REVIEW, FLAGGED -> role == Role.LEXICAL_EDITOR;
            case EDITOR_APPROVED, C_FLAGGED -> role == Role.CHIEF_LEXICAL_EDITOR;
            default -> true;
        };
    }

    /** The case's recorded pre-arbitration state, else the state the entry left when it entered ARBITRATION. */
    private static EntryState preArbitrationState(ArbitrationCase c, List<Map<String, Object>> history) {
        if (c.getPreArbitrationState() != null) {
            return c.getPreArbitrationState();
        }
        for (int i = history.size() - 1; i >= 0; i--) {
            Map<String, Object> h = history.get(i);
            if (EntryState.ARBITRATION.name().equals(h.get("to")) && h.get("from") != null) {
                return EntryState.valueOf((String) h.get("from"));
            }
        }
        return null;
    }

    /** Sense-relation rows resolved to the target word, its POS and its synset gloss. */
    private List<Map<String, Object>> senseRelationDetails(List<SenseRelationPayload> rels) {
        if (rels == null || rels.isEmpty()) {
            return List.of();
        }
        Map<String, Object[]> byId = new HashMap<>();
        for (Object[] row : senseRepository.findSummariesByIds(
                rels.stream().map(SenseRelationPayload::target).filter(Objects::nonNull).toList())) {
            byId.put((String) row[0], row);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (SenseRelationPayload r : rels) {
            Object[] row = byId.get(r.target());
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("relType", r.relType());
            d.put("target", r.target());
            d.put("found", row != null);
            d.put("writtenForm", row != null ? row[1] : null);
            d.put("pos", row != null && row[2] instanceof PartOfSpeech p ? p.getCode() : null);
            d.put("synsetId", row != null ? row[3] : null);
            d.put("definition", row != null ? row[4] : null);
            out.add(d);
        }
        return out;
    }

    /** Synset-relation rows resolved to the target synset's gloss and member words. */
    private List<Map<String, Object>> synsetRelationDetails(List<SynsetRelationPayload> rels) {
        if (rels == null || rels.isEmpty()) {
            return List.of();
        }
        List<String> ids = rels.stream().map(SynsetRelationPayload::target).filter(Objects::nonNull).toList();
        Map<String, SynsetEntity> byId = new HashMap<>();
        for (SynsetEntity s : synsetRepository.findAllById(ids)) {
            byId.put(s.getId(), s);
        }
        Map<String, List<String>> members = lemmasBySynset(byId.keySet());
        List<Map<String, Object>> out = new ArrayList<>();
        for (SynsetRelationPayload r : rels) {
            SynsetEntity s = byId.get(r.target());
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("relType", r.relType());
            d.put("target", r.target());
            d.put("found", s != null);
            d.put("pos", s != null && s.getPartOfSpeech() != null ? s.getPartOfSpeech().getCode() : null);
            d.put("ili", s != null ? s.getIli() : null);
            d.put("definition", s != null ? s.getDefinitionText() : null);
            d.put("members", s != null ? members.getOrDefault(s.getId(), List.of()) : List.of());
            out.add(d);
        }
        return out;
    }

    private Map<String, List<String>> lemmasBySynset(Collection<String> synsetIds) {
        Map<String, List<String>> out = new HashMap<>();
        if (synsetIds.isEmpty()) {
            return out;
        }
        for (Object[] row : senseRepository.findLemmasBySynsetIds(synsetIds)) {
            List<String> words = out.computeIfAbsent((String) row[0], k -> new ArrayList<>());
            if (words.size() < MAX_MEMBERS) {
                words.add((String) row[1]);
            }
        }
        return out;
    }

    public record Criteria(
            String q, String state, String assignedTo, String contributorId, Integer page, Integer size) {}

    @Transactional(readOnly = true)
    public Map<String, Object> browse(UserPrincipal principal, Criteria c) {
        Role role = principal.getRole();
        String me = principal.getId();

        EntryState state = parseState(c.state());
        String assignedTo = resolveUser(c.assignedTo(), me);
        String contributorId = resolveUser(c.contributorId(), me);

        switch (role) {
            case PLATFORM_ARCHITECT, SYSTEM_MODERATOR -> { }
            case LEXICAL_EDITOR -> {
                state = EntryState.PENDING_REVIEW;
                assignedTo = assignedTo != null ? me : null;
                contributorId = null;
            }
            case CHIEF_LEXICAL_EDITOR -> {
                state = EntryState.EDITOR_APPROVED;
                assignedTo = assignedTo != null ? me : null;
                contributorId = null;
            }
            case CONTRIBUTOR -> {
                contributorId = me;
                assignedTo = null;
            }
            default -> throw ApiException.forbidden("Your role cannot browse submissions.");
        }

        int size = c.size() == null ? DEFAULT_SIZE : Math.max(1, Math.min(MAX_SIZE, c.size()));
        int page = c.page() == null ? 0 : Math.max(0, c.page());
        String q = normalizeQuery(c.q());

        StringBuilder where = new StringBuilder(" where 1=1");
        MapSqlParameterSource params = new MapSqlParameterSource();
        if (state != null) {
            where.append(" and s.state = :state");
            params.addValue("state", state.name());
        }
        // Each reviewer role has its own slot; managers filter across both.
        String slot = reviewerSlotColumn(role);
        if (slot != null) {
            // An entry assigned to another reviewer of the same role is theirs alone.
            where.append(" and (").append(slot).append(" is null or ").append(slot).append(" = :me)");
            params.addValue("me", me);
        }
        if (contributorId != null) {
            where.append(" and s.contributor_id = :contributorId");
            params.addValue("contributorId", contributorId);
        }
        if (q != null) {
            // Linked submissions carry no own definition, so also match the linked synset's gloss.
            where.append(" and (lower(s.written_form) like :like escape '!'")
                    .append(" or lower(s.definition_text) like :like escape '!'")
                    .append(" or s.synset_id in (select sy.id from synset sy")
                    .append(" where lower(sy.definition_text) like :like escape '!'))");
            params.addValue("like", "%" + escapeLike(q.toLowerCase(Locale.ROOT)) + "%");
        }

        Map<String, Object> scopeCounts = null;
        if (slot != null) {
            Map<String, Object> counts = jdbc.queryForMap("select count(*) as available, count(*) filter (where "
                    + slot + " = :me) as mine from submission s" + where, params);
            scopeCounts = new LinkedHashMap<>();
            scopeCounts.put("available", ((Number) counts.get("available")).longValue());
            scopeCounts.put("assignedToMe", ((Number) counts.get("mine")).longValue());
        }

        if ("unassigned".equals(assignedTo)) {
            where.append(slot != null ? " and " + slot + " is null"
                    : " and s.assigned_to is null and s.assigned_chief is null");
        } else if (assignedTo != null) {
            where.append(slot != null ? " and " + slot + " = :assignedTo"
                    : " and (s.assigned_to = :assignedTo or s.assigned_chief = :assignedTo)");
            params.addValue("assignedTo", assignedTo);
        }

        Map<String, Object> agg = jdbc.queryForMap(
                "select count(*) as total, max(s.updated_at) as last_updated from submission s" + where, params);
        long total = ((Number) agg.get("total")).longValue();
        Timestamp lastUpdated = (Timestamp) agg.get("last_updated");

        List<Map<String, Object>> items = List.of();
        if (total > (long) page * size) {
            params.addValue("limit", size);
            params.addValue("offset", (long) page * size);
            items = jdbc.query("""
                    select s.id, s.state, s.written_form, s.part_of_speech, s.synset_action, s.synset_id,
                           s.ili_code, s.definition_text, sy.definition_text as synset_definition,
                           s.contributor_id, cu.name as contributor_name,
                           s.assigned_to, au.name as assigned_to_name,
                           s.assigned_chief, ac.name as assigned_chief_name,
                           s.last_updated_by, lu.name as last_updated_by_name,
                           s.created_at, s.updated_at
                    from submission s
                    left join synset sy on sy.id = s.synset_id
                    left join app_user cu on cu.id = s.contributor_id
                    left join app_user au on au.id = s.assigned_to
                    left join app_user ac on ac.id = s.assigned_chief
                    left join app_user lu on lu.id = s.last_updated_by
                    """ + where + " order by s.updated_at desc, s.id desc limit :limit offset :offset",
                    params,
                    (rs, i) -> row(rs));
        }

        Map<String, Object> filters = new LinkedHashMap<>();
        filters.put("q", q);
        filters.put("state", state != null ? state.name() : null);
        filters.put("assignedTo", assignedTo);
        filters.put("contributorId", contributorId);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", items);
        body.put("page", page);
        body.put("size", size);
        body.put("totalElements", total);
        body.put("totalPages", (int) ((total + size - 1) / size));
        body.put("lastUpdatedAt", lastUpdated != null ? lastUpdated.toInstant().toString() : null);
        if (scopeCounts != null) {
            body.put("scopeCounts", scopeCounts);
        }
        body.put("filters", filters);
        return body;
    }

    private static String reviewerSlotColumn(Role role) {
        return switch (role) {
            case LEXICAL_EDITOR -> "s.assigned_to";
            case CHIEF_LEXICAL_EDITOR -> "s.assigned_chief";
            default -> null;
        };
    }

    /**
     * Error message when the entry sits in the caller's review stage but is assigned to another
     * reviewer of the same role; such entries are visible only to their assignee (and managers).
     */
    private static String assignedElsewhere(UserPrincipal principal, Submission sub) {
        String assignee;
        String roleLabel;
        switch (principal.getRole()) {
            case LEXICAL_EDITOR -> {
                if (sub.getState() != EntryState.PENDING_REVIEW) return null;
                assignee = sub.getAssignedTo();
                roleLabel = "Lexical Editor";
            }
            case CHIEF_LEXICAL_EDITOR -> {
                if (sub.getState() != EntryState.EDITOR_APPROVED) return null;
                assignee = sub.getAssignedChief();
                roleLabel = "Chief Lexical Editor";
            }
            default -> {
                return null;
            }
        }
        if (assignee == null || assignee.equals(principal.getId())) return null;
        return "This entry is assigned to another " + roleLabel
                + ". Only the assigned reviewer can open it while it is awaiting review.";
    }

    public record HistoryCriteria(String q, String state, Integer page, Integer size) {}

    /**
     * Current states that never appear in a reviewer's history: those entries are back in (or
     * not yet out of) a review queue, or are states the role never produces.
     */
    private static final Map<Role, Set<EntryState>> HISTORY_EXCLUDED = Map.of(
            Role.LEXICAL_EDITOR, Set.of(EntryState.DRAFT, EntryState.PENDING_REVIEW),
            Role.CHIEF_LEXICAL_EDITOR, Set.of(
                    EntryState.DRAFT, EntryState.PENDING_REVIEW, EntryState.EDITOR_APPROVED, EntryState.FLAGGED));

    /**
     * My Review History (Lexical Editor) / My Validation History (Chief Lexical Editor): entries whose
     * status the caller changed on someone else's submission, shown with their current state and the
     * caller's latest decision, newest decision first.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> history(UserPrincipal principal, HistoryCriteria c) {
        Set<EntryState> excluded = HISTORY_EXCLUDED.get(principal.getRole());
        if (excluded == null) {
            throw ApiException.forbidden("Only Lexical Editors and Chief Lexical Editors have a review history.");
        }
        List<String> available = Stream.of(EntryState.values())
                .filter(s -> !excluded.contains(s))
                .map(Enum::name)
                .toList();
        EntryState state = parseState(c.state());
        if (state != null && excluded.contains(state)) {
            throw ApiException.badRequest(Map.of("state", "This status is not part of your history."));
        }

        int size = c.size() == null ? DEFAULT_SIZE : Math.max(1, Math.min(MAX_SIZE, c.size()));
        int page = c.page() == null ? 0 : Math.max(0, c.page());
        String q = normalizeQuery(c.q());

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("me", principal.getId())
                .addValue("available", available);
        StringBuilder where = new StringBuilder(" where s.state in (:available)");
        if (state != null) {
            where.append(" and s.state = :state");
            params.addValue("state", state.name());
        }
        if (q != null) {
            where.append(" and (lower(s.written_form) like :like escape '!'")
                    .append(" or lower(s.definition_text) like :like escape '!'")
                    .append(" or s.synset_id in (select sy.id from synset sy")
                    .append(" where lower(sy.definition_text) like :like escape '!'))");
            params.addValue("like", "%" + escapeLike(q.toLowerCase(Locale.ROOT)) + "%");
        }
        String mine = """
                with mine as (
                    select t.submission_id, max(t.created_at) as my_last_at
                    from state_transition t
                    join submission own on own.id = t.submission_id
                    where t.actor_id = :me and t.from_state is not null and own.contributor_id <> :me
                    group by t.submission_id
                )
                """;

        Map<String, Object> agg = jdbc.queryForMap(mine
                + "select count(*) as total, max(s.updated_at) as last_updated"
                + " from mine m join submission s on s.id = m.submission_id" + where, params);
        long total = ((Number) agg.get("total")).longValue();
        Timestamp lastUpdated = (Timestamp) agg.get("last_updated");

        List<Map<String, Object>> items = List.of();
        if (total > (long) page * size) {
            params.addValue("limit", size);
            params.addValue("offset", (long) page * size);
            items = jdbc.query(mine + """
                    select s.id, s.state, s.written_form, s.part_of_speech, s.synset_action, s.synset_id,
                           s.ili_code, s.definition_text, sy.definition_text as synset_definition,
                           s.contributor_id, cu.name as contributor_name,
                           s.assigned_to, au.name as assigned_to_name,
                           s.assigned_chief, ac.name as assigned_chief_name,
                           s.last_updated_by, lu.name as last_updated_by_name,
                           s.created_at, s.updated_at
                    from mine m
                    join submission s on s.id = m.submission_id
                    left join synset sy on sy.id = s.synset_id
                    left join app_user cu on cu.id = s.contributor_id
                    left join app_user au on au.id = s.assigned_to
                    left join app_user ac on ac.id = s.assigned_chief
                    left join app_user lu on lu.id = s.last_updated_by
                    """ + where + " order by m.my_last_at desc, s.id desc limit :limit offset :offset",
                    params,
                    (rs, i) -> row(rs));
        }

        Map<String, Object> filters = new LinkedHashMap<>();
        filters.put("q", q);
        filters.put("state", state != null ? state.name() : null);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", items);
        body.put("page", page);
        body.put("size", size);
        body.put("totalElements", total);
        body.put("totalPages", (int) ((total + size - 1) / size));
        body.put("lastUpdatedAt", lastUpdated != null ? lastUpdated.toInstant().toString() : null);
        body.put("availableStates", available);
        body.put("filters", filters);
        return body;
    }

    private static Map<String, Object> row(ResultSet rs) throws SQLException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", rs.getString("id"));
        m.put("state", rs.getString("state"));
        m.put("writtenForm", rs.getString("written_form"));
        m.put("pos", rs.getString("part_of_speech"));
        m.put("synsetAction", rs.getString("synset_action"));
        m.put("synsetId", rs.getString("synset_id"));
        m.put("iliCode", rs.getString("ili_code"));
        String own = rs.getString("definition_text");
        m.put("definition", own != null ? own : rs.getString("synset_definition"));
        m.put("contributorId", rs.getString("contributor_id"));
        m.put("contributorName", rs.getString("contributor_name"));
        m.put("assignedTo", rs.getString("assigned_to"));
        m.put("assignedToName", rs.getString("assigned_to_name"));
        m.put("assignedChief", rs.getString("assigned_chief"));
        m.put("assignedChiefName", rs.getString("assigned_chief_name"));
        m.put("lastUpdatedBy", rs.getString("last_updated_by"));
        m.put("lastUpdatedByName", rs.getString("last_updated_by_name"));
        m.put("createdAt", instant(rs.getTimestamp("created_at")));
        m.put("updatedAt", instant(rs.getTimestamp("updated_at")));
        return m;
    }

    private static String instant(Timestamp ts) {
        return ts != null ? ts.toInstant().toString() : null;
    }

    private static EntryState parseState(String raw) {
        if (raw == null || raw.isBlank() || "ALL".equalsIgnoreCase(raw.trim())) {
            return null;
        }
        try {
            return EntryState.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("Unknown state: " + raw);
        }
    }

    /** "me" means the caller; "unassigned" is kept as a marker; blank means no filter. */
    private static String resolveUser(String raw, String me) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String v = raw.trim();
        if ("me".equalsIgnoreCase(v)) {
            return me;
        }
        if ("unassigned".equalsIgnoreCase(v)) {
            return "unassigned";
        }
        if (v.length() > 64) {
            throw ApiException.badRequest("User id is too long.");
        }
        return v;
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
}
