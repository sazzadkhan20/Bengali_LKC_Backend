package com.bengalilkc.service.lexicon;

import com.bengalilkc.common.BengaliText;
import com.bengalilkc.common.exception.ApiException;
import com.bengalilkc.domain.entity.lexicon.LexicalEntryEntity;
import com.bengalilkc.domain.entity.lexicon.SenseEntity;
import com.bengalilkc.domain.entity.lexicon.SynsetEntity;
import com.bengalilkc.domain.entity.lexicon.SynsetRelationEntity;
import com.bengalilkc.domain.entity.lexicon.WordnetIli;
import com.bengalilkc.domain.enums.PartOfSpeech;
import com.bengalilkc.repository.lexicon.LexicalEntryRepository;
import com.bengalilkc.repository.lexicon.SenseRepository;
import com.bengalilkc.repository.lexicon.SynsetRelationRepository;
import com.bengalilkc.repository.lexicon.SynsetRepository;
import com.bengalilkc.repository.lexicon.WordnetIliRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class LexiconQueryService {

    private static final int LOOKUP_LIMIT = 15;

    private final LexicalEntryRepository entryRepository;
    private final SynsetRepository synsetRepository;
    private final SenseRepository senseRepository;
    private final SynsetRelationRepository synsetRelationRepository;
    private final WordnetIliRepository wordnetIliRepository;
    private final NamedParameterJdbcTemplate jdbc;

    /** count(*) scans the whole table in PostgreSQL, so corpus sizes are cached briefly. */
    private static final long COUNTS_TTL_MS = 60_000;

    private volatile Map<String, Long> cachedCounts;
    private volatile long cachedCountsAt;

    private Map<String, Long> corpusCounts() {
        Map<String, Long> c = cachedCounts;
        if (c != null && System.currentTimeMillis() - cachedCountsAt < COUNTS_TTL_MS) {
            return c;
        }
        Map<String, Long> fresh = new LinkedHashMap<>();
        fresh.put("entries", entryRepository.count());
        fresh.put("synsets", synsetRepository.count());
        fresh.put("senses", senseRepository.count());
        fresh.put("iliRegistry", wordnetIliRepository.count());
        cachedCounts = fresh;
        cachedCountsAt = System.currentTimeMillis();
        return fresh;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> publicStats() {
        return new LinkedHashMap<>(corpusCounts());
    }

    /** Sense-relation target search — by Bengali word (returns sense ids). */
    @Transactional(readOnly = true)
    public Map<String, Object> lookupWords(String q, String posParam) {
        if (q == null || q.trim().length() < 1) {
            return Map.of("results", List.of());
        }
        PartOfSpeech pos = parsePos(posParam);
        List<Map<String, Object>> results = new ArrayList<>();
        String prefix = BengaliText.key(q);
        if (prefix.isEmpty()) {
            return Map.of("results", List.of());
        }
        List<SenseEntity> senses = senseRepository.findForWordLookup(
                prefix, pos, PageRequest.of(0, LOOKUP_LIMIT));
        for (SenseEntity s : senses) {
            LexicalEntryEntity e = s.getLexicalEntry();
            SynsetEntity syn = s.getSynset();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("senseId", s.getId());
            row.put("synsetId", syn.getId());
            row.put("lemma", e.getWrittenForm());
            row.put("pos", e.getPartOfSpeech().getCode());
            row.put("definition", truncate(syn.getDefinitionText(), 90));
            row.put("origin", s.getOrigin() != null ? s.getOrigin() : "XML");
            results.add(row);
        }
        return Map.of("results", results);
    }

    /** Synset link / synset-relation target search — by word, id prefix, or definition snippet. */
    @Transactional(readOnly = true)
    public Map<String, Object> lookupSynsets(String q, String posParam) {
        if (q == null || q.trim().length() < 2) {
            return Map.of("results", List.of());
        }
        String query = q.trim();
        PartOfSpeech pos = parsePos(posParam);
        Map<String, SynsetEntity> found = new LinkedHashMap<>();

        if (query.toLowerCase().matches("^ben-syn\\d*$")) {
            for (SynsetEntity syn :
                    synsetRepository.findByIdStartingWith(query.toLowerCase(), PageRequest.of(0, LOOKUP_LIMIT * 2))) {
                if (pos == null || syn.getPartOfSpeech() == pos) {
                    found.putIfAbsent(syn.getId(), syn);
                }
                if (found.size() >= LOOKUP_LIMIT) {
                    break;
                }
            }
            return Map.of("results", synsetRows(found.values()));
        }

        for (SenseEntity s : senseRepository.findForWordLookup(
                BengaliText.key(query), pos, PageRequest.of(0, LOOKUP_LIMIT * 2))) {
            found.putIfAbsent(s.getSynset().getId(), s.getSynset());
            if (found.size() >= LOOKUP_LIMIT) {
                break;
            }
        }

        if (found.size() < LOOKUP_LIMIT) {
            int need = LOOKUP_LIMIT - found.size();
            for (SynsetEntity syn :
                    synsetRepository.searchByDefinition(query, pos, PageRequest.of(0, need + 5))) {
                found.putIfAbsent(syn.getId(), syn);
                if (found.size() >= LOOKUP_LIMIT) {
                    break;
                }
            }
        }
        return Map.of("results", synsetRows(found.values()));
    }

    @Transactional(readOnly = true)
    public Map<String, Object> lookupIli(String word, String posParam) {
        PartOfSpeech pos = parsePos(posParam);
        Map<String, Long> counts = corpusCounts();
        Map<String, Object> registry = new LinkedHashMap<>();
        registry.put("count", counts.get("iliRegistry"));
        registry.put("synsetCount", counts.get("iliRegistry"));
        registry.put("lemmaCount", counts.get("entries"));
        registry.put("version", "Bengali-UKC-seeded");
        registry.put("source", "wordnet_ili");

        if (word == null || word.trim().length() < 2) {
            return Map.of("results", List.of(), "registry", registry);
        }
        List<WordnetIli> hits = wordnetIliRepository.searchByWord(word.trim(), pos, PageRequest.of(0, 25));
        Set<String> mappedIlis = hits.isEmpty()
                ? Set.of()
                : new java.util.HashSet<>(synsetRepository.findExistingIlis(
                        hits.stream().map(WordnetIli::getIli).toList()));
        Map<String, String> proposedIn = new java.util.HashMap<>();
        if (!hits.isEmpty()) {
            jdbc.query("""
                    select lower(ili_code) as ili, min(id) as id from submission
                    where synset_action = 'create' and state <> 'REJECTED' and promoted_synset_id is null
                      and lower(ili_code) in (:ilis)
                    group by lower(ili_code)
                    """,
                    new MapSqlParameterSource("ilis", hits.stream().map(h -> h.getIli().toLowerCase()).toList()),
                    (org.springframework.jdbc.core.RowCallbackHandler) rs -> proposedIn.put(rs.getString("ili"), rs.getString("id")));
        }
        List<Map<String, Object>> results = new ArrayList<>();
        for (WordnetIli w : hits) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("ili", w.getIli());
            row.put("pos", w.getPos().getCode());
            row.put("words", w.getWordsCsv() != null ? List.of(w.getWordsCsv().split(",")) : List.of());
            row.put("gloss", w.getGloss());
            boolean mapped = mappedIlis.contains(w.getIli());
            row.put("alreadyMapped", mapped);
            row.put("alreadyInBengali", mapped);
            row.put("proposedIn", proposedIn.get(w.getIli().toLowerCase()));
            results.add(row);
        }
        return Map.of("results", results, "registry", registry);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> searchByLemma(String q, int limit) {
        List<LexicalEntryEntity> entries = entryRepository.findByWrittenFormStartingWithOrderByWrittenFormAsc(
                q == null ? "" : q.trim(), PageRequest.of(0, Math.min(Math.max(limit, 1), 50)));
        return Map.of("results", entries.stream().map(this::entrySummary).toList());
    }

    @Transactional(readOnly = true)
    public Map<String, Object> browseByLetter(String letter, int limit) {
        if (letter == null || letter.isBlank()) {
            throw ApiException.badRequest("letter is required");
        }
        return Map.of(
                "results",
                entryRepository
                        .findByInitialLetter(letter.substring(0, 1), PageRequest.of(0, Math.min(limit, 100)))
                        .stream()
                        .map(this::entrySummary)
                        .toList());
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getWord(String id) {
        LexicalEntryEntity e =
                entryRepository.findById(id).orElseThrow(() -> ApiException.notFound("Word not found"));
        Map<String, Object> m = entrySummary(e);
        List<Map<String, Object>> senses = new ArrayList<>();
        for (SenseEntity s : senseRepository.findByLexicalEntryId(id)) {
            Map<String, Object> sm = new LinkedHashMap<>();
            sm.put("id", s.getId());
            sm.put("synset", s.getSynset().getId());
            senses.add(sm);
        }
        m.put("senses", senses);
        return m;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getSynset(String id) {
        SynsetEntity syn =
                synsetRepository.findById(id).orElseThrow(() -> ApiException.notFound("Synset not found"));
        Map<String, Object> m = synsetRow(syn);
        List<Map<String, Object>> relations = new ArrayList<>();
        for (SynsetRelationEntity rel : synsetRelationRepository.findBySourceSynsetId(id)) {
            relations.add(Map.of(
                    "relType", rel.getRelationType().getRelationName(),
                    "target", rel.getTargetSynset().getId()));
        }
        m.put("relations", relations);
        m.put("lexicalized", syn.isLexicalized());
        if (syn.getDefinitionText() != null) {
            m.put(
                    "definition",
                    Map.of(
                            "language",
                            syn.getDefinitionLanguage() != null
                                    ? syn.getDefinitionLanguage().getCode()
                                    : "ben",
                            "text",
                            syn.getDefinitionText()));
        }
        return m;
    }

    private Map<String, Object> synsetRow(SynsetEntity syn) {
        return synsetRows(List.of(syn)).get(0);
    }

    /** Builds result rows with member words fetched in a single query for all synsets. */
    private List<Map<String, Object>> synsetRows(java.util.Collection<SynsetEntity> synsets) {
        if (synsets.isEmpty()) {
            return List.of();
        }
        Map<String, Set<String>> wordsBySynset = new java.util.HashMap<>();
        for (Object[] r : senseRepository.findLemmasBySynsetIds(
                synsets.stream().map(SynsetEntity::getId).toList())) {
            Set<String> w = wordsBySynset.computeIfAbsent((String) r[0], k -> new LinkedHashSet<>());
            if (w.size() < 5) {
                w.add((String) r[1]);
            }
        }
        List<Map<String, Object>> rows = new ArrayList<>(synsets.size());
        for (SynsetEntity syn : synsets) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", syn.getId());
            row.put("ili", syn.getIli());
            row.put("pos", syn.getPartOfSpeech().getCode());
            row.put("definition", truncate(syn.getDefinitionText(), 110));
            row.put("words", List.copyOf(wordsBySynset.getOrDefault(syn.getId(), Set.of())));
            row.put("origin", syn.getOrigin() != null ? syn.getOrigin() : "XML");
            rows.add(row);
        }
        return rows;
    }

    private Map<String, Object> entrySummary(LexicalEntryEntity e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", e.getId());
        m.put("lemma", e.getWrittenForm());
        m.put("pos", e.getPartOfSpeech().getCode());
        return m;
    }

    private static PartOfSpeech parsePos(String posParam) {
        if (posParam == null || posParam.isBlank() || "any".equalsIgnoreCase(posParam)) {
            return null;
        }
        try {
            return PartOfSpeech.from(posParam);
        } catch (Exception e) {
            return null;
        }
    }

    private static String truncate(String text, int max) {
        if (text == null) {
            return null;
        }
        return text.substring(0, Math.min(max, text.length()));
    }
}
