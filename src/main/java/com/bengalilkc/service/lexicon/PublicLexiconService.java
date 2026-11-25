package com.bengalilkc.service.lexicon;

import com.bengalilkc.common.BengaliText;
import com.bengalilkc.common.exception.ApiException;
import com.bengalilkc.domain.enums.PartOfSpeech;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read model for the public dictionary: browse, word / gloss / synset / sense search, suggestions and
 * word / synset detail, straight from the lexicon tables. Imported XML data and entries promoted from
 * validated submissions (origin {@code USER}) are served together; platform data is flagged so the UI
 * can badge it as crowdsourced.
 */
@Service
@RequiredArgsConstructor
public class PublicLexiconService {

    public static final List<String> ALPHABET = List.of(
            "অ", "আ", "ই", "ঈ", "উ", "ঊ", "ঋ", "এ", "ঐ", "ও", "ঔ",
            "ক", "খ", "গ", "ঘ", "ঙ", "চ", "ছ", "জ", "ঝ", "ঞ",
            "ট", "ঠ", "ড", "ঢ", "ণ", "ত", "থ", "দ", "ধ", "ন",
            "প", "ফ", "ব", "ভ", "ম", "য", "র", "ল", "শ", "ষ", "স", "হ",
            "ড়", "ঢ়", "য়", "ৎ");

    private static final Pattern SYNSET_ID = Pattern.compile("(?i)^ben-syn\\d+$");
    private static final Pattern ILI = Pattern.compile("(?i)^\\d{8}-[nvars]$");
    private static final int MAX_QUERY = 100;
    private static final String SENSE_ORDER = "s.created_at, length(s.id), s.id";

    private final NamedParameterJdbcTemplate jdbc;
    private final LexiconStatsService statsService;
    private final SynsetDefinitionService definitionService;

    // ------------------------------------------------------------------ stats

    @Transactional(readOnly = true)
    public Map<String, Object> stats() {
        Map<String, Object> m = new LinkedHashMap<>(statsService.stats());
        m.put("lexicon", lexiconMeta());
        return m;
    }

