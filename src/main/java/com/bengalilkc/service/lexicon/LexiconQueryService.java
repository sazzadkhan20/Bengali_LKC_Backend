package com.bengalilkc.service.lexicon;

import com.bengalilkc.common.BengaliText;
import com.bengalilkc.common.exception.ApiException;
import com.bengalilkc.domain.entity.lexicon.LexicalEntryEntity;
import com.bengalilkc.domain.entity.lexicon.SenseEntity;
import com.bengalilkc.domain.entity.lexicon.SynsetEntity;
import com.bengalilkc.domain.entity.lexicon.SynsetRelationEntity;
import com.bengalilkc.domain.enums.PartOfSpeech;
import com.bengalilkc.repository.lexicon.LexicalEntryRepository;
import com.bengalilkc.repository.lexicon.SenseRepository;
import com.bengalilkc.repository.lexicon.SynsetRelationRepository;
import com.bengalilkc.repository.lexicon.SynsetRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.RowCallbackHandler;
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
    private final WordnetRegistryService registryService;
    private final SynsetDefinitionService definitionService;
    private final NamedParameterJdbcTemplate jdbc;

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
                    synsetRepository.searchByDefinition(query, pos == null ? null : pos.getCode(), PageRequest.of(0, need + 5))) {
                found.putIfAbsent(syn.getId(), syn);
                if (found.size() >= LOOKUP_LIMIT) {
                    break;
                }
            }
        }
        return Map.of("results", synsetRows(found.values()));
    }

    /**
     * ILI Finder: Princeton WordNet 3.0 synsets by English word and optional POS. Each hit says whether
     * the concept already has a Bengali synset ({@code alreadyInBengali} + its id) or is proposed by a
     * live submission ({@code proposedIn}); only free concepts can be used for a new synset.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> lookupIli(String word, String posParam) {
        PartOfSpeech pos = parsePos(posParam);
        Map<String, Object> stats = registryService.stats();
        Map<String, Object> registry = new LinkedHashMap<>();
        registry.put("version", WordnetRegistryService.VERSION);
        registry.put("source", "Princeton WordNet " + WordnetRegistryService.VERSION);
        registry.put("loaded", stats != null);
        registry.put("synsetCount", stats != null ? stats.get("synsetCount") : 0L);
        registry.put("lemmaCount", stats != null ? stats.get("lemmaCount") : 0L);

        if (stats == null || word == null || WordnetRegistryService.normalize(word).length() < 2) {
            return Map.of("results", List.of(), "registry", registry);
        }
        List<WordnetRegistryService.Hit> hits = registryService.search(word, pos, 25);
        List<String> ilis = hits.stream().map(WordnetRegistryService.Hit::ili).toList();
        Map<String, String> bengaliSynset = new java.util.HashMap<>();
        Map<String, String> proposedIn = new java.util.HashMap<>();
        if (!ilis.isEmpty()) {
            MapSqlParameterSource args = new MapSqlParameterSource("ilis", ilis);
            jdbc.query("""
                    select lower(ili) as ili, min(id) as id from synset
                    where lower(ili) in (:ilis)
                    group by lower(ili)
                    """, args, (RowCallbackHandler) rs -> bengaliSynset.put(rs.getString("ili"), rs.getString("id")));
            jdbc.query("""
                    select lower(ili_code) as ili, min(id) as id from submission
                    where synset_action = 'create' and state <> 'REJECTED' and promoted_synset_id is null
                      and lower(ili_code) in (:ilis)
                    group by lower(ili_code)
                    """, args, (RowCallbackHandler) rs -> proposedIn.put(rs.getString("ili"), rs.getString("id")));
        }
        List<Map<String, Object>> results = new ArrayList<>();
        for (WordnetRegistryService.Hit h : hits) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("ili", h.ili());
            row.put("pos", h.pos());
            row.put("words", h.words());
            row.put("gloss", h.gloss());
            String synsetId = bengaliSynset.get(h.ili());
            row.put("alreadyInBengali", synsetId != null);
            row.put("alreadyMapped", synsetId != null);
            row.put("synsetId", synsetId);
            row.put("proposedIn", synsetId == null ? proposedIn.get(h.ili()) : null);
            results.add(row);
        }
        return Map.of("results", results, "registry", registry);
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
        Map<String, List<Map<String, Object>>> definitions =
                definitionService.bySynsetIds(synsets.stream().map(SynsetEntity::getId).toList());
        List<Map<String, Object>> rows = new ArrayList<>(synsets.size());
        for (SynsetEntity syn : synsets) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", syn.getId());
            row.put("ili", syn.getIli());
            row.put("pos", syn.getPartOfSpeech().getCode());
            row.put("definition", truncate(syn.getDefinitionText(), 110));
            row.put("definitions", definitions.getOrDefault(syn.getId(), List.of()));
            row.put("words", List.copyOf(wordsBySynset.getOrDefault(syn.getId(), Set.of())));
            row.put("origin", syn.getOrigin() != null ? syn.getOrigin() : "XML");
            rows.add(row);
        }
        return rows;
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
