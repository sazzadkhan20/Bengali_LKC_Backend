package com.bengalilkc.service.export;

import com.bengalilkc.common.exception.ApiException;
import com.bengalilkc.domain.entity.admin.ExportRecord;
import com.bengalilkc.domain.entity.lexicon.Lexicon;
import com.bengalilkc.domain.enums.AuditTargetType;
import com.bengalilkc.domain.enums.PartOfSpeech;
import com.bengalilkc.repository.admin.ExportRecordRepository;
import com.bengalilkc.repository.lexicon.LexiconRepository;
import com.bengalilkc.security.UserPrincipal;
import com.bengalilkc.service.admin.AuditService;
import com.bengalilkc.service.admin.IdGeneratorService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * LMF XML export straight from the lexicon tables. Validated submissions are promoted into
 * those tables (origin USER, stamped with {@code promoted_at}), so they are exported with their
 * permanent ids. Scopes:
 * <ul>
 *   <li>{@code all} — imported lexicon plus every platform entry;</li>
 *   <li>{@code new} — platform entries validated since the previous {@code all}/{@code new} export
 *       (the baseline), so incremental exports never skip or repeat an entry;</li>
 *   <li>{@code xml} — the originally imported lexicon only;</li>
 *   <li>{@code range} — platform entries validated within a date range (Asia/Dhaka days).</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class ExportService {

    public static final ZoneId PLATFORM_ZONE = ZoneId.of("Asia/Dhaka");
    private static final Pattern VERSION = Pattern.compile("[0-9A-Za-z][0-9A-Za-z._-]{0,39}");
    private static final List<String> SCOPES = List.of("all", "new", "xml", "range");

    private final LexiconRepository lexiconRepository;
    private final ExportRecordRepository exportRecordRepository;
    private final IdGeneratorService idGeneratorService;
    private final AuditService auditService;
    private final NamedParameterJdbcTemplate jdbc;

    private record SenseRow(String senseId, String entryId, String writtenForm, String pos, String synsetId, boolean user) {}

    private record Baseline(String exportId, Instant at, String actorName) {}

    private record Plan(String scope, String version, List<PartOfSpeech> pos, LocalDate from, LocalDate to,
                        Baseline baseline, Instant cutoff, boolean lkcSource) {

        String posLabel() {
            return pos.isEmpty() ? "all" : String.join("/", pos.stream().map(PartOfSpeech::getCode).toList());
        }
    }

    public record ExportFile(String xml, String sha256, String filename, String exportId) {}

    @Transactional(readOnly = true)
    public Map<String, Object> dryRun(Map<String, String> params) {
        Plan plan = plan(params);
        List<SenseRow> senses = select(plan);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("dryRun", true);
        body.put("scope", plan.scope());
        body.put("projected", stats(senses));
        body.put("window", window(plan));
        body.put("filename", filename(plan));
        return body;
    }

    @Transactional
    public ExportFile export(UserPrincipal principal, Map<String, String> params) {
        Plan plan = plan(params);
        List<SenseRow> senses = select(plan);
        if (senses.isEmpty()) {
            throw ApiException.unprocessable(Map.of("scope", emptyMessage(plan)));
        }

        Map<String, List<SenseRow>> byEntry = new LinkedHashMap<>();
        Set<String> senseIds = new HashSet<>();
        Set<String> synsetIds = new TreeSet<>();
        int userSenses = 0;
        for (SenseRow r : senses) {
            byEntry.computeIfAbsent(r.entryId(), k -> new ArrayList<>()).add(r);
            senseIds.add(r.senseId());
            synsetIds.add(r.synsetId());
            if (r.user()) {
                userSenses++;
            }
        }

        String xml = render(plan, byEntry, senseIds, synsetIds);
        String sha256 = sha256(xml);
        String exportId = "exp-" + idGeneratorService.nextValue("export");
        exportRecordRepository.save(ExportRecord.builder()
                .id(exportId)
                .timestamp(Instant.now())
                .actorId(principal.getId())
                .entryCount(byEntry.size())
                .synsetCount(synsetIds.size())
                .senseCount(senses.size())
                .userSenses(userSenses)
                .fileBytes(xml.getBytes(StandardCharsets.UTF_8).length)
                .sha256(sha256)
                .scope(plan.scope().toUpperCase())
                .version(plan.version())
                .posFilter(plan.posLabel())
                .rangeFrom(plan.from())
                .rangeTo(plan.to())
                .baselineAt(plan.baseline() != null ? plan.baseline().at() : null)
                .cutoffAt(plan.cutoff())
                .configJson("version=" + plan.version() + "; pos=" + plan.posLabel() + "; scope=" + plan.scope()
                        + (plan.lkcSource() ? "; lkcSource" : ""))
                .build());
        auditService.record(principal, "EXPORT_GENERATED", AuditTargetType.EXPORT, exportId,
                scopeLabel(plan) + ": " + byEntry.size() + " entries, " + synsetIds.size() + " synsets, "
                        + userSenses + " platform senses · SHA-256 " + sha256.substring(0, 12) + "…");
        return new ExportFile(xml, sha256, filename(plan), exportId);
    }

    /** Export page data: the "new" baseline, pending counts and a page of export history. */
    @Transactional(readOnly = true)
    public Map<String, Object> summary(int page, int size) {
        int safeSize = Math.min(Math.max(size, 1), 50);
        int safePage = Math.max(page, 0);
        Baseline baseline = baseline();
        Instant now = Instant.now();

        Map<String, Object> counts = jdbc.queryForMap("""
                select count(*) filter (where origin = 'XML') as xml_senses,
                       count(*) filter (where origin = 'USER') as user_senses,
                       count(*) filter (where origin = 'USER' and (cast(:baseline as timestamptz) is null
                                                                   or promoted_at > :baseline)) as pending_new,
                       min(promoted_at) filter (where origin = 'USER') as first_promoted
                from sense
                """, new MapSqlParameterSource("baseline", baseline != null ? Timestamp.from(baseline.at()) : null));

        long total = jdbc.queryForObject("select count(*) from export_record", new MapSqlParameterSource(), Long.class);
        List<Map<String, Object>> items = jdbc.query("""
                select e.id, e.timestamp, e.scope, e.version, e.pos_filter, e.range_from, e.range_to,
                       e.baseline_at, e.cutoff_at, e.entry_count, e.synset_count, e.sense_count, e.user_senses,
                       e.file_bytes, e.sha256, e.actor_id, u.name as actor_name, u.role as actor_role
                from export_record e left join app_user u on u.id = e.actor_id
                order by e.timestamp desc, e.id desc
                limit :limit offset :offset
                """, new MapSqlParameterSource("limit", safeSize).addValue("offset", (long) safePage * safeSize),
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getString("id"));
                    m.put("timestamp", iso(rs.getTimestamp("timestamp")));
                    m.put("scope", rs.getString("scope"));
                    m.put("version", rs.getString("version"));
                    m.put("pos", rs.getString("pos_filter"));
                    m.put("rangeFrom", rs.getString("range_from"));
                    m.put("rangeTo", rs.getString("range_to"));
                    m.put("baselineAt", iso(rs.getTimestamp("baseline_at")));
                    m.put("cutoffAt", iso(rs.getTimestamp("cutoff_at")));
                    m.put("entries", rs.getInt("entry_count"));
                    m.put("synsets", rs.getInt("synset_count"));
                    m.put("senses", rs.getInt("sense_count"));
                    m.put("platformSenses", rs.getInt("user_senses"));
                    m.put("bytes", rs.getLong("file_bytes"));
                    m.put("sha256", rs.getString("sha256"));
                    m.put("actorId", rs.getString("actor_id"));
                    m.put("actorName", rs.getString("actor_name"));
                    m.put("actorRole", rs.getString("actor_role"));
                    return m;
                });

        Map<String, Object> base = null;
        if (baseline != null) {
            base = new LinkedHashMap<>();
            base.put("exportId", baseline.exportId());
            base.put("at", baseline.at().toString());
            base.put("actorName", baseline.actorName());
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("baseline", base);
        body.put("pendingNew", ((Number) counts.get("pending_new")).longValue());
        body.put("xmlSenses", ((Number) counts.get("xml_senses")).longValue());
        body.put("platformSenses", ((Number) counts.get("user_senses")).longValue());
        Object first = counts.get("first_promoted");
        body.put("firstPlatformDate", first instanceof Timestamp ts
                ? ts.toInstant().atZone(PLATFORM_ZONE).toLocalDate().toString() : null);
        body.put("today", LocalDate.ofInstant(now, PLATFORM_ZONE).toString());
        body.put("items", items);
        body.put("page", safePage);
        body.put("size", safeSize);
        body.put("totalElements", total);
        body.put("totalPages", (int) Math.ceil(total / (double) safeSize));
        return body;
    }

    private Plan plan(Map<String, String> params) {
        Map<String, String> errors = new LinkedHashMap<>();
        String scope = trim(params.get("scope"));
        if (scope == null) {
            scope = "all";
        }
        scope = scope.toLowerCase();
        if (!SCOPES.contains(scope)) {
            errors.put("scope", "Choose a scope: new, all, xml or range.");
        }

        String version = trim(params.get("version"));
        if (version == null) {
            version = "1.1";
        }
        if (!VERSION.matcher(version).matches()) {
            errors.put("version", "Use 1–40 letters, digits, dots, hyphens or underscores (e.g. 1.2).");
        }

        List<PartOfSpeech> pos = new ArrayList<>();
        String posParam = trim(params.get("pos"));
        if (posParam != null) {
            for (String p : posParam.split(",")) {
                if (p.isBlank()) {
                    continue;
                }
                try {
                    PartOfSpeech value = PartOfSpeech.from(p.trim());
                    if (!pos.contains(value)) {
                        pos.add(value);
                    }
                } catch (Exception e) {
                    errors.put("pos", "Unknown part of speech \"" + p.trim() + "\".");
                }
            }
        }

        LocalDate today = LocalDate.now(PLATFORM_ZONE);
        LocalDate from = null;
        LocalDate to = null;
        if ("range".equals(scope)) {
            from = date(params.get("from"), "from", "Choose a start date.", errors);
            to = date(params.get("to"), "to", "Choose an end date.", errors);
            if (from != null && from.isAfter(today)) {
                errors.put("from", "The start date cannot be in the future.");
            }
            if (from != null && to != null && to.isBefore(from)) {
                errors.put("to", "The end date must be on or after the start date.");
            }
        }
        if (!errors.isEmpty()) {
            throw ApiException.unprocessable(errors);
        }

        Instant now = Instant.now();
        Instant cutoff = now;
        if (to != null) {
            Instant end = to.plusDays(1).atStartOfDay(PLATFORM_ZONE).toInstant();
            cutoff = end.isBefore(now) ? end : now;
        }
        Baseline baseline = "new".equals(scope) ? baseline() : null;
        return new Plan(scope, version, pos, from, to, baseline, cutoff, "1".equals(params.get("lkcSource")));
    }

    private List<SenseRow> select(Plan plan) {
        StringBuilder where = new StringBuilder(" where 1 = 1");
        MapSqlParameterSource args = new MapSqlParameterSource("cutoff", Timestamp.from(plan.cutoff()));
        if (!plan.pos().isEmpty()) {
            where.append(" and e.part_of_speech in (:pos)");
            args.addValue("pos", plan.pos().stream().map(PartOfSpeech::getCode).toList());
        }
        switch (plan.scope()) {
            case "xml" -> where.append(" and s.origin = 'XML'");
            case "new" -> {
                where.append(" and s.origin = 'USER' and s.promoted_at <= :cutoff");
                if (plan.baseline() != null) {
                    where.append(" and s.promoted_at > :baseline");
                    args.addValue("baseline", Timestamp.from(plan.baseline().at()));
                }
            }
            case "range" -> {
                where.append(" and s.origin = 'USER' and s.promoted_at >= :from and s.promoted_at < :cutoff");
                args.addValue("from", Timestamp.from(plan.from().atStartOfDay(PLATFORM_ZONE).toInstant()));
            }
            default -> where.append(" and (s.origin = 'XML' or s.promoted_at <= :cutoff)");
        }
        return jdbc.query("""
                select s.id as sense_id, e.id as entry_id, e.written_form, e.part_of_speech, s.synset_id, s.origin
                from sense s join lexical_entry e on e.id = s.lexical_entry_id
                """ + where + " order by e.id, s.id", args,
                (rs, i) -> new SenseRow(rs.getString("sense_id"), rs.getString("entry_id"),
                        rs.getString("written_form"), rs.getString("part_of_speech"), rs.getString("synset_id"),
                        "USER".equals(rs.getString("origin"))));
    }

    private Baseline baseline() {
        List<Baseline> rows = jdbc.query("""
                select e.id, e.cutoff_at, u.name
                from export_record e left join app_user u on u.id = e.actor_id
                where e.scope in ('ALL', 'NEW') and e.cutoff_at is not null
                order by e.cutoff_at desc limit 1
                """, new MapSqlParameterSource(),
                (rs, i) -> new Baseline(rs.getString(1), rs.getTimestamp(2).toInstant(), rs.getString(3)));
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static Map<String, Object> stats(List<SenseRow> senses) {
        Set<String> entries = new HashSet<>();
        Set<String> synsets = new HashSet<>();
        long user = 0;
        for (SenseRow r : senses) {
            entries.add(r.entryId());
            synsets.add(r.synsetId());
            if (r.user()) {
                user++;
            }
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("entries", entries.size());
        m.put("senses", senses.size());
        m.put("synsets", synsets.size());
        m.put("crowdsourced", user);
        return m;
    }

    private static Map<String, Object> window(Plan plan) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("baselineAt", plan.baseline() != null ? plan.baseline().at().toString() : null);
        m.put("baselineExportId", plan.baseline() != null ? plan.baseline().exportId() : null);
        m.put("cutoffAt", plan.cutoff().toString());
        m.put("from", plan.from() != null ? plan.from().toString() : null);
        m.put("to", plan.to() != null ? plan.to().toString() : null);
        m.put("version", plan.version());
        m.put("pos", plan.pos().stream().map(PartOfSpeech::getCode).toList());
        return m;
    }

    private static String emptyMessage(Plan plan) {
        String pos = plan.pos().isEmpty() ? "" : " for the selected parts of speech";
        return switch (plan.scope()) {
            case "new" -> plan.baseline() != null
                    ? "No platform entries have been validated" + pos + " since the last export ("
                            + plan.baseline().exportId() + "). Nothing to export yet."
                    : "No platform entries have been validated" + pos + " yet. Nothing to export.";
            case "range" -> "No platform entries were validated" + pos + " between " + plan.from() + " and "
                    + plan.to() + ".";
            default -> "No lexicon entries match the selected parts of speech.";
        };
    }

    private static String scopeLabel(Plan plan) {
        return switch (plan.scope()) {
            case "new" -> "New since " + (plan.baseline() != null ? plan.baseline().exportId() : "the start");
            case "xml" -> "Original XML only";
            case "range" -> "Validated " + plan.from() + " to " + plan.to();
            default -> "All data";
        };
    }

    private static String filename(Plan plan) {
        String suffix = switch (plan.scope()) {
            case "new" -> "_new_" + LocalDate.ofInstant(plan.cutoff(), PLATFORM_ZONE);
            case "xml" -> "_original";
            case "range" -> "_" + plan.from() + "_to_" + plan.to();
            default -> "";
        };
        return "Bangla_Mono_Lingual_Lexicons_v" + plan.version() + suffix + ".xml";
    }

    private String render(Plan plan, Map<String, List<SenseRow>> byEntry, Set<String> senseIds, Set<String> synsetIds) {
        Map<String, List<String[]>> senseRels = new TreeMap<>();
        jdbc.query("""
                select r.sense_id_1, t.relation_name, r.sense_id_2
                from sense_relation r join sense_relation_type t on t.id = r.relation_type_id
                order by r.sense_id_1, t.relation_name, r.sense_id_2
                """, (RowCallbackHandler) rs -> {
            String src = rs.getString(1);
            String target = rs.getString(3);
            if (senseIds.contains(src) && senseIds.contains(target)) {
                senseRels.computeIfAbsent(src, k -> new ArrayList<>()).add(new String[] {rs.getString(2), target});
            }
        });

        Lexicon meta = lexiconRepository.findById("livelanguage-ben")
                .orElseThrow(() -> ApiException.notFound("Lexicon metadata missing"));

        StringBuilder xml = new StringBuilder(Math.max(1024, byEntry.size() * 400));
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        xml.append("<LexicalResource xmlns:dc=\"http://purl.org/dc/elements/1.1/\">\n");
        String source = esc(meta.getDcSource() != null ? meta.getDcSource() : "");
        if (plan.lkcSource()) {
            source = source + "; Bengali LKC Framework crowdsourcing platform (Ben-LKC)";
        }
        xml.append("  <Lexicon id=\"").append(esc(meta.getId()))
                .append("\" label=\"").append(esc(meta.getLabel()))
                .append("\" language=\"").append(esc(meta.getLanguage()))
                .append("\" email=\"").append(esc(meta.getEmail()))
                .append("\" license=\"").append(esc(meta.getLicense()))
                .append("\" version=\"").append(esc(plan.version()))
                .append("\" dc:source=\"").append(source)
                .append("\" dc:description=\"").append(esc(meta.getDcDescription()))
                .append("\" dc:publisher=\"").append(esc(meta.getDcPublisher()))
                .append("\">\n");

        for (List<SenseRow> rows : byEntry.values()) {
            SenseRow first = rows.get(0);
            xml.append("    <LexicalEntry id=\"").append(esc(first.entryId())).append("\">\n");
            xml.append("      <Lemma writtenForm=\"").append(esc(first.writtenForm()))
                    .append("\" partOfSpeech=\"").append(first.pos()).append("\"></Lemma>\n");
            for (SenseRow r : rows) {
                List<String[]> rels = senseRels.getOrDefault(r.senseId(), List.of());
                xml.append("      <Sense id=\"").append(esc(r.senseId())).append("\" synset=\"")
                        .append(esc(r.synsetId())).append("\">");
                if (rels.isEmpty()) {
                    xml.append("</Sense>\n");
                    continue;
                }
                xml.append("\n");
                for (String[] rel : rels) {
                    xml.append("        <SenseRelation relType=\"").append(esc(rel[0]))
                            .append("\" target=\"").append(esc(rel[1])).append("\"></SenseRelation>\n");
                }
                xml.append("      </Sense>\n");
            }
            xml.append("    </LexicalEntry>\n");
        }

        Map<String, List<String[]>> synRels = new TreeMap<>();
        jdbc.query("""
                select r.source_synset_id, t.relation_name, r.target_synset_id
                from synset_relation r join synset_relation_type t on t.id = r.relation_type_id
                order by r.source_synset_id, t.relation_name, r.target_synset_id
                """, (RowCallbackHandler) rs -> {
            String src = rs.getString(1);
            String target = rs.getString(3);
            if (synsetIds.contains(src) && synsetIds.contains(target)) {
                synRels.computeIfAbsent(src, k -> new ArrayList<>()).add(new String[] {rs.getString(2), target});
            }
        });
        jdbc.query("""
                select id, ili, part_of_speech, lexicalized, definition_language, definition_text
                from synset where id in (:ids) order by id
                """, new MapSqlParameterSource("ids", List.copyOf(synsetIds)), (RowCallbackHandler) rs -> {
            String id = rs.getString("id");
            xml.append("    <Synset id=\"").append(esc(id)).append("\" ili=\"").append(esc(rs.getString("ili")))
                    .append("\" partOfSpeech=\"").append(rs.getString("part_of_speech"))
                    .append("\" lexicalized=\"").append(rs.getBoolean("lexicalized")).append("\">\n");
            String def = rs.getString("definition_text");
            if (def != null) {
                String lang = rs.getString("definition_language");
                xml.append("      <Definition language=\"").append(lang != null ? lang : "ben")
                        .append("\">").append(esc(def)).append("</Definition>\n");
            }
            for (String[] rel : synRels.getOrDefault(id, List.of())) {
                xml.append("      <SynsetRelation relType=\"").append(esc(rel[0]))
                        .append("\" target=\"").append(esc(rel[1])).append("\"></SynsetRelation>\n");
            }
            xml.append("    </Synset>\n");
        });

        xml.append("  </Lexicon>\n</LexicalResource>");
        return xml.toString();
    }

    private static LocalDate date(String raw, String field, String missing, Map<String, String> errors) {
        String value = trim(raw);
        if (value == null) {
            errors.put(field, missing);
            return null;
        }
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            errors.put(field, "Use the format YYYY-MM-DD.");
            return null;
        }
    }

    private static String trim(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static String iso(Timestamp ts) {
        return ts == null ? null : ts.toInstant().toString();
    }

    private static String sha256(String xml) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(xml.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String esc(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
