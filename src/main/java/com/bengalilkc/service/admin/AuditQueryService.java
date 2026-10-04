package com.bengalilkc.service.admin;

import com.bengalilkc.common.exception.ApiException;
import com.bengalilkc.domain.enums.AuditTargetType;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Filtered, paged reads and streamed exports of the audit trail. */
@Service
@RequiredArgsConstructor
public class AuditQueryService {

    /** Calendar days in filters are interpreted in the platform's time zone. */
    public static final ZoneId PLATFORM_ZONE = ZoneId.of("Asia/Dhaka");
    private static final int DEFAULT_SIZE = 50;
    private static final int MAX_SIZE = 200;
    private static final int MAX_EXPORT_ROWS = 200_000;

    private static final String SELECT = """
            select a.id, a.timestamp, a.actor_id, a.actor_role, a.action, a.target_type, a.target_id, a.detail,
                   case when a.actor_id = 'system' then 'System' else coalesce(u.name, a.actor_id) end as actor_name
            from audit_event a left join app_user u on u.id = a.actor_id
            """;

    public record Filter(String type, String action, String actor, String q, String from, String to) {}

    private record Where(String sql, MapSqlParameterSource params) {}

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public Map<String, Object> search(Filter filter, Integer pageRaw, Integer sizeRaw) {
        int size = sizeRaw == null ? DEFAULT_SIZE : Math.max(1, Math.min(MAX_SIZE, sizeRaw));
        int page = pageRaw == null ? 0 : Math.max(0, pageRaw);
        Where where = where(filter, true, true);
        MapSqlParameterSource p = where.params()
                .addValue("limit", size)
                .addValue("offset", (long) page * size);

        List<Map<String, Object>> items = jdbc.query(
                SELECT + where.sql() + " order by a.timestamp desc, a.id desc limit :limit offset :offset",
                p, (rs, i) -> row(rs));
        Long total = jdbc.queryForObject(
                "select count(*) from audit_event a left join app_user u on u.id = a.actor_id" + where.sql(),
                where.params(), Long.class);
        long totalElements = total == null ? 0 : total;

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", items);
        body.put("page", page);
        body.put("size", size);
        body.put("totalElements", totalElements);
        body.put("totalPages", (totalElements + size - 1) / size);
        body.put("facets", facets(filter));
        return body;
    }

