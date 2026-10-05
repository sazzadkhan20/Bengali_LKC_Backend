package com.bengalilkc.service.lexicon;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Aggregate counts over the canonical lexicon (original XML plus promoted platform entries).
 * The lexicon only grows through validation, so a short cache keeps public pages cheap.
 */
@Service
@RequiredArgsConstructor
public class LexiconStatsService {

    private static final Duration TTL = Duration.ofSeconds(60);

    private final NamedParameterJdbcTemplate jdbc;

    private volatile Map<String, Object> cached;
    private volatile Instant cachedAt = Instant.EPOCH;

    public Map<String, Object> stats() {
        Map<String, Object> snapshot = cached;
        if (snapshot != null && Instant.now().isBefore(cachedAt.plus(TTL))) {
            return snapshot;
        }
        synchronized (this) {
            if (cached != null && Instant.now().isBefore(cachedAt.plus(TTL))) {
                return cached;
            }
            cached = compute();
            cachedAt = Instant.now();
            return cached;
        }
    }

    public void invalidate() {
        cachedAt = Instant.EPOCH;
    }

    private Map<String, Object> compute() {
        MapSqlParameterSource none = new MapSqlParameterSource();
        Map<String, Object> totals = jdbc.queryForMap("""
                select (select count(*) from lexical_entry) as entries,
                       (select count(*) from lexical_entry where origin = 'USER') as crowdsourced_entries,
                       (select count(*) from sense) as senses,
                       (select count(*) from sense where origin = 'USER') as crowdsourced_senses,
                       (select count(*) from synset) as synsets,
                       (select count(*) from synset where origin = 'USER') as crowdsourced_synsets,
                       (select count(*) from synset where definition_language = 'ben') as definitions_ben,
                       (select count(*) from synset where definition_language = 'eng') as definitions_eng,
                       (select count(*) from (select lexical_entry_id from sense
                          group by lexical_entry_id having count(*) > 1) p) as polysemous_entries,
                       (select count(*) from synset_relation) as synset_relations,
                       (select count(*) from sense_relation) as sense_relations
                """, none);

        Map<String, Object> m = new LinkedHashMap<>();
        totals.forEach((k, v) -> m.put(camel(k), ((Number) v).longValue()));
        m.put("relations", (Long) m.get("synsetRelations") + (Long) m.get("senseRelations"));
        m.put("generatedAt", Instant.now().toString());

        Map<String, Long> pos = new LinkedHashMap<>();
        for (String p : new String[] {"n", "v", "a", "r"}) {
            pos.put(p, 0L);
        }
        jdbc.query("select part_of_speech, count(*) as n from lexical_entry group by part_of_speech", none,
                rs -> {
                    pos.merge(rs.getString("part_of_speech"), rs.getLong("n"), Long::sum);
                });
        m.put("posDistribution", pos);
        m.put("synsetRelationCounts", relationCounts(
                "select t.relation_name, count(*) as n from synset_relation r"
                        + " join synset_relation_type t on t.id = r.relation_type_id group by t.relation_name"));
        m.put("senseRelationCounts", relationCounts(
                "select t.relation_name, count(*) as n from sense_relation r"
                        + " join sense_relation_type t on t.id = r.relation_type_id group by t.relation_name"));
        return m;
    }

    private Map<String, Long> relationCounts(String sql) {
        Map<String, Long> out = new LinkedHashMap<>();
        jdbc.query(sql + " order by n desc", new MapSqlParameterSource(), rs -> {
            out.put(rs.getString("relation_name"), rs.getLong("n"));
        });
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
}
