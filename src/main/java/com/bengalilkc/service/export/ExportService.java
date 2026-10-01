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
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * LMF XML export straight from the lexicon tables. Validated submissions are promoted into
 * those tables (origin USER), so they are exported with their stored ids; {@code origin}
 * selects XML-imported data, new user data, or both.
 */
@Service
@RequiredArgsConstructor
public class ExportService {

    private final LexiconRepository lexiconRepository;
    private final ExportRecordRepository exportRecordRepository;
    private final IdGeneratorService idGeneratorService;
    private final AuditService auditService;
    private final NamedParameterJdbcTemplate jdbc;

    private record SenseRow(String senseId, String entryId, String writtenForm, String pos, String synsetId) {}

    @Transactional
    public Object export(UserPrincipal principal, Map<String, String> params) {
        boolean dryRun = "1".equals(params.get("dryRun"));
        String version = params.getOrDefault("version", "1.1");
        List<PartOfSpeech> posFilter = new ArrayList<>();
        String posParam = params.get("pos");
        if (posParam != null && !posParam.isBlank()) {
            for (String p : posParam.split(",")) {
                try {
                    posFilter.add(PartOfSpeech.from(p.trim()));
                } catch (Exception ignored) {
                    // skip invalid
                }
            }
        }
        String origin = params.getOrDefault("origin", "0".equals(params.get("canonical")) ? "user" : "all").toLowerCase();
        if (!List.of("all", "xml", "user").contains(origin)) {
            throw ApiException.badRequest("origin must be all, xml or user.");
        }
        boolean includeLkcSource = "1".equals(params.get("lkcSource"));

        StringBuilder where = new StringBuilder(" where 1 = 1");
        MapSqlParameterSource args = new MapSqlParameterSource();
        if (!posFilter.isEmpty()) {
            where.append(" and e.part_of_speech in (:pos)");
            args.addValue("pos", posFilter.stream().map(PartOfSpeech::getCode).toList());
        }
        if (!"all".equals(origin)) {
            where.append(" and s.origin = :origin");
            args.addValue("origin", origin.toUpperCase());
        }
        List<SenseRow> senses = jdbc.query("""
                select s.id as sense_id, e.id as entry_id, e.written_form, e.part_of_speech, s.synset_id
                from sense s join lexical_entry e on e.id = s.lexical_entry_id
                """ + where + " order by e.id, s.id", args,
                (rs, i) -> new SenseRow(rs.getString("sense_id"), rs.getString("entry_id"),
                        rs.getString("written_form"), rs.getString("part_of_speech"), rs.getString("synset_id")));

        Map<String, List<SenseRow>> byEntry = new LinkedHashMap<>();
        Set<String> senseIds = new HashSet<>();
        Set<String> synsetIds = new java.util.TreeSet<>();
        for (SenseRow r : senses) {
            byEntry.computeIfAbsent(r.entryId(), k -> new ArrayList<>()).add(r);
            senseIds.add(r.senseId());
            synsetIds.add(r.synsetId());
        }
        long userSenses = senses.isEmpty() ? 0 : jdbc.queryForObject(
                "select count(*) from sense s join lexical_entry e on e.id = s.lexical_entry_id"
                        + where + " and s.origin = 'USER'", args, Long.class);

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("entries", byEntry.size());
        stats.put("crowdsourced", userSenses);
        stats.put("synsets", synsetIds.size());

        if (dryRun) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("dryRun", true);
            body.put("projected", stats);
            body.put("config", Map.of(
                    "version", version,
                    "posFilter", posFilter.stream().map(PartOfSpeech::getCode).toList(),
                    "origin", origin,
                    "includeLkcSource", includeLkcSource));
            return body;
        }

        Map<String, List<String[]>> senseRels = new TreeMap<>();
        if (!senseIds.isEmpty()) {
            jdbc.query("""
                    select r.sense_id_1, t.relation_name, r.sense_id_2
                    from sense_relation r join sense_relation_type t on t.id = r.relation_type_id
                    order by r.sense_id_1, t.relation_name, r.sense_id_2
                    """, (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
                String src = rs.getString(1);
                String target = rs.getString(3);
                if (senseIds.contains(src) && senseIds.contains(target)) {
                    senseRels.computeIfAbsent(src, k -> new ArrayList<>()).add(new String[] {rs.getString(2), target});
                }
            });
        }

        Lexicon meta = lexiconRepository.findById("livelanguage-ben")
                .orElseThrow(() -> ApiException.notFound("Lexicon metadata missing"));

        StringBuilder xml = new StringBuilder();
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        xml.append("<LexicalResource xmlns:dc=\"http://purl.org/dc/elements/1.1/\">\n");
        String source = esc(meta.getDcSource() != null ? meta.getDcSource() : "");
        if (includeLkcSource) {
            source = source + "; Bengali LKC Framework crowdsourcing platform (Ben-LKC)";
        }
        xml.append("  <Lexicon id=\"").append(esc(meta.getId()))
                .append("\" label=\"").append(esc(meta.getLabel()))
                .append("\" language=\"").append(esc(meta.getLanguage()))
                .append("\" email=\"").append(esc(nullToEmpty(meta.getEmail())))
                .append("\" license=\"").append(esc(nullToEmpty(meta.getLicense())))
                .append("\" version=\"").append(esc(version))
                .append("\" dc:source=\"").append(source)
                .append("\" dc:description=\"").append(esc(nullToEmpty(meta.getDcDescription())))
                .append("\" dc:publisher=\"").append(esc(nullToEmpty(meta.getDcPublisher())))
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

        if (!synsetIds.isEmpty()) {
            Map<String, List<String[]>> synRels = new TreeMap<>();
            jdbc.query("""
                    select r.source_synset_id, t.relation_name, r.target_synset_id
                    from synset_relation r join synset_relation_type t on t.id = r.relation_type_id
                    order by r.source_synset_id, t.relation_name, r.target_synset_id
                    """, (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
                String src = rs.getString(1);
                String target = rs.getString(3);
                if (synsetIds.contains(src) && synsetIds.contains(target)) {
                    synRels.computeIfAbsent(src, k -> new ArrayList<>()).add(new String[] {rs.getString(2), target});
                }
            });
            jdbc.query("""
                    select id, ili, part_of_speech, lexicalized, definition_language, definition_text
                    from synset where id in (:ids) order by id
                    """, new MapSqlParameterSource("ids", List.copyOf(synsetIds)),
                    (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
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
        }

        xml.append("  </Lexicon>\n</LexicalResource>");
        String xmlStr = xml.toString();
        String sha256 = sha256(xmlStr);

        String exportId = "exp-" + idGeneratorService.nextValue("export");
        exportRecordRepository.save(ExportRecord.builder()
                .id(exportId)
                .timestamp(Instant.now())
                .actorId(principal.getId())
                .entryCount(byEntry.size())
                .synsetCount(synsetIds.size())
                .sha256(sha256)
                .configJson("version=" + version + "; pos="
                        + (posFilter.isEmpty() ? "all" : String.join("/", posFilter.stream().map(PartOfSpeech::getCode).toList()))
                        + "; origin=" + origin)
                .build());
        auditService.record(principal, "EXPORT_GENERATED", AuditTargetType.EXPORT, exportId,
                byEntry.size() + " entries, " + synsetIds.size() + " synsets (" + origin + "), SHA-256 "
                        + sha256.substring(0, 12) + "…");

        return Map.of("xml", xmlStr, "sha256", sha256, "version", version);
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

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
