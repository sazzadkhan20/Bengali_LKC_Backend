package com.bengalilkc.service.workflow;

import com.bengalilkc.common.BengaliText;
import com.bengalilkc.common.DefinitionPayload;
import com.bengalilkc.common.SenseRelationPayload;
import com.bengalilkc.common.SynsetRelationPayload;
import com.bengalilkc.domain.entity.lexicon.SynsetEntity;
import com.bengalilkc.domain.enums.DefinitionLanguage;
import com.bengalilkc.domain.enums.EntryState;
import com.bengalilkc.domain.enums.PartOfSpeech;
import com.bengalilkc.domain.enums.SynsetAction;
import com.bengalilkc.repository.lexicon.SynsetRepository;
import com.bengalilkc.service.lexicon.SynsetDefinitionService;
import com.bengalilkc.service.lexicon.WordnetRegistryService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Every Quick Input rule, shared by live validation, new entries, drafts and resubmissions.
 * Duplicates are checked against the whole database: the lexicon (XML + validated entries)
 * and every submission whatever its status. Rejected submissions release their word and ILI,
 * but an entry identical to a rejected one is refused.
 */
@Component
@RequiredArgsConstructor
public class EntryValidator {

    public static final int WORD_MAX = 25;
    public static final int DEFINITION_MIN = 5;
    public static final int DEFINITION_MAX = 500;
    public static final int NOTE_MAX = 500;
    public static final int RELATIONS_MAX = 20;

    public static final Set<String> SENSE_REL_TYPES = Set.of("derivation", "antonym", "metonym");
    public static final Set<String> SYNSET_REL_TYPES =
            Set.of("hypernym", "mero_part", "mero_substance", "mero_member", "attribute");

    private static final Pattern ILI = Pattern.compile("^\\d{8}-[nvar]$");
    private static final String NA = "n/a";

    private final SynsetRepository synsetRepository;
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final SynsetDefinitionService definitionService;
    private final WordnetRegistryService registryService;

    /** Parsed entry; relation lists contain only complete rows. */
    public record EntryInput(
            String word,
            PartOfSpeech pos,
            SynsetAction action,
            String synsetId,
            String iliCode,
            /** Definitions to add, Bengali first; for a linked synset only languages it does not have yet. */
            List<DefinitionPayload> definitions,
            String reviewerNote,
            List<SenseRelationPayload> senseRelations,
            List<SynsetRelationPayload> synsetRelations) {}

    /** {@code nearDuplicate} is a polysemy warning the user must confirm; it is reported even once confirmed. */
    public record Result(EntryInput input, Map<String, String> errors, String nearDuplicate) {
        public boolean blocked(boolean confirmNear) {
            return !errors.isEmpty() || (nearDuplicate != null && !confirmNear);
        }
    }

    /** Serialises concurrent writes of the same word or ILI until the surrounding transaction ends. */
    public void lockFor(Map<String, Object> p) {
        String key = BengaliText.key(str(p.get("writtenForm")));
        jdbc.queryForList("select pg_advisory_xact_lock(hashtext('lkc-form:' || :k))", Map.of("k", key));
        String ili = str(p.get("iliCode")).trim().toLowerCase();
        if (!ili.isEmpty() && !isNa(ili)) {
            jdbc.queryForList("select pg_advisory_xact_lock(hashtext('lkc-ili:' || :i))", Map.of("i", ili));
        }
    }

