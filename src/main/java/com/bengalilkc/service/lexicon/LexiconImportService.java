package com.bengalilkc.service.lexicon;

import com.bengalilkc.domain.entity.lexicon.LexicalEntryEntity;
import com.bengalilkc.domain.entity.lexicon.SenseEntity;
import com.bengalilkc.domain.entity.lexicon.SenseRelationEntity;
import com.bengalilkc.domain.entity.lexicon.SenseRelationType;
import com.bengalilkc.domain.entity.lexicon.SynsetEntity;
import com.bengalilkc.domain.entity.lexicon.SynsetRelationEntity;
import com.bengalilkc.domain.entity.lexicon.SynsetRelationType;
import com.bengalilkc.domain.enums.DefinitionLanguage;
import com.bengalilkc.domain.enums.PartOfSpeech;
import com.bengalilkc.repository.lexicon.LexicalEntryRepository;
import com.bengalilkc.repository.lexicon.SenseRelationRepository;
import com.bengalilkc.repository.lexicon.SenseRelationTypeRepository;
import com.bengalilkc.repository.lexicon.SenseRepository;
import com.bengalilkc.repository.lexicon.SynsetRelationRepository;
import com.bengalilkc.repository.lexicon.SynsetRelationTypeRepository;
import com.bengalilkc.repository.lexicon.SynsetRepository;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class LexiconImportService {

    private final LexicalEntryRepository entryRepository;
    private final SenseRepository senseRepository;
    private final SynsetRepository synsetRepository;
    private final SenseRelationRepository senseRelationRepository;
    private final SynsetRelationRepository synsetRelationRepository;
    private final SenseRelationTypeRepository senseRelationTypeRepository;
    private final SynsetRelationTypeRepository synsetRelationTypeRepository;

    @Transactional
    public ImportStats importFromXml(Path path) throws Exception {
        if (!Files.exists(path)) {
            throw new IllegalArgumentException("Lexicon XML not found: " + path);
        }
        if (entryRepository.count() > 100) {
            log.info("Lexicon already populated ({} entries) — skipping XML import", entryRepository.count());
            return new ImportStats(0, 0, 0, true);
        }

        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);

        Map<String, LexicalEntryEntity> entries = new HashMap<>();
        Map<String, SynsetEntity> synsets = new HashMap<>();
        Map<String, SenseEntity> senses = new HashMap<>();
        List<PendingSenseRel> pendingSenseRels = new ArrayList<>();
        List<PendingSynsetRel> pendingSynsetRels = new ArrayList<>();

        String lexiconId = "livelanguage-ben";
        LexicalEntryEntity currentEntry = null;
        SynsetEntity currentSynset = null;
        SenseEntity currentSense = null;
        boolean inDefinition = false;
        StringBuilder definitionText = null;

        try (InputStream in = Files.newInputStream(path)) {
            XMLStreamReader reader = factory.createXMLStreamReader(in);
            while (reader.hasNext()) {
                int event = reader.next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    String local = reader.getLocalName();
                    switch (local) {
                        case "LexicalEntry" -> {
                            String id = reader.getAttributeValue(null, "id");
                            currentEntry = LexicalEntryEntity.builder()
                                    .id(id)
                                    .lexiconId(lexiconId)
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
                                    .lexiconId(lexiconId)
                                    .ili("pending-" + k)
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
                                    .lexiconId(lexiconId)
                                    .ili("pending")
                                    .partOfSpeech(PartOfSpeech.N)
                                    .lexicalized(true)
                                    .crowdsourced(false)
                                    .build());
                            currentSynset.setIli(attr(reader, "ili"));
                            currentSynset.setPartOfSpeech(PartOfSpeech.from(attr(reader, "partOfSpeech")));
                            String lex = attr(reader, "lexicalized");
                            currentSynset.setLexicalized(lex == null || Boolean.parseBoolean(lex));
                        }
                        case "Definition" -> {
                            inDefinition = true;
                            definitionText = new StringBuilder();
                            if (currentSynset != null) {
                                String lang = attr(reader, "language");
                                if (lang != null && !lang.isBlank()) {
                                    currentSynset.setDefinitionLanguage(DefinitionLanguage.from(lang));
                                }
                            }
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
                                entries.put(currentEntry.getId(), currentEntry);
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
                                currentSynset.setDefinitionText(definitionText.toString().trim());
                            }
                            inDefinition = false;
                            definitionText = null;
                        }
                        default -> {
                        }
                    }
                }
            }
            reader.close();
        }

        log.info("Parsed lexicon XML: {} entries, {} synsets, {} senses", entries.size(), synsets.size(), senses.size());
        synsetRepository.saveAll(synsets.values());
        entryRepository.saveAll(entries.values());
        for (SenseEntity s : senses.values()) {
            s.setLexicalEntry(entries.get(s.getLexicalEntry().getId()));
            s.setSynset(synsets.get(s.getSynset().getId()));
        }
        senseRepository.saveAll(senses.values());

        Map<String, SenseRelationType> senseTypes = new HashMap<>();
        for (PendingSenseRel rel : pendingSenseRels) {
            SenseEntity src = senses.get(rel.sourceId);
            SenseEntity tgt = senses.get(rel.targetId);
            if (src == null || tgt == null) {
                continue;
            }
            SenseRelationType type = senseTypes.computeIfAbsent(rel.relType, name ->
                    senseRelationTypeRepository.findByRelationName(name)
                            .orElseGet(() -> senseRelationTypeRepository.save(
                                    SenseRelationType.builder().relationName(name).build())));
            senseRelationRepository.save(SenseRelationEntity.builder()
                    .sourceSense(src)
                    .targetSense(tgt)
                    .relationType(type)
                    .build());
        }

        Map<String, SynsetRelationType> synTypes = new HashMap<>();
        for (PendingSynsetRel rel : pendingSynsetRels) {
            SynsetEntity src = synsets.get(rel.sourceId);
            SynsetEntity tgt = synsets.get(rel.targetId);
            if (src == null || tgt == null) {
                continue;
            }
            SynsetRelationType type = synTypes.computeIfAbsent(rel.relType, name ->
                    synsetRelationTypeRepository.findByRelationName(name)
                            .orElseGet(() -> synsetRelationTypeRepository.save(
                                    SynsetRelationType.builder().relationName(name).build())));
            synsetRelationRepository.save(SynsetRelationEntity.builder()
                    .sourceSynset(src)
                    .targetSynset(tgt)
                    .relationType(type)
                    .build());
        }

        return new ImportStats(entries.size(), synsets.size(), senses.size(), false);
    }

    private static String attr(XMLStreamReader reader, String name) {
        String v = reader.getAttributeValue(null, name);
        return v == null ? "" : v;
    }

    public record ImportStats(int entries, int synsets, int senses, boolean skipped) {}

    private record PendingSenseRel(String sourceId, String targetId, String relType) {}

    private record PendingSynsetRel(String sourceId, String targetId, String relType) {}
}
