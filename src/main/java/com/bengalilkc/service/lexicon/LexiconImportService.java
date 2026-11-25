package com.bengalilkc.service.lexicon;

import com.bengalilkc.domain.entity.lexicon.LexicalEntryEntity;
import com.bengalilkc.domain.entity.lexicon.Lexicon;
import com.bengalilkc.domain.entity.lexicon.SenseEntity;
import com.bengalilkc.domain.entity.lexicon.SenseRelationEntity;
import com.bengalilkc.domain.entity.lexicon.SenseRelationType;
import com.bengalilkc.domain.entity.lexicon.SynsetEntity;
import com.bengalilkc.domain.entity.lexicon.SynsetRelationEntity;
import com.bengalilkc.domain.entity.lexicon.SynsetRelationType;
import com.bengalilkc.common.DefinitionPayload;
import com.bengalilkc.domain.enums.DefinitionLanguage;
import com.bengalilkc.domain.enums.PartOfSpeech;
import com.bengalilkc.repository.lexicon.LexicalEntryRepository;
import com.bengalilkc.repository.lexicon.LexiconRepository;
import com.bengalilkc.repository.lexicon.SenseRelationRepository;
import com.bengalilkc.repository.lexicon.SenseRelationTypeRepository;
import com.bengalilkc.repository.lexicon.SenseRepository;
import com.bengalilkc.repository.lexicon.SynsetRelationRepository;
import com.bengalilkc.repository.lexicon.SynsetRelationTypeRepository;
import com.bengalilkc.repository.lexicon.SynsetRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Imports the canonical UKC LMF corpus ({@code Bangla Mono Lingual Lexicons.xml})
 * into core lexicon tables with full sense/synset relations.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LexiconImportService {

    private static final int BATCH = 500;
    private static final String DEFAULT_LEXICON_ID = "livelanguage-ben";

    private final LexicalEntryRepository entryRepository;
    private final SenseRepository senseRepository;
    private final SynsetRepository synsetRepository;
    private final SenseRelationRepository senseRelationRepository;
    private final SynsetRelationRepository synsetRelationRepository;
    private final SenseRelationTypeRepository senseRelationTypeRepository;
    private final SynsetRelationTypeRepository synsetRelationTypeRepository;
    private final LexiconRepository lexiconRepository;
    private final SynsetDefinitionService definitionService;

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * Wipe only core LKC lexicon graph data. Does <strong>not</strong> touch
     * identity (users/credentials), workflow, community, system_config or the WordNet ILI registry.
     */
    @Transactional
    public void clearCoreLexiconData() {
        log.warn("Clearing core lexicon tables (relations → senses → entries → definitions → synsets)");
        entityManager.createNativeQuery("TRUNCATE TABLE sense_relation, synset_relation, sense, lexical_entry, synset_definition, synset RESTART IDENTITY CASCADE")
                .executeUpdate();
        entityManager.flush();
        entityManager.clear();
        log.warn("Core lexicon tables cleared");
    }

    @Transactional(timeout = 3600)
    public ImportStats importFromXml(Path path) throws Exception {
        return importFromXml(path, false);
    }

    @Transactional(timeout = 3600)
    public ImportStats importFromXml(Path path, boolean force) throws Exception {
        if (!Files.exists(path)) {
            throw new IllegalArgumentException("Lexicon XML not found: " + path.toAbsolutePath());
        }

        long existing = entryRepository.count();
        if (!force && existing > 100) {
            log.info("Lexicon already populated ({} entries) — skipping XML import", existing);
            return ImportStats.skipped(existing);
        }
        if (force || (existing > 0 && existing <= 100)) {
            clearCoreLexiconData();
        }

        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);

        Map<String, LexicalEntryEntity> entries = new LinkedHashMap<>();
        Map<String, SynsetEntity> synsets = new LinkedHashMap<>();
        Map<String, SenseEntity> senses = new LinkedHashMap<>();
        List<PendingSenseRel> pendingSenseRels = new ArrayList<>();
        List<PendingSynsetRel> pendingSynsetRels = new ArrayList<>();

        LexiconMeta meta = null;
        final String[] lexiconIdHolder = {DEFAULT_LEXICON_ID};
        LexicalEntryEntity currentEntry = null;
        SynsetEntity currentSynset = null;
        SenseEntity currentSense = null;
        boolean inDefinition = false;
        StringBuilder definitionText = null;
        String definitionLanguage = null;
        /** Every {@code <Definition>} per synset, at most one per language (the first one wins). */
        Map<String, List<DefinitionPayload>> definitions = new LinkedHashMap<>();

        log.info("Parsing lexicon XML from {}", path.toAbsolutePath());
        try (InputStream in = Files.newInputStream(path)) {
            XMLStreamReader reader = factory.createXMLStreamReader(in);
            while (reader.hasNext()) {
                int event = reader.next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    String local = reader.getLocalName();
                    switch (local) {
                        case "Lexicon" -> {
                            lexiconIdHolder[0] = blankTo(attr(reader, "id"), DEFAULT_LEXICON_ID);
                            meta = new LexiconMeta(
                                    lexiconIdHolder[0],
                                    attr(reader, "label"),
                                    attr(reader, "language"),
                                    attr(reader, "email"),
                                    attr(reader, "license"),
                                    attr(reader, "version"),
                                    attrNs(reader, "http://purl.org/dc/elements/1.1/", "source"),
                                    attrNs(reader, "http://purl.org/dc/elements/1.1/", "description"),
                                    attrNs(reader, "http://purl.org/dc/elements/1.1/", "publisher"));
                        }
                        case "LexicalEntry" -> {
                            String id = attr(reader, "id");
                            currentEntry = LexicalEntryEntity.builder()
                                    .id(id)
                                    .lexiconId(lexiconIdHolder[0])
                                    .writtenForm("")
                                    .partOfSpeech(PartOfSpeech.N)
                                    .crowdsourced(false)
                                    .build();
                        }
                        case "Lemma" -> {
                            if (currentEntry != null) {
                                currentEntry.setWrittenForm(attr(reader, "writtenForm"));
                                currentEntry.setPartOfSpeech(PartOfSpeech.from(attr(reader, "partOfSpeech")));
                            }
                        }
                        case "Sense" -> {
                            String sid = attr(reader, "id");
                            String synsetId = attr(reader, "synset");
                            final LexicalEntryEntity entryRef = currentEntry;
                            SynsetEntity syn = synsets.computeIfAbsent(synsetId, k -> SynsetEntity.builder()
                                    .id(k)
                                    .lexiconId(lexiconIdHolder[0])
                                    .ili("n/a")
                                    .partOfSpeech(entryRef != null ? entryRef.getPartOfSpeech() : PartOfSpeech.N)
                                    .lexicalized(true)
                                    .crowdsourced(false)
                                    .build());
                            currentSense = SenseEntity.builder()
                                    .id(sid)
                                    .lexicalEntry(currentEntry)
                                    .synset(syn)
                                    .build();
                            senses.put(sid, currentSense);
                        }
                        case "SenseRelation" -> {
                            if (currentSense != null) {
                                pendingSenseRels.add(new PendingSenseRel(
                                        currentSense.getId(),
                                        attr(reader, "target"),
                                        attr(reader, "relType")));
                            }
                        }
                        case "Synset" -> {
                            String id = attr(reader, "id");
                            currentSynset = synsets.computeIfAbsent(id, k -> SynsetEntity.builder()
                                    .id(k)
                                    .lexiconId(lexiconIdHolder[0])
                                    .ili("n/a")
                                    .partOfSpeech(PartOfSpeech.N)
                                    .lexicalized(true)
                                    .crowdsourced(false)
                                    .build());
                            currentSynset.setIli(normalizeIli(attr(reader, "ili")));
                            currentSynset.setPartOfSpeech(PartOfSpeech.from(attr(reader, "partOfSpeech")));
                            String lex = attr(reader, "lexicalized");
                            currentSynset.setLexicalized(lex == null || lex.isBlank() || Boolean.parseBoolean(lex));
                        }
                        case "Definition" -> {
                            inDefinition = true;
                            definitionText = new StringBuilder();
                            String lang = attr(reader, "language");
                            definitionLanguage = lang.isBlank()
                                    ? DefinitionLanguage.BEN.getCode()
                                    : DefinitionLanguage.from(lang).getCode();
                        }
                        case "SynsetRelation" -> {
                            if (currentSynset != null) {
                                pendingSynsetRels.add(new PendingSynsetRel(
                                        currentSynset.getId(),
                                        attr(reader, "target"),
                                        attr(reader, "relType")));
                            }
                        }
                        default -> {
                        }
                    }
                } else if (event == XMLStreamConstants.CHARACTERS || event == XMLStreamConstants.CDATA) {
                    if (inDefinition && definitionText != null) {
                        definitionText.append(reader.getText());
                    }
                } else if (event == XMLStreamConstants.END_ELEMENT) {
                    String local = reader.getLocalName();
                    switch (local) {
                        case "LexicalEntry" -> {
                            if (currentEntry != null) {
                                if (currentEntry.getWrittenForm() == null || currentEntry.getWrittenForm().isBlank()) {
                                    log.warn("Skipping LexicalEntry {} — empty writtenForm", currentEntry.getId());
                                } else {
                                    entries.put(currentEntry.getId(), currentEntry);
                                }
                                currentEntry = null;
                            }
                        }
                        case "Sense" -> currentSense = null;
                        case "Synset" -> {
                            if (currentSynset != null) {
                                synsets.put(currentSynset.getId(), currentSynset);
                                currentSynset = null;
                            }
                        }
                        case "Definition" -> {
                            if (currentSynset != null && definitionText != null) {
                                String text = definitionText.toString().trim();
                                String language = definitionLanguage;
                                List<DefinitionPayload> list = definitions.computeIfAbsent(
                                        currentSynset.getId(), k -> new ArrayList<>());
                                if (!text.isEmpty() && list.stream().noneMatch(d -> d.language().equals(language))) {
                                    list.add(new DefinitionPayload(language, text));
                                }
                            }
                            inDefinition = false;
                            definitionText = null;
                            definitionLanguage = null;
                        }
                        default -> {
                        }
                    }
                }
            }
            reader.close();
        }

        upsertLexiconMeta(meta, lexiconIdHolder[0]);

        log.info(
                "Parsed XML: {} entries, {} synsets, {} senses, {} sense-rels, {} synset-rels — persisting…",
                entries.size(),
                synsets.size(),
                senses.size(),
                pendingSenseRels.size(),
                pendingSynsetRels.size());

        saveSynsets(synsets.values());
        saveEntries(entries.values());

        for (SenseEntity s : senses.values()) {
            LexicalEntryEntity e = entries.get(s.getLexicalEntry().getId());
            SynsetEntity syn = synsets.get(s.getSynset().getId());
            if (e == null || syn == null) {
                throw new IllegalStateException("Broken Sense " + s.getId() + " — missing entry or synset");
            }
            s.setLexicalEntry(e);
            s.setSynset(syn);
        }
        saveSenses(senses.values());

        int senseRelSaved = persistSenseRelations(pendingSenseRels, senses);
        int synsetRelSaved = persistSynsetRelations(pendingSynsetRels, synsets);
        entityManager.flush();
        definitions.keySet().retainAll(synsets.keySet());
        int definitionsSaved = definitionService.insertBatch(definitions);

        log.info(
                "Lexicon import complete: entries={}, synsets={}, senses={}, senseRels={}, synsetRels={}, definitions={}",
                entries.size(),
                synsets.size(),
                senses.size(),
                senseRelSaved,
                synsetRelSaved,
                definitionsSaved);

        return new ImportStats(
                entries.size(),
                synsets.size(),
                senses.size(),
                senseRelSaved,
                synsetRelSaved,
                definitionsSaved,
                false);
    }

    private void upsertLexiconMeta(LexiconMeta meta, String lexiconId) {
        Lexicon lexicon = lexiconRepository.findById(lexiconId).orElseGet(() -> Lexicon.builder()
                .id(lexiconId)
                .label("Bengali")
                .language("ben")
                .build());
        if (meta != null) {
            if (!blank(meta.label())) {
                lexicon.setLabel(meta.label());
            }
            if (!blank(meta.language())) {
                lexicon.setLanguage(meta.language());
            }
            lexicon.setEmail(blankToNull(meta.email()));
            lexicon.setLicense(blankToNull(meta.license()));
            lexicon.setVersion(blankToNull(meta.version()));
            lexicon.setDcSource(blankToNull(meta.dcSource()));
            lexicon.setDcDescription(blankToNull(meta.dcDescription()));
            lexicon.setDcPublisher(blankToNull(meta.dcPublisher()));
        }
        lexiconRepository.save(lexicon);
    }

    private int persistSenseRelations(List<PendingSenseRel> pending, Map<String, SenseEntity> senses) {
        Map<String, Long> typeIds = new HashMap<>();
        int saved = 0;
        int skipped = 0;
        for (PendingSenseRel rel : pending) {
            if (!senses.containsKey(rel.sourceId()) || !senses.containsKey(rel.targetId())) {
                skipped++;
                continue;
            }
            Long typeId = typeIds.computeIfAbsent(rel.relType(), name ->
                    senseRelationTypeRepository.findByRelationName(name)
                            .orElseGet(() -> senseRelationTypeRepository.save(
                                    SenseRelationType.builder().relationName(name).build()))
                            .getId());
            SenseEntity src = entityManager.getReference(SenseEntity.class, rel.sourceId());
            SenseEntity tgt = entityManager.getReference(SenseEntity.class, rel.targetId());
            SenseRelationType type = entityManager.getReference(SenseRelationType.class, typeId);
            senseRelationRepository.save(SenseRelationEntity.builder()
                    .sourceSense(src)
                    .targetSense(tgt)
                    .relationType(type)
                    .build());
            saved++;
            if (saved % BATCH == 0) {
                entityManager.flush();
                entityManager.clear();
                typeIds.clear();
                log.info("Sense relations persisted {} / {}", saved, pending.size());
            }
        }
        entityManager.flush();
        entityManager.clear();
        if (skipped > 0) {
            log.warn("Skipped {} sense relations with unresolved targets", skipped);
        }
        return saved;
    }

    private int persistSynsetRelations(List<PendingSynsetRel> pending, Map<String, SynsetEntity> synsets) {
        Map<String, Long> typeIds = new HashMap<>();
        int saved = 0;
        int skipped = 0;
        for (PendingSynsetRel rel : pending) {
            if (!synsets.containsKey(rel.sourceId()) || !synsets.containsKey(rel.targetId())) {
                skipped++;
                continue;
            }
            Long typeId = typeIds.computeIfAbsent(rel.relType(), name ->
                    synsetRelationTypeRepository.findByRelationName(name)
                            .orElseGet(() -> synsetRelationTypeRepository.save(
                                    SynsetRelationType.builder().relationName(name).build()))
                            .getId());
            SynsetEntity src = entityManager.getReference(SynsetEntity.class, rel.sourceId());
            SynsetEntity tgt = entityManager.getReference(SynsetEntity.class, rel.targetId());
            SynsetRelationType type = entityManager.getReference(SynsetRelationType.class, typeId);
            synsetRelationRepository.save(SynsetRelationEntity.builder()
                    .sourceSynset(src)
                    .targetSynset(tgt)
                    .relationType(type)
                    .build());
            saved++;
            if (saved % BATCH == 0) {
                entityManager.flush();
                entityManager.clear();
                typeIds.clear();
                log.info("Synset relations persisted {} / {}", saved, pending.size());
            }
        }
        entityManager.flush();
        entityManager.clear();
        if (skipped > 0) {
            log.warn("Skipped {} synset relations with unresolved targets", skipped);
        }
        return saved;
    }

    private void saveSynsets(Collection<SynsetEntity> entities) {
        List<SynsetEntity> list = new ArrayList<>(entities);
        for (int i = 0; i < list.size(); i += BATCH) {
            synsetRepository.saveAll(list.subList(i, Math.min(i + BATCH, list.size())));
            entityManager.flush();
            if ((i / BATCH) % 10 == 0) {
                log.info("Synsets persisted {} / {}", Math.min(i + BATCH, list.size()), list.size());
            }
        }
    }

    private void saveEntries(Collection<LexicalEntryEntity> entities) {
        List<LexicalEntryEntity> list = new ArrayList<>(entities);
        for (int i = 0; i < list.size(); i += BATCH) {
            entryRepository.saveAll(list.subList(i, Math.min(i + BATCH, list.size())));
            entityManager.flush();
            if ((i / BATCH) % 10 == 0) {
                log.info("Entries persisted {} / {}", Math.min(i + BATCH, list.size()), list.size());
            }
        }
    }

    private void saveSenses(Collection<SenseEntity> entities) {
        List<SenseEntity> list = new ArrayList<>(entities);
        for (int i = 0; i < list.size(); i += BATCH) {
            senseRepository.saveAll(list.subList(i, Math.min(i + BATCH, list.size())));
            entityManager.flush();
            if ((i / BATCH) % 10 == 0) {
                log.info("Senses persisted {} / {}", Math.min(i + BATCH, list.size()), list.size());
            }
        }
    }

    private static String normalizeIli(String ili) {
        if (ili == null || ili.isBlank()) {
            return "n/a";
        }
        String t = ili.trim();
        if ("n/a".equalsIgnoreCase(t) || "na".equalsIgnoreCase(t)) {
            return "n/a";
        }
        return t;
    }

    private static String attr(XMLStreamReader reader, String name) {
        String v = reader.getAttributeValue(null, name);
        return v == null ? "" : v;
    }

    private static String attrNs(XMLStreamReader reader, String ns, String local) {
        String v = reader.getAttributeValue(ns, local);
        if (v != null) {
            return v;
        }
        // Some writers omit namespace URI on attributes — try prefixed lookup via raw loop
        for (int i = 0; i < reader.getAttributeCount(); i++) {
            String ln = reader.getAttributeLocalName(i);
            String prefix = reader.getAttributePrefix(i);
            if (local.equals(ln) && ("dc".equals(prefix) || ns.equals(reader.getAttributeNamespace(i)))) {
                return reader.getAttributeValue(i);
            }
        }
        return "";
    }

    private static String blankTo(String v, String fallback) {
        return blank(v) ? fallback : v;
    }

    private static String blankToNull(String v) {
        return blank(v) ? null : v;
    }

    private static boolean blank(String v) {
        return v == null || v.isBlank();
    }

    public record ImportStats(
            int entries,
            int synsets,
            int senses,
            int senseRelations,
            int synsetRelations,
            int definitions,
            boolean skipped) {
        static ImportStats skipped(long existingEntries) {
            return new ImportStats((int) existingEntries, 0, 0, 0, 0, 0, true);
        }
    }

    private record LexiconMeta(
            String id,
            String label,
            String language,
            String email,
            String license,
            String version,
            String dcSource,
            String dcDescription,
            String dcPublisher) {}

    private record PendingSenseRel(String sourceId, String targetId, String relType) {}

    private record PendingSynsetRel(String sourceId, String targetId, String relType) {}
}