    /** @param excludeId the submission being edited, ignored by duplicate checks */
    public Result validate(Map<String, Object> p, String excludeId) {
        Map<String, String> errors = new LinkedHashMap<>();

        String word = BengaliText.clean(str(p.get("writtenForm")));
        if (word.isEmpty()) {
            errors.put("writtenForm", "Bengali word is required.");
        } else if (!BengaliText.onlyAllowedChars(word)) {
            errors.put("writtenForm",
                    "Use Bengali letters only — digits, English letters and symbols are not allowed.");
        } else if (!BengaliText.startsWithLetter(word)) {
            errors.put("writtenForm", "The word must start with a Bengali letter, not a vowel sign or mark.");
        } else if (word.startsWith("-") || word.endsWith("-") || word.contains("--") || word.contains(" -")
                || word.contains("- ")) {
            errors.put("writtenForm", "A hyphen may only join two parts of the word.");
        } else if (BengaliText.length(word) > WORD_MAX) {
            errors.put("writtenForm", "Maximum " + WORD_MAX + " characters allowed.");
        }

        PartOfSpeech pos = null;
        try {
            pos = PartOfSpeech.from(str(p.get("pos")));
        } catch (Exception e) {
            errors.put("pos", "Select a part of speech.");
        }

        SynsetAction action = null;
        try {
            action = SynsetAction.from(str(p.get("synsetAction")));
        } catch (Exception e) {
            errors.put("synsetAction", "Choose to link an existing synset or create a new one.");
        }

        String synsetId = null;
        String iliCode = null;
        SynsetEntity linked = null;
        if (action == SynsetAction.LINK) {
            synsetId = str(p.get("synsetId")).trim();
            linked = synsetId.isEmpty() ? null : synsetRepository.findById(synsetId).orElse(null);
            if (synsetId.isEmpty()) {
                errors.put("synsetId", "Search and select an existing synset.");
            } else if (linked == null) {
                errors.put("synsetId", "This synset is not in the validated lexicon. Pick one from the search results.");
            } else if (pos != null && linked.getPartOfSpeech() != pos) {
                errors.put("synsetId", "POS mismatch: the selected synset is " + posName(linked.getPartOfSpeech())
                        + " but the word is " + posName(pos) + ".");
            }
        } else if (action == SynsetAction.CREATE) {
            String raw = str(p.get("iliCode")).trim();
            iliCode = isNa(raw) ? NA : raw.toLowerCase();
            if (raw.isEmpty()) {
                errors.put("iliCode", "Enter an ILI code or select N/A.");
            } else if (!NA.equals(iliCode) && !ILI.matcher(iliCode).matches()) {
                errors.put("iliCode",
                        "ILI code must follow the format {8-digit-offset}-{n|v|a|r}, e.g. 04192858-n — or select N/A.");
            } else if (!NA.equals(iliCode) && pos != null && !iliCode.endsWith("-" + pos.getCode())) {
                errors.put("iliCode", "The ILI code's POS suffix (-" + iliCode.charAt(iliCode.length() - 1)
                        + ") must match the selected part of speech (-" + pos.getCode() + ").");
            } else if (!NA.equals(iliCode)) {
                String iliError = iliRegistryError(iliCode);
                if (iliError == null) {
                    iliError = iliTaken(iliCode, excludeId);
                }
                if (iliError != null) {
                    errors.put("iliCode", iliError);
                }
            }
        }

        Set<String> existingLanguages = linked != null ? definitionService.languagesOf(linked.getId()) : Set.of();
        List<DefinitionPayload> definitions = validateDefinitions(p, existingLanguages, errors);

        String reviewerNote = blankToNull(str(p.get("reviewerNote")).strip());
        if (reviewerNote != null && BengaliText.length(reviewerNote) > NOTE_MAX) {
            errors.put("reviewerNote", "Note must be at most " + NOTE_MAX + " characters.");
        }

        List<SenseRelationPayload> senseRels = new ArrayList<>();
        List<SynsetRelationPayload> synsetRels = new ArrayList<>();
        validateRelations(p, errors, synsetId, senseRels, synsetRels);

        EntryInput in = new EntryInput(word, pos, action,
                action == SynsetAction.LINK ? synsetId : null,
                action == SynsetAction.CREATE ? iliCode : null,
                definitions,
                reviewerNote, List.copyOf(senseRels), List.copyOf(synsetRels));

        String near = null;
        boolean conceptReady = (action == SynsetAction.LINK && linked != null && !errors.containsKey("synsetId"))
                || (action == SynsetAction.CREATE && !errors.containsKey("iliCode"));
        if (!errors.containsKey("writtenForm") && pos != null && conceptReady) {
            near = checkDuplicates(in, linked, excludeId, errors);
        }
        return new Result(in, errors, near);
    }

