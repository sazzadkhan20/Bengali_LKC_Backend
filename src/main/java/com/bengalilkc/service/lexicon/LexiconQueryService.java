package com.bengalilkc.service.lexicon;

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
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class LexiconQueryService {

    private final LexicalEntryRepository entryRepository;
    private final SynsetRepository synsetRepository;
    private final SenseRepository senseRepository;
    private final SynsetRelationRepository synsetRelationRepository;
    private final WordnetIliRepository wordnetIliRepository;

    @Transactional(readOnly = true)
    public Map<String, Object> publicStats() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("entries", entryRepository.count());
        m.put("synsets", synsetRepository.count());
        m.put("senses", senseRepository.count());
        m.put("iliRegistry", wordnetIliRepository.count());
        return m;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> lookupWords(String q) {
        if (q == null || q.isBlank()) {
            return Map.of("results", List.of());
        }
        List<Map<String, Object>> results = new ArrayList<>();
        List<SenseEntity> senses = senseRepository.findForWordLookup(q.trim());
        for (SenseEntity s : senses) {
            if (results.size() >= 10) {
                break;
            }
            LexicalEntryEntity e = s.getLexicalEntry();
            SynsetEntity syn = s.getSynset();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("senseId", s.getId());
            row.put("synsetId", syn.getId());
            row.put("lemma", e.getWrittenForm());
            row.put("pos", e.getPartOfSpeech().getCode());
            row.put("definition", syn.getDefinitionText() != null
                    ? syn.getDefinitionText().substring(0, Math.min(90, syn.getDefinitionText().length()))
                    : null);
            results.add(row);
        }
        return Map.of("results", results);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> lookupSynsets(String q) {
        if (q == null || q.trim().length() < 2) {
            return Map.of("results", List.of());
        }
        String query = q.trim();
        List<Map<String, Object>> results = new ArrayList<>();
        if (query.toLowerCase().matches("^ben-syn\\d*$")) {
            for (SynsetEntity syn : synsetRepository.findByIdStartingWith(query.toLowerCase(), PageRequest.of(0, 10))) {
                results.add(synsetRow(syn));
            }
        } else {
            for (SenseEntity s : senseRepository.findForWordLookup(query)) {
                if (results.size() >= 10) {
                    break;
                }
                SynsetEntity syn = s.getSynset();
                if (results.stream().noneMatch(r -> r.get("id").equals(syn.getId()))) {
                    results.add(synsetRow(syn));
                }
            }
            if (results.size() < 10) {
                for (SynsetEntity syn : synsetRepository.searchByDefinition(query, PageRequest.of(0, 10))) {
                    if (results.size() >= 10) {
                        break;
                    }
                    if (results.stream().noneMatch(r -> r.get("id").equals(syn.getId()))) {
                        results.add(synsetRow(syn));
                    }
                }
            }
        }
        return Map.of("results", results);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> lookupIli(String word, String posParam) {
        PartOfSpeech pos = null;
        if (posParam != null && !"any".equalsIgnoreCase(posParam)) {
            try {
                pos = PartOfSpeech.from(posParam);
            } catch (Exception ignored) {
                // keep null = any
            }
        }
        Map<String, Object> registry = Map.of(
                "count", wordnetIliRepository.count(),
                "source", "wordnet_ili");
        if (word == null || word.trim().length() < 2) {
            return Map.of("results", List.of(), "registry", registry);
        }
        List<Map<String, Object>> results = new ArrayList<>();
        for (WordnetIli w : wordnetIliRepository.searchByWord(word.trim(), pos, PageRequest.of(0, 25))) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("ili", w.getIli());
            row.put("pos", w.getPos().getCode());
            row.put("words", w.getWordsCsv() != null ? List.of(w.getWordsCsv().split(",")) : List.of());
            row.put("gloss", w.getGloss());
            row.put("alreadyMapped", synsetRepository.existsByIli(w.getIli()));
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
        return Map.of("results", entryRepository.findByInitialLetter(letter.substring(0, 1), PageRequest.of(0, Math.min(limit, 100)))
                .stream().map(this::entrySummary).toList());
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getWord(String id) {
        LexicalEntryEntity e = entryRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound("Word not found"));
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
        SynsetEntity syn = synsetRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound("Synset not found"));
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
            m.put("definition", Map.of(
                    "language", syn.getDefinitionLanguage() != null ? syn.getDefinitionLanguage().getCode() : "ben",
                    "text", syn.getDefinitionText()));
        }
        return m;
    }

    private Map<String, Object> synsetRow(SynsetEntity syn) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", syn.getId());
        row.put("ili", syn.getIli());
        row.put("pos", syn.getPartOfSpeech().getCode());
        row.put("definition", syn.getDefinitionText() != null
                ? syn.getDefinitionText().substring(0, Math.min(110, syn.getDefinitionText().length()))
                : null);
        List<String> words = senseRepository.findBySynsetId(syn.getId()).stream()
                .map(s -> s.getLexicalEntry().getWrittenForm())
                .distinct()
                .limit(5)
                .toList();
        row.put("words", words);
        return row;
    }

    private Map<String, Object> entrySummary(LexicalEntryEntity e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", e.getId());
        m.put("lemma", e.getWrittenForm());
        m.put("pos", e.getPartOfSpeech().getCode());
        return m;
    }
}
