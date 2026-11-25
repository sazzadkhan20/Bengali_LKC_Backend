package com.bengalilkc.service.workflow;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The System Moderator's corrections desk: tied arbitration cases waiting for a corrected entry, and the
 * history of tie-break corrections already made (what changed, by whom, when).
 */
@Service
@RequiredArgsConstructor
public class TieCorrectionService {

    private static final int MAX_PAGE_SIZE = 50;
    private static final TypeReference<List<Map<String, Object>>> DETAILS = new TypeReference<>() {};

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public Map<String, Object> overview(String q, int page, int size) {
        String term = q == null ? "" : q.trim();
        if (term.length() > 80) {
            term = term.substring(0, 80);
        }
        int pageSize = Math.max(1, Math.min(size, MAX_PAGE_SIZE));
        int pageNo = Math.max(1, page);

        MapSqlParameterSource args = new MapSqlParameterSource()
                .addValue("q", term)
                .addValue("like", "%" + term.toLowerCase().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%");
        String search = term.isEmpty() ? "" : " and (lower(s.written_form) like :like or lower(c.id) like :like"
                + " or lower(c.submission_id) like :like or lower(lkc_definitions_text(s.definitions_json)) like :like)";

        List<Map<String, Object>> queue = jdbc.query(
                "select c.id, c.submission_id, s.written_form, s.part_of_speech, s.definition_text, c.stage,"
                        + " c.opened_at, c.voting_deadline, c.resolution_note,"
                        + " cu.name as contributor_name, ru.name as reviewer_name,"
                        + " (select count(*) from arbitration_vote v where v.case_id = c.id) as votes"
                        + " from arbitration_case c"
                        + " join submission s on s.id = c.submission_id"
                        + " left join app_user cu on cu.id = c.contributor_id"
                        + " left join app_user ru on ru.id = c.reviewer_id"
                        + " where c.status = 'TIE_PENDING'" + search
                        + " order by c.voting_deadline nulls last, c.opened_at, c.id",
                args, (rs, i) -> queueRow(rs));

        Long total = jdbc.queryForObject(
                "select count(*) from arbitration_case c join submission s on s.id = c.submission_id"
                        + " where c.verdict_by = 'TIE_BREAK'" + search, args, Long.class);
        long totalRows = total == null ? 0 : total;
        int totalPages = (int) Math.max(1, (totalRows + pageSize - 1) / pageSize);
        if (pageNo > totalPages) {
            pageNo = totalPages;
        }
        args.addValue("limit", pageSize).addValue("offset", (pageNo - 1) * pageSize);
        List<Map<String, Object>> history = history(search, args);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("q", term);
        out.put("queue", queue);
        out.put("history", history);
        out.put("page", pageNo);
        out.put("size", pageSize);
        out.put("total", totalRows);
        out.put("totalPages", totalPages);
        return out;
    }

    /** Most recent tie-break corrections, for the moderator dashboard. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> recent(int limit) {
        return history("", new MapSqlParameterSource().addValue("limit", limit).addValue("offset", 0));
    }

    private List<Map<String, Object>> history(String search, MapSqlParameterSource args) {
        return jdbc.query(
                "select c.id, c.submission_id, s.written_form, s.part_of_speech, s.state, c.resolved_at,"
                        + " c.tie_broken_by, mu.name as moderator_name, c.tie_corrections_json, c.resolution_note,"
                        + " (select e.id from lexical_entry e where e.source_submission_id = c.submission_id"
                        + "   order by e.id limit 1) as entry_id"
                        + " from arbitration_case c"
                        + " join submission s on s.id = c.submission_id"
                        + " left join app_user mu on mu.id = c.tie_broken_by"
                        + " where c.verdict_by = 'TIE_BREAK'" + search
                        + " order by c.resolved_at desc nulls last, c.id desc limit :limit offset :offset",
                args, (rs, i) -> historyRow(rs));
    }

    private Map<String, Object> queueRow(ResultSet rs) throws SQLException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("caseId", rs.getString("id"));
        m.put("submissionId", rs.getString("submission_id"));
        m.put("word", rs.getString("written_form"));
        m.put("pos", rs.getString("part_of_speech"));
        m.put("definition", rs.getString("definition_text"));
        m.put("stage", rs.getString("stage"));
        m.put("openedAt", iso(rs.getTimestamp("opened_at")));
        m.put("votingClosedAt", iso(rs.getTimestamp("voting_deadline")));
        m.put("tally", rs.getString("resolution_note"));
        m.put("votes", rs.getInt("votes"));
        m.put("contributorName", rs.getString("contributor_name"));
        m.put("reviewerName", rs.getString("reviewer_name"));
        return m;
    }

    private Map<String, Object> historyRow(ResultSet rs) throws SQLException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("caseId", rs.getString("id"));
        m.put("submissionId", rs.getString("submission_id"));
        m.put("word", rs.getString("written_form"));
        m.put("pos", rs.getString("part_of_speech"));
        m.put("state", rs.getString("state"));
        m.put("entryId", rs.getString("entry_id"));
        m.put("resolvedAt", iso(rs.getTimestamp("resolved_at")));
        m.put("moderatorId", rs.getString("tie_broken_by"));
        m.put("moderatorName", rs.getString("moderator_name"));
        m.put("changes", parse(rs.getString("tie_corrections_json")));
        m.put("note", rs.getString("resolution_note"));
        return m;
    }

    private List<Map<String, Object>> parse(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, DETAILS);
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private static String iso(Timestamp ts) {
        return ts == null ? null : ts.toInstant().toString();
    }
}