    /** Error text when a real ILI code is not a Princeton WordNet 3.0 synset (or the registry is unavailable). */
    public String iliRegistryError(String ili) {
        if (!registryService.isLoaded()) {
            return "The WordNet 3.0 ILI registry is not available right now. Try again in a minute, or select N/A.";
        }
        if (!registryService.exists(ili)) {
            return "ILI " + ili + " is not a Princeton WordNet 3.0 synset. Use the ILI Finder to pick a valid code, "
                    + "or select N/A.";
        }
        return null;
    }

    /**
     * Definition rows ({@code definitions: [{language, text}]}; the legacy {@code definitionText} /
     * {@code definitionLanguage} pair is read as one row). Row errors use keys {@code definitions.N}.
     * At most one definition per language, and none in a language the linked synset already has.
     */
    private List<DefinitionPayload> validateDefinitions(
            Map<String, Object> p, Set<String> existingLanguages, Map<String, String> errors) {
        List<Map<?, ?>> rows = new ArrayList<>();
        Object raw = p.get("definitions");
        if (raw instanceof List<?> list) {
            for (Object o : list) {
                rows.add(o instanceof Map<?, ?> m ? m : Map.of());
            }
        } else {
            String legacy = str(p.get("definitionText")).strip();
            if (!legacy.isEmpty()) {
                String lang = str(p.get("definitionLanguage")).trim();
                rows.add(Map.of("language", lang.isEmpty() ? "ben" : lang, "text", legacy));
            }
        }
        int max = SynsetDefinitionService.LANGUAGES.size();
        if (rows.size() > max) {
            errors.put("definitions", "A synset can have at most one definition per language (" + max + " in total).");
            return List.of();
        }
        List<DefinitionPayload> out = new ArrayList<>();
        Map<String, Integer> firstUse = new HashMap<>();
        for (int i = 0; i < rows.size(); i++) {
            String key = "definitions." + i;
            String lang = null;
            try {
                DefinitionLanguage dl = DefinitionLanguage.from(str(rows.get(i).get("language")));
                lang = dl == null ? null : dl.getCode();
            } catch (Exception ignored) {
                // reported below
            }
            String text = str(rows.get(i).get("text")).strip();
            if (lang == null) {
                errors.put(key, "Select the definition language.");
                continue;
            }
            String name = SynsetDefinitionService.languageName(lang);
            if (existingLanguages.contains(lang)) {
                errors.put(key, "The selected synset already has a definition in " + name
                        + ". Add a definition in another language, or remove this one.");
            } else if (firstUse.containsKey(lang)) {
                errors.put(key, "Only one definition per language: " + name + " is already used in definition "
                        + (firstUse.get(lang) + 1) + ".");
            } else {
                firstUse.put(lang, i);
                int len = BengaliText.length(text);
                if (text.isEmpty()) {
                    errors.put(key, "Write the " + name + " definition, or remove it.");
                } else if (len < DEFINITION_MIN) {
                    errors.put(key, "Definition must be at least " + DEFINITION_MIN + " characters.");
                } else if (len > DEFINITION_MAX) {
                    errors.put(key, "Definition must be at most " + DEFINITION_MAX + " characters.");
                } else if ("ben".equals(lang) && (!BengaliText.hasBengali(text) || BengaliText.hasLatin(text))) {
                    errors.put(key, "A Bengali definition must be written in Bengali script.");
                } else if ("eng".equals(lang) && (!BengaliText.hasLatin(text) || BengaliText.hasBengali(text))) {
                    errors.put(key, "An English definition must be written in English (Latin script).");
                } else {
                    out.add(new DefinitionPayload(lang, text));
                }
            }
        }
        return SynsetDefinitionService.sorted(out);
    }

