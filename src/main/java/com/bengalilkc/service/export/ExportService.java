package com.bengalilkc.service.export;

import com.bengalilkc.common.SenseRelationPayload;
import com.bengalilkc.common.SynsetRelationPayload;
import com.bengalilkc.common.exception.ApiException;
import com.bengalilkc.domain.entity.admin.ExportRecord;
import com.bengalilkc.domain.entity.lexicon.LexicalEntryEntity;
import com.bengalilkc.domain.entity.lexicon.Lexicon;
import com.bengalilkc.domain.entity.lexicon.SenseEntity;
import com.bengalilkc.domain.entity.lexicon.SynsetEntity;
import com.bengalilkc.domain.entity.lexicon.SynsetRelationEntity;
import com.bengalilkc.domain.entity.workflow.Submission;
import com.bengalilkc.domain.enums.AuditTargetType;
import com.bengalilkc.domain.enums.EntryState;
import com.bengalilkc.domain.enums.PartOfSpeech;
import com.bengalilkc.domain.enums.SynsetAction;
import com.bengalilkc.repository.admin.ExportRecordRepository;
import com.bengalilkc.repository.lexicon.LexicalEntryRepository;
import com.bengalilkc.repository.lexicon.LexiconRepository;
import com.bengalilkc.repository.lexicon.SenseRepository;
import com.bengalilkc.repository.lexicon.SynsetRelationRepository;
import com.bengalilkc.repository.lexicon.SynsetRepository;
import com.bengalilkc.repository.workflow.SubmissionRepository;
import com.bengalilkc.security.UserPrincipal;
import com.bengalilkc.service.admin.AuditService;
import com.bengalilkc.service.admin.IdGeneratorService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ExportService {

    private final LexiconRepository lexiconRepository;
    private final LexicalEntryRepository entryRepository;
    private final SenseRepository senseRepository;
    private final SynsetRepository synsetRepository;
    private final SynsetRelationRepository synsetRelationRepository;
    private final SubmissionRepository submissionRepository;
    private final ExportRecordRepository exportRecordRepository;
    private final IdGeneratorService idGeneratorService;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

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
        boolean includeCanonical = !"0".equals(params.get("canonical"));
        boolean includeLkcSource = "1".equals(params.get("lkcSource"));

        List<Submission> validatedSubs = submissionRepository.findByState(EntryState.VALIDATED).stream()
                .filter(s -> posFilter.isEmpty() || posFilter.contains(s.getPartOfSpeech()))
                .toList();
        List<LexicalEntryEntity> canonicalEntries = includeCanonical
                ? entryRepository.findAll().stream()
                        .filter(e -> !e.isCrowdsourced())
                        .filter(e -> posFilter.isEmpty() || posFilter.contains(e.getPartOfSpeech()))
                        .toList()
                : List.of();

        long nextSynNum = 2_100_000L;
        long nextSenNum = 3_300_000L;
        long nextWordNum = 2_300_000L;
        Map<String, String> newSynsetBySub = new HashMap<>();
        for (Submission s : validatedSubs) {
            if (s.getSynsetAction() == SynsetAction.CREATE && s.getIliCode() != null) {
                newSynsetBySub.put(s.getId(), "ben-syn" + (nextSynNum++));
            }
        }

        Set<String> usedSynsetIds = new HashSet<>();
        for (LexicalEntryEntity e : canonicalEntries) {
            for (SenseEntity sn : senseRepository.findByLexicalEntryId(e.getId())) {
                usedSynsetIds.add(sn.getSynset().getId());
            }
        }
        for (Submission s : validatedSubs) {
            usedSynsetIds.add(s.getSynsetAction() == SynsetAction.LINK
                    ? s.getSynsetId()
                    : newSynsetBySub.get(s.getId()));
        }

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("entries", canonicalEntries.size() + validatedSubs.size());
        stats.put("crowdsourced", validatedSubs.size());
        long knownSynsets = usedSynsetIds.stream().filter(id -> id != null && synsetRepository.existsById(id)).count();
        stats.put("synsets", knownSynsets + newSynsetBySub.size());

        if (dryRun) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("dryRun", true);
            body.put("projected", stats);
            body.put("config", Map.of(
                    "version", version,
                    "posFilter", posFilter.stream().map(PartOfSpeech::getCode).toList(),
                    "includeCanonical", includeCanonical,
                    "includeLkcSource", includeLkcSource));
            return body;
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

        for (LexicalEntryEntity e : canonicalEntries) {
            writeCanonicalEntry(xml, e);
        }

        long wordCursor = nextWordNum;
        long senCursor = nextSenNum;
        for (Submission s : validatedSubs) {
            String wordId = "ben-word-" + s.getPartOfSpeech().getCode() + (wordCursor++);
            String senseId = "ben-sen" + (senCursor++);
            String synId = s.getSynsetAction() == SynsetAction.LINK ? s.getSynsetId() : newSynsetBySub.get(s.getId());
            xml.append("    <LexicalEntry id=\"").append(wordId).append("\">\n");
            xml.append("      <Lemma writtenForm=\"").append(esc(s.getWrittenForm()))
                    .append("\" partOfSpeech=\"").append(s.getPartOfSpeech().getCode()).append("\"></Lemma>\n");
            List<SenseRelationPayload> senseRels = readSense(s.getSenseRelationsJson());
            if (!senseRels.isEmpty()) {
                xml.append("      <Sense id=\"").append(senseId).append("\" synset=\"").append(synId).append("\">\n");
                for (SenseRelationPayload r : senseRels) {
                    xml.append("        <SenseRelation relType=\"").append(esc(r.relType()))
                            .append("\" target=\"").append(esc(r.target())).append("\"></SenseRelation>\n");
                }
                xml.append("      </Sense>\n");
            } else {
                xml.append("      <Sense id=\"").append(senseId).append("\" synset=\"").append(synId)
                        .append("\"></Sense>\n");
            }
            xml.append("    </LexicalEntry>\n");
        }

        for (String id : usedSynsetIds) {
            if (id == null) {
                continue;
            }
            SynsetEntity syn = synsetRepository.findById(id).orElse(null);
            if (syn == null) {
                continue;
            }
            xml.append("    <Synset id=\"").append(syn.getId()).append("\" ili=\"").append(esc(syn.getIli()))
                    .append("\" partOfSpeech=\"").append(syn.getPartOfSpeech().getCode())
                    .append("\" lexicalized=\"").append(syn.isLexicalized()).append("\">\n");
            if (syn.getDefinitionText() != null) {
                xml.append("      <Definition language=\"")
                        .append(syn.getDefinitionLanguage() != null ? syn.getDefinitionLanguage().getCode() : "ben")
                        .append("\">").append(esc(syn.getDefinitionText())).append("</Definition>\n");
            }
            for (SynsetRelationEntity r : synsetRelationRepository.findBySourceSynsetId(syn.getId())) {
                if (!usedSynsetIds.contains(r.getTargetSynset().getId())) {
                    continue;
                }
                xml.append("      <SynsetRelation relType=\"").append(esc(r.getRelationType().getRelationName()))
                        .append("\" target=\"").append(r.getTargetSynset().getId()).append("\"></SynsetRelation>\n");
            }
            xml.append("    </Synset>\n");
        }

        for (Submission s : validatedSubs) {
            String newId = newSynsetBySub.get(s.getId());
            if (newId == null) {
                continue;
            }
            xml.append("    <Synset id=\"").append(newId).append("\" ili=\"").append(esc(s.getIliCode()))
                    .append("\" partOfSpeech=\"").append(s.getPartOfSpeech().getCode())
                    .append("\" lexicalized=\"true\">\n");
            if (s.getDefinitionText() != null) {
                xml.append("      <Definition language=\"")
                        .append(s.getDefinitionLanguage() != null ? s.getDefinitionLanguage().getCode() : "ben")
                        .append("\">").append(esc(s.getDefinitionText())).append("</Definition>\n");
            }
            for (SynsetRelationPayload r : readSynset(s.getSynsetRelationsJson())) {
                if (!usedSynsetIds.contains(r.target())) {
                    continue;
                }
                xml.append("      <SynsetRelation relType=\"").append(esc(r.relType()))
                        .append("\" target=\"").append(esc(r.target())).append("\"></SynsetRelation>\n");
            }
            xml.append("    </Synset>\n");
        }

        xml.append("  </Lexicon>\n</LexicalResource>");
        String xmlStr = xml.toString();
        String sha256 = sha256(xmlStr);

        String exportId = "exp-" + idGeneratorService.nextValue("export");
        exportRecordRepository.save(ExportRecord.builder()
                .id(exportId)
                .timestamp(Instant.now())
                .actorId(principal.getId())
                .entryCount((int) stats.get("entries"))
                .synsetCount((int) stats.get("synsets"))
                .sha256(sha256)
                .configJson("version=" + version + "; pos="
                        + (posFilter.isEmpty() ? "all" : String.join("/", posFilter.stream().map(PartOfSpeech::getCode).toList()))
                        + "; canonical=" + includeCanonical)
                .build());
        auditService.record(principal, "EXPORT_GENERATED", AuditTargetType.EXPORT, exportId,
                stats.get("entries") + " entries, " + stats.get("synsets") + " synsets, SHA-256 "
                        + sha256.substring(0, 12) + "…");

        return Map.of("xml", xmlStr, "sha256", sha256, "version", version);
    }

    private void writeCanonicalEntry(StringBuilder xml, LexicalEntryEntity e) {
        xml.append("    <LexicalEntry id=\"").append(e.getId()).append("\">\n");
        xml.append("      <Lemma writtenForm=\"").append(esc(e.getWrittenForm()))
                .append("\" partOfSpeech=\"").append(e.getPartOfSpeech().getCode()).append("\"></Lemma>\n");
        for (SenseEntity sn : senseRepository.findByLexicalEntryId(e.getId())) {
            xml.append("      <Sense id=\"").append(sn.getId()).append("\" synset=\"")
                    .append(sn.getSynset().getId()).append("\"></Sense>\n");
        }
        xml.append("    </LexicalEntry>\n");
    }

    private List<SenseRelationPayload> readSense(String json) {
        try {
            if (json == null || json.isBlank()) {
                return List.of();
            }
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    private List<SynsetRelationPayload> readSynset(String json) {
        try {
            if (json == null || json.isBlank()) {
                return List.of();
            }
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            return List.of();
        }
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
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
