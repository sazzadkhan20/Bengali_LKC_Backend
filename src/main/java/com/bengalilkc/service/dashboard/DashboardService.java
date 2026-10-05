package com.bengalilkc.service.dashboard;

import com.bengalilkc.domain.entity.admin.SystemConfig;
import com.bengalilkc.domain.enums.EntryState;
import com.bengalilkc.domain.enums.Role;
import com.bengalilkc.security.UserPrincipal;
import com.bengalilkc.service.admin.ConfigService;
import com.bengalilkc.service.lexicon.LexiconStatsService;
import com.bengalilkc.service.workflow.ArbitrationService;
import com.bengalilkc.service.workflow.QuotaService;
import com.bengalilkc.service.workflow.TieCorrectionService;
import com.bengalilkc.service.admin.ApplicationService;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Role dashboards, built from SQL aggregates (a few cheap queries per dashboard, no entity graphs). */
@Service
@RequiredArgsConstructor
public class DashboardService {

    private static final DateTimeFormatter WEEK_LABEL = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH);

    private final NamedParameterJdbcTemplate jdbc;
    private final ConfigService configService;
    private final QuotaService quotaService;
    private final LexiconStatsService lexiconStatsService;
    private final TieCorrectionService tieCorrectionService;
    private final ApplicationService applicationService;

    @Transactional(readOnly = true)
    public Map<String, Object> forUser(UserPrincipal principal) {
        Role role = principal.getRole();
        Map<String, Object> body = switch (role) {
            case PLATFORM_ARCHITECT -> architect();
            case SYSTEM_MODERATOR -> moderator();
            case LEXICAL_EDITOR -> editor(principal.getId());
            case CHIEF_LEXICAL_EDITOR -> chief(principal.getId());
            case CONTRIBUTOR -> contributor(principal.getId());
            case ARBITRATION_CURATOR -> curator(principal.getId());
        };
        body.put("role", role.name());
        body.put("generatedAt", Instant.now().toString());
        return body;
    }

    /** Platform-wide statistics: the canonical lexicon plus the crowdsourcing pipeline. */
    @Transactional(readOnly = true)
    public Map<String, Object> statistics() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("lexicon", lexiconStatsService.stats());
        Map<String, Long> counts = stateCounts(null);
        m.put("counts", counts);
        m.put("totalSubmissions", counts.values().stream().mapToLong(Long::longValue).sum());
        m.put("submittedWeekly", weeklySeries(
                "select created_at as at from submission where state <> 'DRAFT'", null, 12));
        m.put("validatedWeekly", weeklySeries(
                "select created_at as at from state_transition where to_state = 'VALIDATED'", null, 12));
        m.put("contributors", count("""
                select count(distinct contributor_id) from submission where state <> 'DRAFT'
                """));
        m.put("topContributors", topContributors(36500, 10));
        Map<String, Object> rates = jdbc.queryForMap("""
                select count(*) filter (where to_state = 'VALIDATED') as validated,
                       count(*) filter (where to_state = 'REJECTED') as rejected,
                       count(*) filter (where to_state in ('FLAGGED', 'C_FLAGGED')) as flagged,
                       count(*) filter (where to_state = 'ARBITRATION') as escalated,
                       coalesce(avg(extract(epoch from t.created_at - s.created_at))
                                filter (where to_state = 'VALIDATED'), 0) as avg_validation_seconds
                from state_transition t join submission s on s.id = t.submission_id
                where t.from_state is not null
                """, new MapSqlParameterSource());
        Map<String, Object> outcomes = new LinkedHashMap<>();
        outcomes.put("validated", ((Number) rates.get("validated")).longValue());
        outcomes.put("rejected", ((Number) rates.get("rejected")).longValue());
        outcomes.put("flagged", ((Number) rates.get("flagged")).longValue());
        outcomes.put("escalated", ((Number) rates.get("escalated")).longValue());
        outcomes.put("averageValidationHours",
                Math.round(((Number) rates.get("avg_validation_seconds")).doubleValue() / 3600.0));
        m.put("outcomes", outcomes);
        m.put("cases", numbers(jdbc.queryForMap("""
                select count(*) filter (where status <> 'RESOLVED') as open,
                       count(*) filter (where status = 'RESOLVED') as resolved,
                       count(*) filter (where final_verdict = 'CONTRIBUTOR') as contributor_upheld,
                       count(*) filter (where final_verdict = 'REVIEWER') as reviewer_upheld
                from arbitration_case
                """, new MapSqlParameterSource())));
        m.put("generatedAt", Instant.now().toString());
        return m;
    }

    // ------------------------------------------------------------------ roles

    private Map<String, Object> architect() {
        Map<String, Object> m = new LinkedHashMap<>();
        Map<String, Long> counts = stateCounts(null);
        m.put("counts", counts);
        m.put("totalSubmissions", counts.values().stream().mapToLong(Long::longValue).sum());
        Map<String, Object> users = jdbc.queryForMap("""
                select count(*) filter (where u.suspended) as suspended,
                       count(*) filter (where not u.active) as inactive,
                       count(*) as total,
                       (select count(*) from credential c where c.one_time and not c.deactivated) as pending_first_login,
                       (select count(distinct r.user_id) from refresh_token r
                         where not r.revoked and r.expires_at > now()) as signed_in
                from app_user u
                """, new MapSqlParameterSource());
        m.put("users", numbers(users));
        m.put("stuckUnderReview", count(
                "select count(*) from submission where state = 'UNDER_REVIEW' and updated_at < now() - interval '48 hours'"));
        m.put("lexicon", lexiconCounts());
        m.put("lastExport", lastExport());
        m.put("newApplications", applicationService.countNew());
        m.put("topContributors", topContributors(30, 8));
        m.put("recentActivity", recentAudit(20, null));
        return m;
    }

    private Map<String, Object> moderator() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("counts", stateCounts(null));
        m.put("underReviewOld", count(
                "select count(*) from submission where state = 'UNDER_REVIEW' and updated_at < now() - interval '30 days'"));
        m.put("openCases", cases("c.status <> 'RESOLVED'", null, 8));
        m.put("ties", cases("c.status = 'TIE_PENDING'", null, 20));
        m.put("recentCorrections", tieCorrectionService.recent(5));
        m.put("weekly", weeklySeries("select created_at as at from submission where state <> 'DRAFT'", null, 5));
        m.put("recentActivity", recentAudit(8, List.of("SUBMISSION", "ARBITRATION", "USER")));
        SystemConfig cfg = configService.getConfig();
        m.put("anomalyThresholdPerHour", cfg.getAnomalyThresholdPerHour());
        m.put("anomalies", anomalies(cfg.getAnomalyThresholdPerHour()));
        m.put("autoSuspensions", jdbc.query("""
                select a.target_id as user_id, coalesce(u.name, a.target_id) as name, u.suspended, a.timestamp, a.detail
                from audit_event a left join app_user u on u.id = a.target_id
                where a.action = :action and a.timestamp > now() - interval '7 days'
                order by a.timestamp desc limit 10
                """, new MapSqlParameterSource("action", QuotaService.ACCOUNT_AUTO_SUSPENDED), (rs, i) -> {
                    Map<String, Object> s = new LinkedHashMap<>();
                    s.put("userId", rs.getString("user_id"));
                    s.put("name", rs.getString("name"));
                    s.put("stillSuspended", rs.getBoolean("suspended"));
                    s.put("at", iso(rs.getTimestamp("timestamp")));
                    s.put("detail", rs.getString("detail"));
                    return s;
                }));
        return m;
    }

    /** Accounts in an active burst (at or above the hourly threshold) or alerted in the last 24 hours. */
    private List<Map<String, Object>> anomalies(int threshold) {
        return jdbc.query("""
                with alerts as (
                  select target_id as user_id, max(timestamp) as last_alert, count(*) as alerts
                  from audit_event
                  where action = :action and timestamp > now() - interval '24 hours'
                  group by target_id
                ), bursts as (
                  select contributor_id as user_id from submission
                  where created_at > now() - interval '60 minutes'
                  group by contributor_id having count(*) >= :threshold
                ), ids as (select user_id from alerts union select user_id from bursts)
                select u.id, u.name, u.role, u.suspended, a.last_alert, coalesce(a.alerts, 0) as alerts,
                  (select count(*) from submission s
                    where s.contributor_id = u.id and s.created_at > now() - interval '60 minutes') as last_hour,
                  (select count(*) from submission s
                    where s.contributor_id = u.id and s.state = 'UNDER_REVIEW') as held
                from ids join app_user u on u.id = ids.user_id
                left join alerts a on a.user_id = u.id
                order by last_hour desc, a.last_alert desc nulls last
                limit 20
                """, new MapSqlParameterSource("action", QuotaService.ANOMALY_DETECTED).addValue("threshold", threshold),
                (rs, i) -> {
                    Map<String, Object> a = new LinkedHashMap<>();
                    a.put("userId", rs.getString("id"));
                    a.put("name", rs.getString("name"));
                    a.put("role", rs.getString("role"));
                    a.put("suspended", rs.getBoolean("suspended"));
                    long lastHour = rs.getLong("last_hour");
                    a.put("lastHour", lastHour);
                    a.put("active", lastHour >= threshold);
                    a.put("held", rs.getLong("held"));
                    a.put("alerts", rs.getLong("alerts"));
                    a.put("lastAlertAt", iso(rs.getTimestamp("last_alert")));
                    return a;
                });
    }

    private Map<String, Object> editor(String me) {
        Map<String, Object> m = new LinkedHashMap<>();
        MapSqlParameterSource p = new MapSqlParameterSource("me", me);
        String scope = "state = 'PENDING_REVIEW' and (assigned_to is null or assigned_to = :me)";
        m.put("queue", queueSummary(scope, "assigned_to", p));
        m.put("ageBuckets", ageBuckets(scope, p));
        m.put("thisWeek", decisionCounts(me, List.of("EDITOR_APPROVED", "FLAGGED", "REJECTED", "ARBITRATION")));
        m.put("recentDecisions", recentDecisions(me, 6));
        m.put("resubmitted", submissions("""
                s.state = 'PENDING_REVIEW' and (s.assigned_to is null or s.assigned_to = :me)
                and exists (select 1 from state_transition t where t.submission_id = s.id and t.from_state = 'FLAGGED')
                """, p, "s.updated_at asc", 8));
        m.put("myCases", cases("(c.reviewer_id = :me or c.claimant_id = :me)", p, 8));
        return m;
    }

    private Map<String, Object> chief(String me) {
        Map<String, Object> m = new LinkedHashMap<>();
        MapSqlParameterSource p = new MapSqlParameterSource("me", me);
        String scope = "state = 'EDITOR_APPROVED' and (assigned_chief is null or assigned_chief = :me)";
        m.put("queue", queueSummary(scope, "assigned_chief", p));
        m.put("thisWeek", decisionCounts(me, List.of("VALIDATED", "C_FLAGGED", "ARBITRATION")));
        m.put("validatedWeekly", weeklySeries(
                "select created_at as at from state_transition where to_state = 'VALIDATED'", null, 8));
        m.put("recentDecisions", recentDecisions(me, 8));
        m.put("myCases", cases("(c.reviewer_id = :me or c.claimant_id = :me)", p, 8));
        m.put("queueItems", submissions(
                "s.state = 'EDITOR_APPROVED' and (s.assigned_chief is null or s.assigned_chief = :me)",
                p, "s.updated_at asc", 8));
        return m;
    }

    private Map<String, Object> contributor(String me) {
        Map<String, Object> m = new LinkedHashMap<>();
        MapSqlParameterSource p = new MapSqlParameterSource("me", me);
        m.put("counts", stateCounts(me));
        m.put("attention", submissions("s.contributor_id = :me and s.state in ('FLAGGED', 'C_FLAGGED')",
                p, "s.updated_at desc", 10));
        m.put("recent", submissions("s.contributor_id = :me", p, "s.updated_at desc", 10));
        m.put("myCases", cases("c.contributor_id = :me", p, 8));
        m.put("weekly", weeklySeries(
                "select created_at as at from submission where contributor_id = :me and state <> 'DRAFT'", p, 8));
        m.put("quota", quotaService.usage(me));
        return m;
    }

    private Map<String, Object> curator(String me) {
        Map<String, Object> m = new LinkedHashMap<>();
        MapSqlParameterSource p = new MapSqlParameterSource("me", me);
        m.put("activeCount", count("select count(*) from arbitration_case where status = 'ACTIVE'"));
        m.put("votedCount", count("select count(*) from arbitration_vote where curator_id = :me", p));
        List<Map<String, Object>> active = cases("c.status = 'ACTIVE'", null, 50);
        Set<String> votedIds = new HashSet<>(jdbc.queryForList(
                "select case_id from arbitration_vote where curator_id = :me", p, String.class));
        List<Map<String, Object>> awaiting = new ArrayList<>();
        for (Map<String, Object> c : active) {
            boolean voted = votedIds.contains((String) c.get("id"));
            c.put("votedByMe", voted);
            if (!voted && !Boolean.TRUE.equals(c.remove("excludesMe"))) {
                awaiting.add(c);
            } else {
                c.remove("excludesMe");
            }
        }
        m.put("awaitingCount", awaiting.size());
        m.put("active", active);
        m.put("responseWindow", cases("c.status = 'RESPONSE_WINDOW'", null, 10));
        m.put("ties", cases("c.status = 'TIE_PENDING'", null, 10));
        m.put("resolved", cases("c.status = 'RESOLVED'", null, 10));
        Map<String, Object> mine = jdbc.queryForMap("""
                select count(*) filter (where verdict = 'CONTRIBUTOR') as contributor,
                       count(*) filter (where verdict = 'REVIEWER') as reviewer
                from arbitration_vote where curator_id = :me
                """, p);
        m.put("myVotes", numbers(mine));
        int eligible = activeCurators().size();
        Map<String, Object> panel = new LinkedHashMap<>();
        panel.put("eligible", eligible);
        panel.put("quorum", ArbitrationService.quorum(eligible));
        m.put("panel", panel);
        return m;
    }

    // ---------------------------------------------------------------- helpers

    private Map<String, Long> stateCounts(String contributorId) {
        Map<String, Long> out = new LinkedHashMap<>();
        for (EntryState s : EntryState.values()) {
            out.put(s.name(), 0L);
        }
        MapSqlParameterSource p = new MapSqlParameterSource();
        String sql = "select state, count(*) as n from submission";
        if (contributorId != null) {
            sql += " where contributor_id = :me";
            p.addValue("me", contributorId);
        }
        jdbc.query(sql + " group by state", p, rs -> {
            out.put(rs.getString("state"), rs.getLong("n"));
        });
        return out;
    }

    private Map<String, Object> queueSummary(String scope, String slot, MapSqlParameterSource p) {
        Map<String, Object> row = jdbc.queryForMap("select count(*) as available, count(*) filter (where "
                + slot + " = :me) as mine, coalesce(avg(extract(epoch from now() - updated_at)), 0) as avg_age,"
                + " min(updated_at) as oldest from submission where " + scope, p);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("available", ((Number) row.get("available")).longValue());
        m.put("assignedToMe", ((Number) row.get("mine")).longValue());
        m.put("averageAgeHours", Math.round(((Number) row.get("avg_age")).doubleValue() / 3600.0));
        m.put("oldestAt", iso(row.get("oldest")));
        return m;
    }

    private List<Map<String, Object>> ageBuckets(String scope, MapSqlParameterSource p) {
        Map<String, Object> row = jdbc.queryForMap("""
                select count(*) filter (where updated_at > now() - interval '1 day') as d1,
                       count(*) filter (where updated_at <= now() - interval '1 day'
                                          and updated_at > now() - interval '3 days') as d3,
                       count(*) filter (where updated_at <= now() - interval '3 days'
                                          and updated_at > now() - interval '7 days') as d7,
                       count(*) filter (where updated_at <= now() - interval '7 days') as older
                from submission where """ + " " + scope, p);
        List<Map<String, Object>> out = new ArrayList<>();
        out.add(datum("Under 1 day", row.get("d1"), "#7e8c5b"));
        out.add(datum("1–3 days", row.get("d3"), "#4982a5"));
        out.add(datum("3–7 days", row.get("d7"), "#997c3b"));
        out.add(datum("Over 7 days", row.get("older"), "#99553b"));
        return out;
    }

    private Map<String, Long> decisionCounts(String me, List<String> toStates) {
        Map<String, Long> out = new LinkedHashMap<>();
        toStates.forEach(s -> out.put(s, 0L));
        jdbc.query("""
                select to_state, count(*) as n from state_transition
                where actor_id = :me and created_at > now() - interval '7 days' and from_state is not null
                group by to_state
                """, new MapSqlParameterSource("me", me), rs -> {
            if (out.containsKey(rs.getString("to_state"))) {
                out.put(rs.getString("to_state"), rs.getLong("n"));
            }
        });
        return out;
    }

    private List<Map<String, Object>> recentDecisions(String me, int limit) {
        return jdbc.query("""
                select t.submission_id, s.written_form, t.from_state, t.to_state, t.created_at
                from state_transition t join submission s on s.id = t.submission_id
                where t.actor_id = :me and t.from_state is not null
                order by t.created_at desc limit :limit
                """, new MapSqlParameterSource("me", me).addValue("limit", limit), (rs, i) -> {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("submissionId", rs.getString("submission_id"));
            d.put("writtenForm", rs.getString("written_form"));
            d.put("fromState", rs.getString("from_state"));
            d.put("toState", rs.getString("to_state"));
            d.put("at", iso(rs.getTimestamp("created_at")));
            return d;
        });
    }

    private List<Map<String, Object>> submissions(String where, MapSqlParameterSource p, String order, int limit) {
        MapSqlParameterSource params = p != null ? p : new MapSqlParameterSource();
        params.addValue("limit", limit);
        return jdbc.query("""
                select s.id, s.written_form, s.part_of_speech, s.state, s.updated_at, s.created_at,
                       s.editor_comment, s.cle_comment, s.contributor_id, u.name as contributor_name
                from submission s left join app_user u on u.id = s.contributor_id
                where """ + " " + where + " order by " + order + " limit :limit", params, (rs, i) -> {
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("id", rs.getString("id"));
            s.put("writtenForm", rs.getString("written_form"));
            s.put("pos", rs.getString("part_of_speech"));
            s.put("state", rs.getString("state"));
            s.put("contributorId", rs.getString("contributor_id"));
            s.put("contributorName", rs.getString("contributor_name"));
            s.put("feedback", "C_FLAGGED".equals(rs.getString("state"))
                    ? rs.getString("cle_comment") : rs.getString("editor_comment"));
            s.put("createdAt", iso(rs.getTimestamp("created_at")));
            s.put("updatedAt", iso(rs.getTimestamp("updated_at")));
            return s;
        });
    }

    /** Case summaries; tallies are counts only (individual verdicts stay hidden while voting is open). */
    private List<Map<String, Object>> cases(String where, MapSqlParameterSource p, int limit) {
        MapSqlParameterSource params = p != null ? p : new MapSqlParameterSource();
        params.addValue("limit", limit);
        Set<String> curators = activeCurators();
        String me = params.hasValue("me") ? (String) params.getValue("me") : null;
        return jdbc.query("""
                select c.id, c.submission_id, s.written_form, s.part_of_speech, c.status, c.origin, c.stage,
                       c.opened_at, c.response_deadline, c.voting_deadline, c.resolved_at, c.verdict_by,
                       c.final_verdict, c.resulting_state, c.excluded_curator_ids, c.correction_note,
                       c.contributor_id, cu.name as contributor_name, c.reviewer_id, ru.name as reviewer_name,
                       c.contributor_note is not null as has_contributor_note,
                       c.reviewer_note is not null as has_reviewer_note,
                       (select count(*) from arbitration_vote v where v.case_id = c.id) as votes_cast
                from arbitration_case c
                join submission s on s.id = c.submission_id
                left join app_user cu on cu.id = c.contributor_id
                left join app_user ru on ru.id = c.reviewer_id
                where """ + " " + where + """
                 order by case c.status when 'TIE_PENDING' then 0 when 'ACTIVE' then 1
                                        when 'RESPONSE_WINDOW' then 2 else 3 end,
                          coalesce(c.resolved_at, c.opened_at) desc
                 limit :limit
                """, params, (rs, i) -> caseRow(rs, curators, me));
    }

    private Map<String, Object> caseRow(ResultSet rs, Set<String> curators, String me) throws SQLException {
        Set<String> excluded = parseIds(rs.getString("excluded_curator_ids"));
        int eligible = (int) curators.stream().filter(id -> !excluded.contains(id)).count();
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("id", rs.getString("id"));
        c.put("submissionId", rs.getString("submission_id"));
        c.put("writtenForm", rs.getString("written_form"));
        c.put("pos", rs.getString("part_of_speech"));
        c.put("status", rs.getString("status"));
        c.put("origin", rs.getString("origin"));
        c.put("stage", rs.getString("stage"));
        c.put("openedAt", iso(rs.getTimestamp("opened_at")));
        c.put("responseDeadline", iso(rs.getTimestamp("response_deadline")));
        c.put("votingDeadline", iso(rs.getTimestamp("voting_deadline")));
        c.put("resolvedAt", iso(rs.getTimestamp("resolved_at")));
        c.put("verdictBy", rs.getString("verdict_by"));
        c.put("finalVerdict", rs.getString("final_verdict"));
        c.put("resultingState", rs.getString("resulting_state"));
        c.put("correctionNote", rs.getString("correction_note"));
        c.put("contributorId", rs.getString("contributor_id"));
        c.put("contributorName", rs.getString("contributor_name"));
        c.put("reviewerId", rs.getString("reviewer_id"));
        c.put("reviewerName", rs.getString("reviewer_name"));
        c.put("hasContributorNote", rs.getBoolean("has_contributor_note"));
        c.put("hasReviewerNote", rs.getBoolean("has_reviewer_note"));
        c.put("votesCast", rs.getInt("votes_cast"));
        c.put("eligible", eligible);
        c.put("quorum", ArbitrationService.quorum(eligible));
        if (me != null) {
            c.put("excludesMe", excluded.contains(me));
        }
        return c;
    }

    private List<Map<String, Object>> recentAudit(int limit, List<String> targetTypes) {
        MapSqlParameterSource p = new MapSqlParameterSource("limit", limit);
        String where = "";
        if (targetTypes != null) {
            where = " where a.target_type in (:types)";
            p.addValue("types", targetTypes);
        }
        return jdbc.query("""
                select a.id, a.timestamp, a.actor_id, a.actor_role, u.name as actor_name,
                       a.action, a.target_type, a.target_id, a.detail
                from audit_event a left join app_user u on u.id = a.actor_id""" + where
                + " order by a.timestamp desc limit :limit", p, (rs, i) -> {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("id", rs.getString("id"));
            e.put("at", iso(rs.getTimestamp("timestamp")));
            e.put("actorId", rs.getString("actor_id"));
            e.put("actorName", rs.getString("actor_name"));
            e.put("actorRole", rs.getString("actor_role"));
            e.put("action", rs.getString("action"));
            e.put("targetType", rs.getString("target_type"));
            e.put("targetId", rs.getString("target_id"));
            e.put("detail", rs.getString("detail"));
            return e;
        });
    }

    private List<Map<String, Object>> topContributors(int days, int limit) {
        return jdbc.query("""
                select s.contributor_id, u.name,
                       count(*) filter (where s.state <> 'DRAFT') as submitted,
                       count(*) filter (where s.state = 'VALIDATED') as validated
                from submission s join app_user u on u.id = s.contributor_id
                where s.created_at > now() - make_interval(days => :days)
                group by s.contributor_id, u.name
                having count(*) filter (where s.state <> 'DRAFT') > 0
                order by validated desc, submitted desc, u.name
                limit :limit
                """, new MapSqlParameterSource("days", days).addValue("limit", limit), (rs, i) -> {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("userId", rs.getString("contributor_id"));
            r.put("name", rs.getString("name"));
            r.put("submitted", rs.getLong("submitted"));
            r.put("validated", rs.getLong("validated"));
            return r;
        });
    }

    private Map<String, Object> lexiconCounts() {
        return numbers(jdbc.queryForMap("""
                select (select count(*) from lexical_entry) as entries,
                       (select count(*) from lexical_entry where origin = 'USER') as crowdsourced_entries,
                       (select count(*) from synset) as synsets,
                       (select count(*) from sense) as senses
                """, new MapSqlParameterSource()));
    }

    private Map<String, Object> lastExport() {
        List<Map<String, Object>> rows = jdbc.query("""
                select e.id, e.timestamp, e.entry_count, u.name as actor_name
                from export_record e left join app_user u on u.id = e.actor_id
                order by e.timestamp desc limit 1
                """, new MapSqlParameterSource(), (rs, i) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", rs.getString("id"));
            m.put("at", iso(rs.getTimestamp("timestamp")));
            m.put("entryCount", rs.getLong("entry_count"));
            m.put("actorName", rs.getString("actor_name"));
            return m;
        });
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** Weekly counts (Monday-based, UTC) for the last {@code weeks} weeks including the current one. */
    private List<Map<String, Object>> weeklySeries(String source, MapSqlParameterSource p, int weeks) {
        LocalDate thisWeek = LocalDate.now(ZoneOffset.UTC).with(TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY));
        LocalDate first = thisWeek.minusWeeks(weeks - 1L);
        MapSqlParameterSource params = p != null ? p : new MapSqlParameterSource();
        params.addValue("from", Timestamp.from(first.atStartOfDay(ZoneOffset.UTC).toInstant()));
        Map<LocalDate, Long> byWeek = new LinkedHashMap<>();
        jdbc.query("select date_trunc('week', at at time zone 'UTC')::date as wk, count(*) as n from ("
                + source + ") src where at >= :from group by wk", params, rs -> {
            byWeek.put(rs.getDate("wk").toLocalDate(), rs.getLong("n"));
        });
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = 0; i < weeks; i++) {
            LocalDate wk = first.plusWeeks(i);
            out.add(datum(i == weeks - 1 ? "This week" : wk.format(WEEK_LABEL), byWeek.getOrDefault(wk, 0L), null));
        }
        return out;
    }

    private Set<String> activeCurators() {
        return new HashSet<>(jdbc.queryForList(
                "select id from app_user where role = 'ARBITRATION_CURATOR' and active and not suspended",
                new MapSqlParameterSource(), String.class));
    }

    private static Set<String> parseIds(String raw) {
        if (raw == null || raw.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(raw.split("[^A-Za-z0-9_-]+")).filter(s -> !s.isBlank()).collect(Collectors.toSet());
    }

    private long count(String sql) {
        return count(sql, new MapSqlParameterSource());
    }

    private long count(String sql, MapSqlParameterSource p) {
        Long n = jdbc.queryForObject(sql, p, Long.class);
        return n == null ? 0 : n;
    }

    private static Map<String, Object> numbers(Map<String, Object> row) {
        Map<String, Object> out = new LinkedHashMap<>();
        row.forEach((k, v) -> out.put(camel(k), v instanceof Number n ? n.longValue() : v));
        return out;
    }

    private static String camel(String snake) {
        StringBuilder b = new StringBuilder();
        boolean up = false;
        for (char ch : snake.toCharArray()) {
            if (ch == '_') {
                up = true;
            } else {
                b.append(up ? Character.toUpperCase(ch) : ch);
                up = false;
            }
        }
        return b.toString();
    }

    private static Map<String, Object> datum(String label, Object value, String color) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("label", label);
        d.put("value", value instanceof Number n ? n.longValue() : 0L);
        if (color != null) {
            d.put("color", color);
        }
        return d;
    }

    static String iso(Object ts) {
        if (ts instanceof Timestamp t) {
            return t.toInstant().truncatedTo(ChronoUnit.SECONDS).toString();
        }
        if (ts instanceof Instant i) {
            return i.toString();
        }
        return null;
    }
}