    /** Error text when a real ILI is already registered in the lexicon or proposed by a live submission. */
    public String iliTaken(String ili, String excludeId) {
        SynsetEntity registered = synsetRepository.findByIli(ili).orElse(null);
        if (registered != null) {
            String words = String.join(", ", lemmasOf(registered.getId(), 3));
            return "ILI " + ili + " is already registered for synset " + registered.getId()
                    + (words.isEmpty() ? "" : " (" + words + ")")
                    + ". Choose “Link to existing synset” and pick that synset instead.";
        }
        List<Map<String, Object>> proposed = jdbc.queryForList("""
                select id, state from submission
                where synset_action = 'create' and lower(ili_code) = :ili
                  and state <> 'REJECTED' and promoted_synset_id is null and id <> :exclude
                order by created_at limit 1
                """, new MapSqlParameterSource("ili", ili).addValue("exclude", nz(excludeId)));
        if (!proposed.isEmpty()) {
            Map<String, Object> s = proposed.get(0);
            return "ILI " + ili + " is already proposed in submission " + s.get("id") + " ("
                    + stateLabel(str(s.get("state"))) + "). An ILI can only be created once — after that entry is "
                    + "validated, link to its synset instead.";
        }
        return null;
    }

    /** Adds blocking duplicate errors; returns a polysemy warning, or null. */
    private String checkDuplicates(EntryInput in, SynsetEntity linked, String excludeId, Map<String, String> errors) {
        String key = BengaliText.key(in.word());
        String label = "“" + in.word() + "” (" + posName(in.pos()) + ")";
        String targetIli = in.action() == SynsetAction.LINK ? linked.getIli() : in.iliCode();

        List<Map<String, Object>> lexicon = jdbc.queryForList("""
                select s.id as sense_id, sy.id as synset_id, lower(sy.ili) as ili
                from lexical_entry e
                join sense s on s.lexical_entry_id = e.id
                join synset sy on sy.id = s.synset_id
                where e.form_key = :key and e.part_of_speech = :pos
                order by sy.id
                """, new MapSqlParameterSource("key", key).addValue("pos", in.pos().getCode()));
        Set<String> otherSynsets = new LinkedHashSet<>();
        for (Map<String, Object> row : lexicon) {
            String sid = str(row.get("synset_id"));
            boolean same = sid.equals(in.synsetId()) || sameRealIli(targetIli, str(row.get("ili")));
            if (same) {
                errors.put("duplicate", label + " already exists in the lexicon in synset " + sid
                        + ". This entry is already part of the dictionary.");
                return null;
            }
            otherSynsets.add(sid);
        }

        List<Map<String, Object>> subs = jdbc.queryForList("""
                select s.id, s.state, s.synset_action, s.synset_id, lower(coalesce(s.ili_code, sy.ili)) as ili,
                       s.definitions_json, s.sense_relations_json, s.synset_relations_json,
                       s.promoted_sense_id
                from submission s
                left join synset sy on sy.id = s.synset_id
                where s.form_key = :key and s.part_of_speech = :pos and s.id <> :exclude
                order by s.created_at
                """, new MapSqlParameterSource("key", key)
                .addValue("pos", in.pos().getCode())
                .addValue("exclude", nz(excludeId)));
        List<String> otherSubs = new ArrayList<>();
        for (Map<String, Object> s : subs) {
            String id = str(s.get("id"));
            String state = str(s.get("state"));
            boolean sameConcept = (in.synsetId() != null && in.synsetId().equals(s.get("synset_id")))
                    || sameRealIli(targetIli, str(s.get("ili")));
            if (EntryState.REJECTED.name().equals(state)) {
                if (identicalToRejected(in, s)) {
                    errors.put("duplicate", "This entry is identical to rejected submission " + id
                            + ". Change the synset linkage, definition or relations before submitting it again.");
                    return null;
                }
                continue;
            }
            if (s.get("promoted_sense_id") != null) {
                continue;
            }
            if (sameConcept) {
                errors.put("duplicate", label + " is already submitted for the same concept in submission " + id
                        + " (" + stateLabel(state) + ").");
                return null;
            }
            otherSubs.add(id + " (" + stateLabel(state) + ")");
        }

        if (otherSynsets.isEmpty() && otherSubs.isEmpty()) {
            return null;
        }
        List<String> where = new ArrayList<>();
        if (!otherSynsets.isEmpty()) {
            where.add("in the lexicon (" + (otherSynsets.size() == 1 ? "synset " : "synsets ")
                    + limitJoin(otherSynsets, 3) + ")");
        }
        if (!otherSubs.isEmpty()) {
            where.add("in " + (otherSubs.size() == 1 ? "submission " : "submissions ") + limitJoin(otherSubs, 3));
        }
        return label + " already exists with a different meaning " + String.join(" and ", where)
                + ". Confirm only if your entry is a genuinely different meaning (polysemy).";
    }

