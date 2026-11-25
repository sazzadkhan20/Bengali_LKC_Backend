package com.bengalilkc.service.lexicon;

import com.bengalilkc.domain.enums.PartOfSpeech;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Princeton WordNet 3.0 ILI registry ({@code wordnet_ili} + ranked {@code wordnet_lemma}), loaded at
 * startup by {@link com.bengalilkc.bootstrap.WordnetRegistryLoader}. ILI codes in the UKC seed data are
 * WordNet 3.0 offsets: {@code {8-digit-offset}-{n|v|a|r}}.
 */
@Service
@RequiredArgsConstructor
public class WordnetRegistryService {

    public static final String VERSION = "3.0";

    private final NamedParameterJdbcTemplate jdbc;

    private volatile Map<String, Object> cachedStats;

    /** A WordNet 3.0 synset found by English word. */
    public record Hit(String ili, String pos, List<String> words, String gloss) {}

    /** Registry metadata, or null while it has not been loaded yet. */
    public Map<String, Object> stats() {
        Map<String, Object> s = cachedStats;
        if (s != null) {
            return s;
        }
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select version, synset_count, lemma_count, loaded_at from wordnet_registry where id = 1
                """, new MapSqlParameterSource());
        if (rows.isEmpty()) {
            return null;
        }
        Map<String, Object> r = rows.get(0);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("version", r.get("version"));
        m.put("synsetCount", ((Number) r.get("synset_count")).longValue());
        m.put("lemmaCount", ((Number) r.get("lemma_count")).longValue());
        m.put("source", "Princeton WordNet " + r.get("version"));
        cachedStats = m;
        return m;
    }

    public boolean isLoaded() {
        return stats() != null;
    }

    /** Called by the loader after a (re)load. */
    public void invalidate() {
        cachedStats = null;
    }

    public boolean exists(String ili) {
        if (ili == null || ili.isBlank()) {
            return false;
        }
        return !jdbc.queryForList("select 1 from wordnet_ili where ili = :ili",
                new MapSqlParameterSource("ili", ili.trim().toLowerCase()), Integer.class).isEmpty();
    }

    public Hit find(String ili) {
        if (ili == null || ili.isBlank()) {
            return null;
        }
        List<Hit> rows = jdbc.query("select ili, pos, words_csv, gloss from wordnet_ili where ili = :ili",
                new MapSqlParameterSource("ili", ili.trim().toLowerCase()),
                (rs, i) -> new Hit(rs.getString("ili"), rs.getString("pos"),
                        words(rs.getString("words_csv")), rs.getString("gloss")));
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * Synsets whose lemma matches the English word: exact lemma first (noun, verb, adjective, adverb,
     * each in WordNet sense order), then lemmas starting with the word, then (3+ characters) lemmas
     * containing it.
     */
    public List<Hit> search(String word, PartOfSpeech pos, int limit) {
        String q = normalize(word);
        if (q.length() < 2) {
            return List.of();
        }
        String escaped = q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        boolean infix = q.length() >= 3;
        MapSqlParameterSource args = new MapSqlParameterSource("q", q)
                .addValue("prefix", escaped + "%")
                .addValue("pattern", infix ? "%" + escaped + "%" : escaped + "%")
                .addValue("pos", pos == null ? null : pos.getCode())
                .addValue("limit", Math.max(1, Math.min(limit, 50)));
        return jdbc.query("""
                with matches as (
                    select l.ili, l.lemma, l.sense_rank,
                           case when l.lemma = :q then 0 when l.lemma like :prefix escape '\\' then 1 else 2 end as tier,
                           case l.pos when 'n' then 0 when 'v' then 1 when 'a' then 2 else 3 end as pos_order
                    from wordnet_lemma l
                    where l.lemma like :pattern escape '\\'
                      and (cast(:pos as varchar) is null or l.pos = cast(:pos as varchar))
                ), best as (
                    select distinct on (ili) ili, lemma, sense_rank, tier, pos_order
                    from matches
                    order by ili, tier, length(lemma), lemma, sense_rank
                )
                select w.ili, w.pos, w.words_csv, w.gloss
                from best b join wordnet_ili w on w.ili = b.ili
                order by b.tier,
                         case when b.tier = 0 then b.pos_order else 0 end,
                         case when b.tier = 0 then b.sense_rank else 0 end,
                         length(b.lemma), b.lemma, b.pos_order, b.sense_rank, w.ili
                limit :limit
                """, args, (rs, i) -> new Hit(rs.getString("ili"), rs.getString("pos"),
                words(rs.getString("words_csv")), rs.getString("gloss")));
    }

    /** Lower-case, underscores as spaces, whitespace collapsed — the lemma key used by the registry. */
    public static String normalize(String word) {
        if (word == null) {
            return "";
        }
        return word.replace('_', ' ').trim().replaceAll("\\s+", " ").toLowerCase();
    }

    public static List<String> words(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        return new ArrayList<>(Arrays.stream(csv.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList());
    }
}