    private Map<String, Object> lexiconMeta() {
        List<Map<String, Object>> rows = jdbc.query("""
                select id, label, language, email, license, version, dc_publisher, dc_source, dc_description
                from lexicon order by id limit 1
                """, new MapSqlParameterSource(), (rs, i) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", rs.getString("id"));
            m.put("label", rs.getString("label"));
            m.put("language", rs.getString("language"));
            m.put("email", rs.getString("email"));
            m.put("license", rs.getString("license"));
            m.put("version", rs.getString("version"));
            m.put("publisher", rs.getString("dc_publisher"));
            m.put("source", rs.getString("dc_source"));
            m.put("description", rs.getString("dc_description"));
            return m;
        });
        return rows.isEmpty() ? null : rows.get(0);
    }

    // ------------------------------------------------------------------ browse

    @Transactional(readOnly = true)
    public Map<String, Object> browse(String rawLetter, String posParam, int page, int size) {
        List<String> pos = parsePosList(posParam);
        int safeSize = clamp(size, 1, 100);
        int safePage = Math.max(page, 1);

        MapSqlParameterSource args = new MapSqlParameterSource();
        String posClause = "";
        if (!pos.isEmpty()) {
            posClause = " and e.part_of_speech in (:pos)";
            args.addValue("pos", pos);
        }
        Map<String, Long> counts = new LinkedHashMap<>();
        jdbc.query("select e.initial_letter, count(*) as n from lexical_entry e where 1 = 1" + posClause
                + " group by e.initial_letter", args, (RowCallbackHandler) rs ->
                counts.put(rs.getString(1), rs.getLong(2)));

        String letter = rawLetter == null ? "" : BengaliText.key(rawLetter);
        if (!ALPHABET.contains(letter)) {
            letter = ALPHABET.stream().filter(l -> counts.getOrDefault(l, 0L) > 0).findFirst().orElse(ALPHABET.get(0));
        }
        long total = counts.getOrDefault(letter, 0L);

        args.addValue("letter", letter)
                .addValue("limit", safeSize)
                .addValue("offset", (long) (safePage - 1) * safeSize);
        List<Map<String, Object>> items = entryCards("""
                select e.id, e.written_form, e.part_of_speech, e.origin
                from lexical_entry e
                where e.initial_letter = :letter""" + posClause + """

                order by e.written_form collate "C", e.id
                limit :limit offset :offset
                """, args, null);

        List<Map<String, Object>> letters = new ArrayList<>();
        for (String l : ALPHABET) {
            letters.add(Map.of("letter", l, "count", counts.getOrDefault(l, 0L)));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("letter", letter);
        body.put("letters", letters);
        body.put("pos", pos);
        body.put("items", items);
        body.putAll(pageInfo(safePage, safeSize, total));
        return body;
    }

    // ------------------------------------------------------------------ word search

    /** Exact matches first, then prefix matches, then (for 2+ characters) matches anywhere in the word. */
    @Transactional(readOnly = true)
    public Map<String, Object> searchWords(String q, String posParam, int page, int size) {
        String key = BengaliText.key(q);
        List<String> pos = parsePosList(posParam);
        int safeSize = clamp(size, 1, 100);
        int safePage = Math.max(page, 1);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("q", key);
        body.put("pos", pos);
        if (key.isEmpty() || BengaliText.length(key) > MAX_QUERY) {
            body.put("items", List.of());
            body.put("counts", Map.of("exact", 0, "prefix", 0, "contains", 0));
            body.putAll(pageInfo(safePage, safeSize, 0));
            return body;
        }
        boolean infix = BengaliText.length(key) >= 2;
        String escaped = escapeLike(key);
        MapSqlParameterSource args = new MapSqlParameterSource("key", key)
                .addValue("prefix", escaped + "%")
                .addValue("contains", "%" + escaped + "%");
        StringBuilder where = new StringBuilder(infix ? " where e.form_key like :contains" : " where e.form_key like :prefix");
        if (!pos.isEmpty()) {
            where.append(" and e.part_of_speech in (:pos)");
            args.addValue("pos", pos);
        }
        String rank = "case when e.form_key = :key then 0 when e.form_key like :prefix then 1 else 2 end";

        long[] byRank = new long[3];
        jdbc.query("select " + rank + " as r, count(*) from lexical_entry e" + where + " group by 1", args,
                (RowCallbackHandler) rs -> byRank[rs.getInt(1)] = rs.getLong(2));
        long total = byRank[0] + byRank[1] + byRank[2];

        args.addValue("limit", safeSize).addValue("offset", (long) (safePage - 1) * safeSize);
        List<Map<String, Object>> items = entryCards(
                "select e.id, e.written_form, e.part_of_speech, e.origin, " + rank + " as match_rank"
                        + " from lexical_entry e" + where
                        + " order by match_rank, length(e.form_key), e.written_form collate \"C\", e.id"
                        + " limit :limit offset :offset",
                args, "match_rank");

        body.put("items", items);
        body.put("counts", Map.of("exact", byRank[0], "prefix", byRank[1], "contains", byRank[2]));
        body.putAll(pageInfo(safePage, safeSize, total));
        return body;
    }

    /** Typeahead for the search boxes: prefix matches first, topped up with infix matches. */
    @Transactional(readOnly = true)
    public Map<String, Object> suggest(String q, int limit) {
        String key = BengaliText.key(q);
        int safeLimit = clamp(limit, 1, 12);
        if (key.isEmpty() || BengaliText.length(key) > MAX_QUERY) {
            return Map.of("q", key, "items", List.of());
        }
        String escaped = escapeLike(key);
        MapSqlParameterSource args = new MapSqlParameterSource("key", key)
                .addValue("prefix", escaped + "%")
                .addValue("contains", "%" + escaped + "%")
                .addValue("limit", safeLimit);
        boolean infix = BengaliText.length(key) >= 2;
        String filter = infix ? ":contains" : ":prefix";
        List<Map<String, Object>> items = entryCards(
                "select e.id, e.written_form, e.part_of_speech, e.origin,"
                        + " case when e.form_key = :key then 0 when e.form_key like :prefix then 1 else 2 end as match_rank"
                        + " from lexical_entry e where e.form_key like " + filter
                        + " order by match_rank, length(e.form_key), e.written_form collate \"C\", e.id limit :limit",
                args, "match_rank");
        return Map.of("q", key, "items", items);
    }

    // ------------------------------------------------------------------ gloss / synset search

    @Transactional(readOnly = true)
    public Map<String, Object> searchGloss(String q, String posParam, int page, int size) {
        String query = q == null ? "" : BengaliText.key(q);
        List<String> pos = parsePosList(posParam);
        int safeSize = clamp(size, 1, 100);
        int safePage = Math.max(page, 1);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("q", query);
        body.put("pos", pos);
        if (BengaliText.length(query) < 2 || BengaliText.length(query) > MAX_QUERY) {
            body.put("items", List.of());
            body.put("tooShort", !query.isEmpty() && BengaliText.length(query) < 2);
            body.putAll(pageInfo(safePage, safeSize, 0));
            return body;
        }
        MapSqlParameterSource args = new MapSqlParameterSource("pat", "%" + escapeLike(query.toLowerCase()) + "%")
                .addValue("needle", query.toLowerCase());
        StringBuilder where = new StringBuilder(" where exists (select 1 from synset_definition d"
                + " where d.synset_id = s.id and lower(d.text) like :pat)");
        if (!pos.isEmpty()) {
            where.append(" and s.part_of_speech in (:pos)");
            args.addValue("pos", pos);
        }
        long total = jdbc.queryForObject("select count(*) from synset s" + where, args, Long.class);
        args.addValue("limit", safeSize).addValue("offset", (long) (safePage - 1) * safeSize);
        List<Map<String, Object>> items = synsetHits(
                "select s.id, s.ili, s.part_of_speech, s.lexicalized, s.definition_language, s.definition_text, s.origin"
                        + " from synset s" + where
                        + " order by (select min(strpos(lower(d.text), :needle)) from synset_definition d"
                        + " where d.synset_id = s.id and lower(d.text) like :pat),"
                        + " length(coalesce(s.definition_text, '')), s.id"
                        + " limit :limit offset :offset", args, false);
        body.put("items", items);
        body.putAll(pageInfo(safePage, safeSize, total));
        return body;
    }

    /** Synset id, ILI code, or a concept description (gloss search). */
    @Transactional(readOnly = true)
    public Map<String, Object> searchSynsets(String q, int page, int size) {
        String query = q == null ? "" : BengaliText.key(q);
        if (SYNSET_ID.matcher(query).matches() || ILI.matcher(query).matches()) {
            boolean byId = SYNSET_ID.matcher(query).matches();
            List<Map<String, Object>> items = synsetHits(
                    "select s.id, s.ili, s.part_of_speech, s.lexicalized, s.definition_language, s.definition_text, s.origin"
                            + " from synset s where " + (byId ? "s.id = :q" : "lower(s.ili) = :q") + " order by s.id",
                    new MapSqlParameterSource("q", query.toLowerCase()), true);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("q", query);
            body.put("mode", byId ? "id" : "ili");
            body.put("items", items);
            body.putAll(pageInfo(1, Math.max(items.size(), 1), items.size()));
            return body;
        }
        Map<String, Object> body = searchGloss(query, null, page, size);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) body.get("items");
        attachRelations(items);
        body.put("mode", "gloss");
        return body;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> sense(String rawId) {
        String id = rawId == null ? "" : rawId.trim().toLowerCase();
        List<Map<String, Object>> rows = jdbc.query("""
                select s.id, s.synset_id, s.origin, s.promoted_at, e.id as entry_id, e.written_form, e.part_of_speech,
                       syn.ili, syn.part_of_speech as synset_pos, syn.definition_language, syn.definition_text
                from sense s
                join lexical_entry e on e.id = s.lexical_entry_id
                join synset syn on syn.id = s.synset_id
                where s.id = :id
                """, new MapSqlParameterSource("id", id), (rs, i) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("senseId", rs.getString("id"));
            m.put("synsetId", rs.getString("synset_id"));
            m.put("entryId", rs.getString("entry_id"));
            m.put("lemma", rs.getString("written_form"));
            m.put("pos", rs.getString("part_of_speech"));
            m.put("ili", rs.getString("ili"));
            m.put("synsetPos", rs.getString("synset_pos"));
            m.put("defLang", rs.getString("definition_language"));
            m.put("defText", rs.getString("definition_text"));
            m.put("crowdsourced", "USER".equals(rs.getString("origin")));
            m.put("validatedAt", iso(rs.getTimestamp("promoted_at")));
            return m;
        });
        if (rows.isEmpty()) {
            throw ApiException.notFound("No sense with id " + id + ".");
        }
        Map<String, Object> hit = rows.get(0);
        hit.put("definitions", definitionService.forSynset((String) hit.get("synsetId")));
        hit.put("relations", senseRelations(List.of(id)).getOrDefault(id, List.of()));
        return hit;
    }

    // ------------------------------------------------------------------ word detail

    @Transactional(readOnly = true)
    public Map<String, Object> word(String id) {
        List<Map<String, Object>> rows = jdbc.query("""
                select id, written_form, part_of_speech, origin, source_submission_id, promoted_at
                from lexical_entry where id = :id
                """, new MapSqlParameterSource("id", id), (rs, i) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", rs.getString("id"));
            m.put("lemma", rs.getString("written_form"));
            m.put("pos", rs.getString("part_of_speech"));
            m.put("origin", rs.getString("origin"));
            m.put("submissionId", rs.getString("source_submission_id"));
            m.put("validatedAt", iso(rs.getTimestamp("promoted_at")));
            return m;
        });
        if (rows.isEmpty()) {
            throw ApiException.notFound("Word not found.");
        }
        Map<String, Object> entry = rows.get(0);

        List<Map<String, Object>> senses = jdbc.query("""
                select s.id, s.synset_id, s.origin, s.source_submission_id, s.promoted_at,
                       syn.ili, syn.part_of_speech as synset_pos, syn.lexicalized, syn.definition_language,
                       syn.definition_text, syn.origin as synset_origin
                from sense s join synset syn on syn.id = s.synset_id
                where s.lexical_entry_id = :id
                order by""" + " " + SENSE_ORDER, new MapSqlParameterSource("id", id), (rs, i) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", rs.getString("id"));
            m.put("synsetId", rs.getString("synset_id"));
            m.put("ili", rs.getString("ili"));
            m.put("synsetPos", rs.getString("synset_pos"));
            m.put("lexicalized", rs.getBoolean("lexicalized"));
            m.put("definition", definition(rs));
            m.put("crowdsourced", "USER".equals(rs.getString("origin")));
            m.put("newSynset", "USER".equals(rs.getString("synset_origin")));
            m.put("submissionId", rs.getString("source_submission_id"));
            m.put("validatedAt", iso(rs.getTimestamp("promoted_at")));
            return m;
        });

        List<String> senseIds = senses.stream().map(s -> (String) s.get("id")).toList();
        List<String> synsetIds = senses.stream().map(s -> (String) s.get("synsetId")).distinct().toList();
        Map<String, List<Map<String, Object>>> senseRels = senseRelations(senseIds);
        Map<String, Members> synonyms = members(synsetIds, id, 8);
        Map<String, List<Map<String, Object>>> synsetRels = synsetRelations(synsetIds, 4);
        Map<String, List<Map<String, Object>>> definitions = definitionService.bySynsetIds(synsetIds);
        for (Map<String, Object> s : senses) {
            String syn = (String) s.get("synsetId");
            s.put("definitions", definitions.getOrDefault(syn, List.of()));
            Members mem = synonyms.getOrDefault(syn, Members.EMPTY);
            s.put("synonyms", mem.words());
            s.put("synonymCount", mem.total());
            s.put("senseRelations", senseRels.getOrDefault((String) s.get("id"), List.of()));
            s.put("synsetRelations", synsetRels.getOrDefault(syn, List.of()));
        }

        boolean crowdsourced = senses.stream().anyMatch(s -> Boolean.TRUE.equals(s.get("crowdsourced")));
        entry.put("crowdsourced", crowdsourced);
        entry.put("senses", senses);
        String primary = synsetIds.isEmpty() ? null : synsetIds.get(0);
        entry.put("primarySynsetId", primary);
        entry.put("hypernymPath", primary == null ? List.of() : hypernymPath(primary));
        entry.put("graph", primary == null ? List.of() : neighbourhood(primary));
        return entry;
    }

    // ------------------------------------------------------------------ synset detail

    @Transactional(readOnly = true)
    public Map<String, Object> synset(String rawId) {
        String id = rawId == null ? "" : rawId.trim().toLowerCase();
        List<Map<String, Object>> rows = jdbc.query("""
                select id, ili, part_of_speech, lexicalized, definition_language, definition_text, origin,
                       source_submission_id, promoted_at
                from synset where id = :id
                """, new MapSqlParameterSource("id", id), (rs, i) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", rs.getString("id"));
            m.put("ili", rs.getString("ili"));
            m.put("pos", rs.getString("part_of_speech"));
            m.put("lexicalized", rs.getBoolean("lexicalized"));
            m.put("definition", definition(rs));
            m.put("crowdsourced", "USER".equals(rs.getString("origin")));
            m.put("submissionId", rs.getString("source_submission_id"));
            m.put("validatedAt", iso(rs.getTimestamp("promoted_at")));
            return m;
        });
        if (rows.isEmpty()) {
            throw ApiException.notFound("Synset not found.");
        }
        Map<String, Object> syn = rows.get(0);
        syn.put("definitions", definitionService.forSynset(id));
        Members mem = members(List.of(id), null, 200).getOrDefault(id, Members.EMPTY);
        syn.put("words", mem.words());
        syn.put("wordCount", mem.total());
        syn.put("relations", synsetRelations(List.of(id), 3).getOrDefault(id, List.of()));

        long incomingTotal = jdbc.queryForObject(
                "select count(*) from synset_relation where target_synset_id = :id",
                new MapSqlParameterSource("id", id), Long.class);
        List<Map<String, Object>> incoming = jdbc.query("""
                select t.relation_name, r.source_synset_id, s.definition_text
                from synset_relation r
                join synset_relation_type t on t.id = r.relation_type_id
                join synset s on s.id = r.source_synset_id
                where r.target_synset_id = :id
                order by t.relation_name, r.source_synset_id
                limit 20
                """, new MapSqlParameterSource("id", id), (rs, i) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("relType", rs.getString("relation_name"));
            m.put("source", rs.getString("source_synset_id"));
            m.put("definition", truncate(rs.getString("definition_text"), 110));
            return m;
        });
        Map<String, Members> sourceWords = members(
                incoming.stream().map(r -> (String) r.get("source")).distinct().toList(), null, 3);
        for (Map<String, Object> r : incoming) {
            r.put("words", sourceWords.getOrDefault((String) r.get("source"), Members.EMPTY).words());
        }
        syn.put("incoming", incoming);
        syn.put("incomingTotal", incomingTotal);
        syn.put("hypernymPath", hypernymPath(id));
        syn.put("graph", neighbourhood(id));
        return syn;
    }

    // ------------------------------------------------------------------ building blocks

    private List<Map<String, Object>> entryCards(String sql, MapSqlParameterSource args, String rankColumn) {
        List<Map<String, Object>> cards = jdbc.query(sql, args, (rs, i) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", rs.getString("id"));
            m.put("lemma", rs.getString("written_form"));
            m.put("pos", rs.getString("part_of_speech"));
            m.put("origin", rs.getString("origin"));
            if (rankColumn != null) {
                int r = rs.getInt(rankColumn);
                m.put("matchType", r == 0 ? "exact" : r == 1 ? "prefix" : "contains");
            }
            return m;
        });
        if (cards.isEmpty()) {
            return cards;
        }
        Map<String, Map<String, Object>> byId = new LinkedHashMap<>();
        cards.forEach(c -> byId.put((String) c.get("id"), c));
        jdbc.query("""
                select x.lexical_entry_id, x.senses, x.platform_senses, x.validated_at,
                       f.synset_id, syn.definition_language, syn.definition_text
                from (
                    select s.lexical_entry_id, count(*) as senses,
                           count(*) filter (where s.origin = 'USER') as platform_senses,
                           min(s.promoted_at) filter (where s.origin = 'USER') as validated_at
                    from sense s where s.lexical_entry_id in (:ids)
                    group by s.lexical_entry_id
                ) x
                left join lateral (
                    select s.synset_id from sense s where s.lexical_entry_id = x.lexical_entry_id
                    order by""" + " " + SENSE_ORDER + """
                     limit 1
                ) f on true
                left join synset syn on syn.id = f.synset_id
                """, new MapSqlParameterSource("ids", List.copyOf(byId.keySet())), (RowCallbackHandler) rs -> {
            Map<String, Object> c = byId.get(rs.getString("lexical_entry_id"));
            c.put("senseCount", rs.getInt("senses"));
            c.put("crowdsourced", rs.getInt("platform_senses") > 0);
            c.put("validatedAt", iso(rs.getTimestamp("validated_at")));
            c.put("synsetId", rs.getString("synset_id"));
            c.put("definition", truncate(rs.getString("definition_text"), 180));
            c.put("defLang", rs.getString("definition_language"));
        });
        Map<String, List<Map<String, Object>>> definitions = definitionService.bySynsetIds(
                cards.stream().map(c -> (String) c.get("synsetId")).filter(java.util.Objects::nonNull).toList());
        for (Map<String, Object> c : cards) {
            c.put("definitions", definitions.getOrDefault((String) c.get("synsetId"), List.of()));
            c.putIfAbsent("senseCount", 0);
            c.putIfAbsent("crowdsourced", false);
            c.putIfAbsent("validatedAt", null);
            c.putIfAbsent("synsetId", null);
            c.putIfAbsent("definition", null);
            c.putIfAbsent("defLang", null);
        }
        return cards;
    }

    private List<Map<String, Object>> synsetHits(String sql, MapSqlParameterSource args, boolean withRelations) {
        List<Map<String, Object>> items = jdbc.query(sql, args, (rs, i) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("synsetId", rs.getString("id"));
            m.put("ili", rs.getString("ili"));
            m.put("pos", rs.getString("part_of_speech"));
            m.put("lexicalized", rs.getBoolean("lexicalized"));
            m.put("defLang", rs.getString("definition_language"));
            m.put("defText", rs.getString("definition_text"));
            m.put("crowdsourced", "USER".equals(rs.getString("origin")));
            return m;
        });
        List<String> hitIds = items.stream().map(m -> (String) m.get("synsetId")).toList();
        Map<String, Members> words = members(hitIds, null, 12);
        Map<String, List<Map<String, Object>>> definitions = definitionService.bySynsetIds(hitIds);
        for (Map<String, Object> m : items) {
            m.put("definitions", definitions.getOrDefault((String) m.get("synsetId"), List.of()));
            Members mem = words.getOrDefault((String) m.get("synsetId"), Members.EMPTY);
            m.put("entries", mem.words());
            m.put("entryCount", mem.total());
        }
        if (withRelations) {
            attachRelations(items);
        }
        return items;
    }

    private void attachRelations(List<Map<String, Object>> items) {
        Map<String, List<Map<String, Object>>> rels = new HashMap<>();
        List<String> ids = items.stream().map(m -> (String) m.get("synsetId")).toList();
        if (!ids.isEmpty()) {
            jdbc.query("""
                    select r.source_synset_id, t.relation_name, r.target_synset_id
                    from synset_relation r join synset_relation_type t on t.id = r.relation_type_id
                    where r.source_synset_id in (:ids)
                    order by r.source_synset_id, t.relation_name, r.target_synset_id
                    """, new MapSqlParameterSource("ids", ids), (RowCallbackHandler) rs ->
                    rels.computeIfAbsent(rs.getString(1), k -> new ArrayList<>())
                            .add(Map.of("relType", rs.getString(2), "target", rs.getString(3))));
        }
        for (Map<String, Object> m : items) {
            m.put("relations", rels.getOrDefault((String) m.get("synsetId"), List.of()));
        }
    }

    private record Members(List<Map<String, Object>> words, int total) {
        static final Members EMPTY = new Members(List.of(), 0);
    }

    /** Words lexicalising each synset (excluding {@code exceptEntry}), at most {@code limit} per synset. */
    private Map<String, Members> members(Collection<String> synsetIds, String exceptEntry, int limit) {
        if (synsetIds.isEmpty()) {
            return Map.of();
        }
        Map<String, List<Map<String, Object>>> words = new LinkedHashMap<>();
        Map<String, Integer> totals = new HashMap<>();
        MapSqlParameterSource args = new MapSqlParameterSource("ids", List.copyOf(synsetIds))
                .addValue("except", exceptEntry == null ? "" : exceptEntry);
        jdbc.query("""
                select s.synset_id, e.id, e.written_form, s.origin
                from sense s join lexical_entry e on e.id = s.lexical_entry_id
                where s.synset_id in (:ids) and e.id <> :except
                order by s.synset_id, e.written_form collate "C", e.id
                """, args, (RowCallbackHandler) rs -> {
            String syn = rs.getString(1);
            totals.merge(syn, 1, Integer::sum);
            List<Map<String, Object>> list = words.computeIfAbsent(syn, k -> new ArrayList<>());
            if (list.size() < limit) {
                list.add(Map.of("id", rs.getString(2), "lemma", rs.getString(3),
                        "crowdsourced", "USER".equals(rs.getString(4))));
            }
        });
        Map<String, Members> out = new HashMap<>();
        words.forEach((k, v) -> out.put(k, new Members(v, totals.getOrDefault(k, v.size()))));
        return out;
    }

    private Map<String, List<Map<String, Object>>> senseRelations(List<String> senseIds) {
        Map<String, List<Map<String, Object>>> out = new HashMap<>();
        if (senseIds.isEmpty()) {
            return out;
        }
        jdbc.query("""
                select r.sense_id_1, t.relation_name, r.sense_id_2, e.id as entry_id, e.written_form
                from sense_relation r
                join sense_relation_type t on t.id = r.relation_type_id
                left join sense ts on ts.id = r.sense_id_2
                left join lexical_entry e on e.id = ts.lexical_entry_id
                where r.sense_id_1 in (:ids)
                order by r.sense_id_1, t.relation_name, e.written_form collate "C"
                """, new MapSqlParameterSource("ids", senseIds), (RowCallbackHandler) rs -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("relType", rs.getString("relation_name"));
            m.put("target", rs.getString("sense_id_2"));
            m.put("targetEntryId", rs.getString("entry_id"));
            m.put("targetLemma", rs.getString("written_form"));
            out.computeIfAbsent(rs.getString("sense_id_1"), k -> new ArrayList<>()).add(m);
        });
        return out;
    }

    private Map<String, List<Map<String, Object>>> synsetRelations(List<String> synsetIds, int wordsPerTarget) {
        Map<String, List<Map<String, Object>>> out = new HashMap<>();
        if (synsetIds.isEmpty()) {
            return out;
        }
        List<Map<String, Object>> all = new ArrayList<>();
        jdbc.query("""
                select r.source_synset_id, t.relation_name, r.target_synset_id, s.definition_text
                from synset_relation r
                join synset_relation_type t on t.id = r.relation_type_id
                join synset s on s.id = r.target_synset_id
                where r.source_synset_id in (:ids)
                order by r.source_synset_id, r.id
                """, new MapSqlParameterSource("ids", synsetIds), (RowCallbackHandler) rs -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("relType", rs.getString("relation_name"));
            m.put("target", rs.getString("target_synset_id"));
            m.put("definition", truncate(rs.getString("definition_text"), 110));
            all.add(m);
            out.computeIfAbsent(rs.getString("source_synset_id"), k -> new ArrayList<>()).add(m);
        });
        Map<String, Members> words = members(all.stream().map(m -> (String) m.get("target")).distinct().toList(),
                null, wordsPerTarget);
        for (Map<String, Object> m : all) {
            m.put("words", words.getOrDefault((String) m.get("target"), Members.EMPTY).words());
        }
        return out;
    }

    /** First-hypernym chain from a synset towards its root (cycle-safe, at most 12 steps). */
    private List<Map<String, Object>> hypernymPath(String synsetId) {
        List<Map<String, Object>> path = jdbc.query("""
                with recursive chain(id, depth, trail) as (
                    select cast(:start as varchar), 0, array[cast(:start as varchar)]
                    union all
                    select h.target, c.depth + 1, c.trail || h.target
                    from chain c
                    cross join lateral (
                        select cast(r.target_synset_id as varchar) as target
                        from synset_relation r
                        join synset_relation_type t on t.id = r.relation_type_id
                        where r.source_synset_id = c.id and t.relation_name = 'hypernym'
                        order by r.id limit 1
                    ) h
                    where c.depth < 12 and not h.target = any(c.trail)
                )
                select c.id, s.definition_text from chain c join synset s on s.id = c.id
                where c.depth > 0 order by c.depth
                """, new MapSqlParameterSource("start", synsetId), (rs, i) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("synsetId", rs.getString("id"));
            m.put("definition", truncate(rs.getString("definition_text"), 140));
            return m;
        });
        Map<String, Members> words = members(path.stream().map(m -> (String) m.get("synsetId")).toList(), null, 1);
        for (Map<String, Object> m : path) {
            m.put("words", words.getOrDefault((String) m.get("synsetId"), Members.EMPTY).words());
        }
        return path;
    }

    /** Focus synset plus its outgoing relations and up to 14 incoming ones (inverse labels), max 24 nodes. */
    private List<Map<String, Object>> neighbourhood(String synsetId) {
        List<Map<String, Object>> nodes = new ArrayList<>();
        List<Map<String, Object>> centre = jdbc.query("select id, definition_text from synset where id = :id",
                new MapSqlParameterSource("id", synsetId), (rs, i) -> node(rs.getString(1), rs.getString(2), "centre", "self"));
        if (centre.isEmpty()) {
            return nodes;
        }
        nodes.addAll(centre);
        nodes.addAll(jdbc.query("""
                select r.target_synset_id, s.definition_text, t.relation_name
                from synset_relation r
                join synset_relation_type t on t.id = r.relation_type_id
                join synset s on s.id = r.target_synset_id
                where r.source_synset_id = :id
                order by r.id
                """, new MapSqlParameterSource("id", synsetId),
                (rs, i) -> node(rs.getString(1), rs.getString(2), rs.getString(3), "out")));
        nodes.addAll(jdbc.query("""
                select r.source_synset_id, s.definition_text, t.relation_name
                from synset_relation r
                join synset_relation_type t on t.id = r.relation_type_id
                join synset s on s.id = r.source_synset_id
                where r.target_synset_id = :id
                order by r.id
                limit 14
                """, new MapSqlParameterSource("id", synsetId),
                (rs, i) -> node(rs.getString(1), rs.getString(2), inverse(rs.getString(3)), "in")));
        List<Map<String, Object>> trimmed = new ArrayList<>(nodes.subList(0, Math.min(24, nodes.size())));
        Set<String> ids = new LinkedHashSet<>();
        trimmed.forEach(n -> ids.add((String) n.get("synsetId")));
        Map<String, Members> words = members(ids, null, 3);
        for (Map<String, Object> n : trimmed) {
            n.put("words", words.getOrDefault((String) n.get("synsetId"), Members.EMPTY).words());
        }
        return trimmed;
    }

    private static Map<String, Object> node(String id, String definition, String relation, String direction) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("synsetId", id);
        m.put("definition", truncate(definition, 140));
        m.put("relation", relation);
        m.put("direction", direction);
        return m;
    }

    private static String inverse(String rel) {
        if ("hypernym".equals(rel)) {
            return "hyponym";
        }
        return rel != null && rel.startsWith("mero") ? rel.replaceFirst("mero", "holo") : rel;
    }

    private static Map<String, Object> definition(ResultSet rs) throws SQLException {
        String text = rs.getString("definition_text");
        if (text == null) {
            return null;
        }
        String lang = rs.getString("definition_language");
        return Map.of("language", lang != null ? lang : "ben", "text", text);
    }

    private static Map<String, Object> pageInfo(int page, int size, long total) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("page", page);
        m.put("size", size);
        m.put("total", total);
        m.put("totalPages", Math.max(1, (int) Math.ceil(total / (double) size)));
        return m;
    }

    private static List<String> parsePosList(String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return out;
        }
        for (String p : raw.split(",")) {
            try {
                String code = PartOfSpeech.from(p.trim()).getCode();
                if (!out.contains(code)) {
                    out.add(code);
                }
            } catch (Exception ignored) {
                // unknown codes are ignored so a stale link still shows results
            }
        }
        return out;
    }

    private static int clamp(int v, int min, int max) {
        return Math.min(Math.max(v, min), max);
    }

    private static String escapeLike(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static String truncate(String text, int max) {
        if (text == null) {
            return null;
        }
        return text.length() <= max ? text : text.substring(0, max).stripTrailing() + "…";
    }

    private static String iso(Timestamp ts) {
        return ts == null ? null : ts.toInstant().toString();
    }
}