    /** Same word was rejected with the very same linkage, definition and relations. */
    private boolean identicalToRejected(EntryInput in, Map<String, Object> s) {
        if (!in.action().getCode().equals(str(s.get("synset_action")))) {
            return false;
        }
        boolean sameLink = in.action() == SynsetAction.LINK
                ? Objects.equals(in.synsetId(), s.get("synset_id"))
                : Objects.equals(in.iliCode(), str(s.get("ili")));
        if (!sameLink) {
            return false;
        }
        if (!new HashSet<>(in.definitions()).equals(
                new HashSet<>(definitionService.read(str(s.get("definitions_json")))))) {
            return false;
        }
        return new HashSet<>(in.senseRelations()).equals(new HashSet<>(readSense(str(s.get("sense_relations_json")))))
                && new HashSet<>(in.synsetRelations()).equals(new HashSet<>(readSynset(str(s.get("synset_relations_json")))));
    }

    /**
     * Relation rows: valid type, a target from the lexicon (XML + validated entries), and each
     * target used at most once per list whatever the relation type. Row errors use keys like
     * {@code senseRelations.2} (0-based row index as sent).
     */
    private void validateRelations(Map<String, Object> p, Map<String, String> errors, String linkedSynsetId,
            List<SenseRelationPayload> senseOut, List<SynsetRelationPayload> synsetOut) {
        List<SenseRelationPayload> senseRows = readSense(writeJson(p.get("senseRelations")));
        if (senseRows.size() > RELATIONS_MAX) {
            errors.put("senseRelations", "At most " + RELATIONS_MAX + " sense relations are allowed per entry.");
        } else {
            Set<String> existing = existingIds("sense", senseRows.stream().map(SenseRelationPayload::target).toList());
            Map<String, Integer> firstUse = new HashMap<>();
            for (int i = 0; i < senseRows.size(); i++) {
                SenseRelationPayload rel = senseRows.get(i);
                String key = "senseRelations." + i;
                String type = rel == null ? "" : str(rel.relType()).trim();
                String target = rel == null ? "" : str(rel.target()).trim();
                if (type.isEmpty() || !SENSE_REL_TYPES.contains(type)) {
                    errors.put(key, "Choose a valid sense relation type.");
                } else if (target.isEmpty()) {
                    errors.put(key, "Search and pick a target word, or remove this row.");
                } else if (!existing.contains(target)) {
                    errors.put(key, "This word sense is not in the validated lexicon. Pick one from the search results.");
                } else if (firstUse.containsKey(target)) {
                    errors.put(key, "This word sense is already used in row " + (firstUse.get(target) + 1)
                            + ". Each target can be related only once.");
                } else {
                    firstUse.put(target, i);
                    senseOut.add(new SenseRelationPayload(type, target));
                }
            }
        }

        List<SynsetRelationPayload> synsetRows = readSynset(writeJson(p.get("synsetRelations")));
        if (synsetRows.size() > RELATIONS_MAX) {
            errors.put("synsetRelations", "At most " + RELATIONS_MAX + " synset relations are allowed per entry.");
            return;
        }
        Set<String> existing = existingIds("synset", synsetRows.stream().map(SynsetRelationPayload::target).toList());
        Map<String, Integer> firstUse = new HashMap<>();
        for (int i = 0; i < synsetRows.size(); i++) {
            SynsetRelationPayload rel = synsetRows.get(i);
            String key = "synsetRelations." + i;
            String type = rel == null ? "" : str(rel.relType()).trim();
            String target = rel == null ? "" : str(rel.target()).trim();
            if (type.isEmpty() || !SYNSET_REL_TYPES.contains(type)) {
                errors.put(key, "Choose a valid synset relation type.");
            } else if (target.isEmpty()) {
                errors.put(key, "Search and pick a target concept, or remove this row.");
            } else if (target.equals(linkedSynsetId)) {
                errors.put(key, "This is the synset your word is linked to — a synset cannot relate to itself.");
            } else if (!existing.contains(target)) {
                errors.put(key, "This concept is not in the validated lexicon. Pick one from the search results.");
            } else if (firstUse.containsKey(target)) {
                errors.put(key, "This concept is already used in row " + (firstUse.get(target) + 1)
                        + ". Each target can be related only once.");
            } else {
                firstUse.put(target, i);
                synsetOut.add(new SynsetRelationPayload(type, target));
            }
        }
    }