    /** Streams every matching event (newest first) as CSV or JSON. Returns the number of rows written. */
    @Transactional(readOnly = true)
    public int export(Filter filter, String format, OutputStream out) {
        Where where = where(filter, true, true);
        String sql = SELECT + where.sql() + " order by a.timestamp desc, a.id desc limit " + MAX_EXPORT_ROWS;
        int[] count = {0};
        try {
            if ("csv".equals(format)) {
                Writer w = new OutputStreamWriter(out, StandardCharsets.UTF_8);
                w.write('\uFEFF');
                w.write("id,timestamp,actorId,actorName,actorRole,action,targetType,targetId,detail\n");
                jdbc.query(sql, where.params(), rs -> {
                    try {
                        w.write(String.join(",",
                                csv(rs.getString("id")), csv(instant(rs.getTimestamp("timestamp"))),
                                csv(rs.getString("actor_id")), csv(rs.getString("actor_name")),
                                csv(rs.getString("actor_role")), csv(rs.getString("action")),
                                csv(rs.getString("target_type")), csv(rs.getString("target_id")),
                                csv(rs.getString("detail"))));
                        w.write('\n');
                        count[0]++;
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                });
                w.flush();
            } else {
                JsonGenerator g = objectMapper.getFactory().createGenerator(out);
                g.useDefaultPrettyPrinter();
                g.writeStartArray();
                jdbc.query(sql, where.params(), rs -> {
                    try {
                        g.writeObject(row(rs));
                        count[0]++;
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                });
                g.writeEndArray();
                g.flush();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return count[0];
    }

    /** Human-readable summary of the active filters, for the export audit entry. */
    public static String describe(Filter f) {
        List<String> parts = new ArrayList<>();
        if (notBlank(f.type())) parts.add("type " + f.type());
        if (notBlank(f.action())) parts.add("action " + f.action());
        if (notBlank(f.actor())) parts.add("actor " + f.actor());
        if (notBlank(f.q())) parts.add("search \"" + f.q().strip() + "\"");
        if (notBlank(f.from())) parts.add("from " + f.from());
        if (notBlank(f.to())) parts.add("to " + f.to());
        return parts.isEmpty() ? "all events" : String.join(", ", parts);
    }

    private Map<String, Object> facets(Filter filter) {
        Where noType = where(filter, false, true);
        List<Map<String, Object>> types = jdbc.query(
                "select a.target_type, count(*) as n from audit_event a left join app_user u on u.id = a.actor_id"
                        + noType.sql() + " group by a.target_type order by a.target_type",
                noType.params(), (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("value", rs.getString("target_type"));
                    m.put("count", rs.getLong("n"));
                    return m;
                });
        Where noAction = where(filter, true, false);
        List<Map<String, Object>> actions = jdbc.query(
                "select a.action, count(*) as n from audit_event a left join app_user u on u.id = a.actor_id"
                        + noAction.sql() + " group by a.action order by a.action limit 300",
                noAction.params(), (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("value", rs.getString("action"));
                    m.put("count", rs.getLong("n"));
                    return m;
                });
        List<Map<String, Object>> actors = jdbc.query("""
                select a.actor_id, max(a.actor_role) as role,
                       case when a.actor_id = 'system' then 'System' else coalesce(max(u.name), a.actor_id) end as name
                from audit_event a left join app_user u on u.id = a.actor_id
                where a.actor_id is not null
                group by a.actor_id order by name limit 500
                """, new MapSqlParameterSource(), (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("value", rs.getString("actor_id"));
                    m.put("label", rs.getString("name"));
                    m.put("role", rs.getString("role"));
                    return m;
                });
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("types", types);
        m.put("actions", actions);
        m.put("actors", actors);
        return m;
    }

    private Where where(Filter f, boolean withType, boolean withAction) {
        List<String> clauses = new ArrayList<>();
        MapSqlParameterSource p = new MapSqlParameterSource();
        if (withType && notBlank(f.type())) {
            try {
                p.addValue("type", AuditTargetType.valueOf(f.type().strip().toUpperCase()).name());
            } catch (IllegalArgumentException e) {
                throw ApiException.unprocessable("Unknown event type.");
            }
            clauses.add("a.target_type = :type");
        }
        if (withAction && notBlank(f.action())) {
            clauses.add("a.action = :action");
            p.addValue("action", f.action().strip());
        }
        if (notBlank(f.actor())) {
            clauses.add("a.actor_id = :actor");
            p.addValue("actor", f.actor().strip());
        }
        if (notBlank(f.q())) {
            clauses.add("(a.detail ilike :q or a.action ilike :q or a.target_id ilike :q"
                    + " or u.name ilike :q or u.username ilike :q)");
            p.addValue("q", "%" + escapeLike(f.q().strip()) + "%");
        }
        LocalDate from = date(f.from(), "from");
        LocalDate to = date(f.to(), "to");
        if (from != null && to != null && to.isBefore(from)) {
            throw ApiException.unprocessable("The end date must be on or after the start date.");
        }
        if (from != null) {
            clauses.add("a.timestamp >= :from");
            p.addValue("from", Timestamp.from(from.atStartOfDay(PLATFORM_ZONE).toInstant()));
        }
        if (to != null) {
            clauses.add("a.timestamp < :to");
            p.addValue("to", Timestamp.from(to.plusDays(1).atStartOfDay(PLATFORM_ZONE).toInstant()));
        }
        return new Where(clauses.isEmpty() ? "" : " where " + String.join(" and ", clauses), p);
    }

    private static LocalDate date(String raw, String name) {
        if (!notBlank(raw)) {
            return null;
        }
        try {
            return LocalDate.parse(raw.strip());
        } catch (DateTimeParseException e) {
            throw ApiException.unprocessable("The " + name + " date must be in YYYY-MM-DD format.");
        }
    }

    private static Map<String, Object> row(ResultSet rs) throws SQLException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", rs.getString("id"));
        m.put("timestamp", instant(rs.getTimestamp("timestamp")));
        m.put("actorId", rs.getString("actor_id"));
        m.put("actorName", rs.getString("actor_name"));
        m.put("actorRole", rs.getString("actor_role"));
        m.put("action", rs.getString("action"));
        m.put("targetType", rs.getString("target_type"));
        m.put("targetId", rs.getString("target_id"));
        m.put("detail", rs.getString("detail"));
        return m;
    }

    private static String instant(Timestamp t) {
        return t == null ? null : t.toInstant().toString();
    }

    private static String csv(String v) {
        String s = v == null ? "" : v;
        // Neutralise spreadsheet formula injection.
        if (!s.isEmpty() && "=+-@\t\r".indexOf(s.charAt(0)) >= 0) {
            s = "'" + s;
        }
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }

    private static String escapeLike(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
