package com.bengalilkc.service.lexicon;

import com.bengalilkc.common.DefinitionPayload;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Synset definitions ({@code synset_definition}): zero or more per synset, at most one per language.
 * Also reads / writes the definition lists stored on submissions ({@code definitions_json}).
 * Lists are always ordered Bengali first, then English.
 */
@Service
@RequiredArgsConstructor
public class SynsetDefinitionService {

    /** Supported definition languages, in display order. */
    public static final List<String> LANGUAGES = List.of("ben", "eng");

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public static int languageOrder(String language) {
        int i = LANGUAGES.indexOf(language);
        return i < 0 ? LANGUAGES.size() : i;
    }

    public static String languageName(String language) {
        return "eng".equals(language) ? "English" : "Bengali";
    }

    /** Definitions of each synset as {@code {language, text}} maps; synsets without any are absent. */
    public Map<String, List<Map<String, Object>>> bySynsetIds(Collection<String> synsetIds) {
        List<String> ids = synsetIds == null ? List.of()
                : synsetIds.stream().filter(Objects::nonNull).distinct().toList();
        Map<String, List<Map<String, Object>>> out = new HashMap<>();
        if (ids.isEmpty()) {
            return out;
        }
        jdbc.query("""
                select synset_id, language, text from synset_definition
                where synset_id in (:ids)
                order by synset_id, case language when 'ben' then 0 when 'eng' then 1 else 2 end
                """, new MapSqlParameterSource("ids", ids), (RowCallbackHandler) rs ->
                out.computeIfAbsent(rs.getString("synset_id"), k -> new ArrayList<>())
                        .add(toMap(rs.getString("language"), rs.getString("text"))));
        return out;
    }

    public List<Map<String, Object>> forSynset(String synsetId) {
        return synsetId == null ? List.of() : bySynsetIds(List.of(synsetId)).getOrDefault(synsetId, List.of());
    }

    /** Languages that already have a definition for the synset. */
    public Set<String> languagesOf(String synsetId) {
        if (synsetId == null || synsetId.isBlank()) {
            return Set.of();
        }
        return new LinkedHashSet<>(jdbc.queryForList(
                "select language from synset_definition where synset_id = :id",
                new MapSqlParameterSource("id", synsetId), String.class));
    }

    /** Adds definitions; a language the synset already has is kept as it is. Returns the rows added. */
    public int insert(String synsetId, List<DefinitionPayload> definitions, String sourceSubmissionId) {
        int added = 0;
        for (DefinitionPayload d : sorted(definitions)) {
            added += jdbc.update("""
                    insert into synset_definition (synset_id, language, text, source_submission_id)
                    values (:synset, :lang, :text, :sub)
                    on conflict (synset_id, language) do nothing
                    """, new MapSqlParameterSource("synset", synsetId).addValue("lang", d.language())
                    .addValue("text", d.text()).addValue("sub", sourceSubmissionId));
        }
        return added;
    }

    /** Bulk insert for the XML import (synsets must already be flushed). Returns the rows added. */
    public int insertBatch(Map<String, List<DefinitionPayload>> bySynset) {
        List<MapSqlParameterSource> rows = new ArrayList<>();
        bySynset.forEach((synsetId, defs) -> sorted(defs).forEach(d -> rows.add(
                new MapSqlParameterSource("synset", synsetId).addValue("lang", d.language()).addValue("text", d.text()))));
        int added = 0;
        for (int i = 0; i < rows.size(); i += 1000) {
            for (int n : jdbc.batchUpdate("""
                    insert into synset_definition (synset_id, language, text)
                    values (:synset, :lang, :text)
                    on conflict (synset_id, language) do nothing
                    """, rows.subList(i, Math.min(i + 1000, rows.size())).toArray(MapSqlParameterSource[]::new))) {
                added += Math.max(n, 0);
            }
        }
        return added;
    }

    /** Removes the definitions a submission added (when it leaves VALIDATED). */
    public void deleteAddedBy(String submissionId) {
        jdbc.update("delete from synset_definition where source_submission_id = :sub",
                new MapSqlParameterSource("sub", submissionId));
    }

    // ------------------------------------------------------------------ submission JSON

    public List<DefinitionPayload> read(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<DefinitionPayload> rows = objectMapper.readValue(json, new TypeReference<>() {});
            return sorted(rows.stream()
                    .filter(d -> d != null && d.language() != null && d.text() != null && !d.text().isBlank())
                    .toList());
        } catch (Exception e) {
            return List.of();
        }
    }

    public String write(List<DefinitionPayload> definitions) {
        List<DefinitionPayload> rows = sorted(definitions);
        if (rows.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(rows);
        } catch (Exception e) {
            throw new IllegalStateException("Could not serialise definitions", e);
        }
    }

    public static List<Map<String, Object>> toMaps(List<DefinitionPayload> definitions) {
        return sorted(definitions).stream().map(d -> toMap(d.language(), d.text())).toList();
    }

    /** Bengali first, then English; one row per language (first wins). */
    public static List<DefinitionPayload> sorted(List<DefinitionPayload> definitions) {
        if (definitions == null || definitions.isEmpty()) {
            return List.of();
        }
        Map<String, DefinitionPayload> byLang = new LinkedHashMap<>();
        for (DefinitionPayload d : definitions) {
            if (d != null && d.language() != null) {
                byLang.putIfAbsent(d.language(), d);
            }
        }
        List<DefinitionPayload> out = new ArrayList<>(byLang.values());
        out.sort(Comparator.comparingInt(d -> languageOrder(d.language())));
        return List.copyOf(out);
    }

    /** "[ben] text; [eng] text" — for change logs. */
    public static String label(List<DefinitionPayload> definitions) {
        List<DefinitionPayload> rows = sorted(definitions);
        if (rows.isEmpty()) {
            return null;
        }
        return String.join("; ", rows.stream().map(d -> "[" + d.language() + "] " + d.text()).toList());
    }

    private static Map<String, Object> toMap(String language, String text) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("language", language);
        m.put("text", text);
        return m;
    }
}