    private Set<String> existingIds(String table, Collection<String> ids) {
        List<String> wanted = ids.stream().filter(Objects::nonNull).map(String::trim)
                .filter(s -> !s.isEmpty()).distinct().toList();
        if (wanted.isEmpty()) {
            return Set.of();
        }
        String sql = "sense".equals(table)
                ? "select id from sense where id in (:ids)"
                : "select id from synset where id in (:ids)";
        return new HashSet<>(jdbc.queryForList(sql, new MapSqlParameterSource("ids", wanted), String.class));
    }

    private List<String> lemmasOf(String synsetId, int limit) {
        return jdbc.queryForList("""
                select e.written_form from sense s join lexical_entry e on e.id = s.lexical_entry_id
                where s.synset_id = :id order by e.written_form limit :limit
                """, new MapSqlParameterSource("id", synsetId).addValue("limit", limit), String.class);
    }

    public static boolean isNa(String ili) {
        if (ili == null) {
            return false;
        }
        String t = ili.trim();
        return t.equalsIgnoreCase("n/a") || t.equalsIgnoreCase("na");
    }

    private static boolean sameRealIli(String a, String b) {
        return a != null && b != null && !a.isEmpty() && !isNa(a) && !isNa(b) && a.equalsIgnoreCase(b);
    }

    public static String posName(PartOfSpeech pos) {
        return switch (pos) {
            case N -> "noun";
            case V -> "verb";
            case A -> "adjective";
            case R -> "adverb";
        };
    }

    public static String stateLabel(String state) {
        return switch (state) {
            case "DRAFT" -> "Draft";
            case "PENDING_REVIEW" -> "Pending Review";
            case "UNDER_REVIEW" -> "Under Review";
            case "FLAGGED" -> "Flagged";
            case "C_FLAGGED" -> "C-Flagged";
            case "EDITOR_APPROVED" -> "Editor Approved";
            case "ARBITRATION" -> "Arbitration";
            case "VALIDATED" -> "Validated";
            case "REJECTED" -> "Rejected";
            default -> state;
        };
    }

    private static String limitJoin(Collection<String> items, int max) {
        String head = items.stream().limit(max).collect(Collectors.joining(", "));
        return items.size() > max ? head + " and " + (items.size() - max) + " more" : head;
    }

    public String writeJson(Object value) {
        if (value == null) {
            return "[]";
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "[]";
        }
    }

    public List<SenseRelationPayload> readSense(String json) {
        try {
            return json == null || json.isBlank() ? List.of() : objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    public List<SynsetRelationPayload> readSynset(String json) {
        try {
            return json == null || json.isBlank() ? List.of() : objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
