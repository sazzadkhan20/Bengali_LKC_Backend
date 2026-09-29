package com.bengalilkc.service.lexicon;

import com.bengalilkc.domain.entity.lexicon.SenseEntity;
import com.bengalilkc.domain.entity.lexicon.SynsetEntity;
import com.bengalilkc.domain.enums.PartOfSpeech;
import com.bengalilkc.domain.enums.SynsetAction;
import com.bengalilkc.repository.lexicon.SenseRepository;
import com.bengalilkc.repository.lexicon.SynsetRepository;
import com.bengalilkc.repository.lexicon.WordnetIliRepository;
import com.bengalilkc.repository.workflow.SubmissionRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Fast field-level validation for Quick Input (writtenForm + POS + synset/ILI).
 */
@Service
@RequiredArgsConstructor
public class LexiconValidationService {

    private static final Pattern BENGALI = Pattern.compile("[\\u0980-\\u09ff]");
    private static final Pattern ILI = Pattern.compile("^\\d{8}-[nvar]$");

    private final SynsetRepository synsetRepository;
    private final SenseRepository senseRepository;
    private final WordnetIliRepository wordnetIliRepository;
    private final SubmissionRepository submissionRepository;

    @Transactional(readOnly = true)
    public Map<String, Object> validateEntry(Map<String, Object> body) {
        Map<String, String> errors = new LinkedHashMap<>();
        String word = str(body.get("writtenForm")).trim();
        String posRaw = str(body.get("pos")).trim();
        String actionRaw = str(body.get("synsetAction")).trim();
        boolean confirmNear = bool(body.get("confirmNearDuplicate"));

        PartOfSpeech pos = null;
        if (word.isEmpty()) {
            errors.put("writtenForm", "Bengali word is required.");
        } else if (!BENGALI.matcher(word).find()) {
            errors.put("writtenForm", "Must contain at least one Bengali Unicode character (U+0980–U+09FF).");
        } else if (word.length() > 25) {
            errors.put("writtenForm", "Maximum 25 characters allowed.");
        }

        try {
            pos = PartOfSpeech.from(posRaw);
        } catch (Exception e) {
            errors.put("pos", "Part of speech is required.");
        }

        SynsetAction action = null;
        try {
            action = SynsetAction.from(actionRaw);
        } catch (Exception e) {
            if (!actionRaw.isEmpty()) {
                errors.put("synsetAction", "Choose to link an existing synset or create a new one.");
            }
        }

        String synsetId = null;
        String iliCode = null;
        if (action == SynsetAction.LINK && pos != null) {
            synsetId = str(body.get("synsetId")).trim();
            SynsetEntity syn = synsetId.isEmpty() ? null : synsetRepository.findById(synsetId).orElse(null);
            if (syn == null) {
                errors.put("synsetId", "Select an existing synset from search.");
            } else if (syn.getPartOfSpeech() != pos) {
                errors.put("synsetId",
                        "POS mismatch: selected synset is \"" + syn.getPartOfSpeech().getCode()
                                + "\" but the lemma is \"" + pos.getCode() + "\".");
            } else if (!word.isEmpty() && senseRepository.existsByLemmaPosAndSynset(word, pos, syn.getId())) {
                errors.put("duplicate",
                        "Exact duplicate: \"" + word + "\" (" + pos.getCode()
                                + ") is already linked to synset " + syn.getId() + " in the lexicon.");
            }
        } else if (action == SynsetAction.CREATE && pos != null) {
            Map<String, Object> iliResult = validateIli(str(body.get("iliCode")), pos);
            @SuppressWarnings("unchecked")
            Map<String, String> iliErrors = (Map<String, String>) iliResult.get("errors");
            if (iliErrors != null && !iliErrors.isEmpty()) {
                errors.putAll(iliErrors);
            } else {
                iliCode = str(iliResult.get("normalized"));
            }
        }

        String nearDuplicate = null;
        if (errors.isEmpty() && pos != null && !word.isEmpty()) {
            List<SenseEntity> existingSenses = senseRepository.findByLemmaAndPos(word, pos);
            var openSubs = submissionRepository.findActiveByLemmaAndPos(word, pos);
            boolean lexiconNear = !existingSenses.isEmpty();
            boolean submissionNear = !openSubs.isEmpty();
            if ((lexiconNear || submissionNear) && action == SynsetAction.LINK && synsetId != null) {
                // Exact lexicon dup already handled; near = other synsets / submissions
                boolean onlyExact = !submissionNear
                        && existingSenses.size() == 1
                        && existingSenses.get(0).getSynset().getId().equals(synsetId);
                if (!onlyExact && !confirmNear) {
                    nearDuplicate = "\"" + word + "\" (" + pos.getCode()
                            + ") already exists with another concept. Confirm to proceed — this may be legitimate polysemy.";
                }
            } else if ((lexiconNear || submissionNear) && action == SynsetAction.CREATE && !confirmNear) {
                // Creating new sense for existing lemma+POS is always near-dup unless confirmed
                final String ili = iliCode;
                boolean sameIliAlready = ili != null && !isNa(ili)
                        && existingSenses.stream().anyMatch(s -> ili.equalsIgnoreCase(s.getSynset().getIli()));
                if (!sameIliAlready) {
                    nearDuplicate = "\"" + word + "\" (" + pos.getCode()
                            + ") already exists in the lexicon or an open submission. Confirm to proceed — this may be legitimate polysemy.";
                }
            }

            // Exact duplicate against open submissions (same ILI)
            String targetIli = action == SynsetAction.LINK
                    ? synsetRepository.findById(synsetId).map(SynsetEntity::getIli).orElse(null)
                    : iliCode;
            Map<String, String> iliBySynset = new java.util.HashMap<>();
            List<String> linkedIds = openSubs.stream()
                    .filter(s -> s.getSynsetAction() == SynsetAction.LINK && s.getSynsetId() != null)
                    .map(s -> s.getSynsetId())
                    .distinct()
                    .toList();
            if (targetIli != null && !linkedIds.isEmpty()) {
                synsetRepository.findAllById(linkedIds).forEach(s -> iliBySynset.put(s.getId(), s.getIli()));
            }
            if (targetIli != null) {
                for (var existing : openSubs) {
                    String existingIli = existing.getSynsetAction() == SynsetAction.LINK
                            ? iliBySynset.get(existing.getSynsetId())
                            : existing.getIliCode();
                    if (targetIli.equalsIgnoreCase(existingIli == null ? "" : existingIli)
                            || (isNa(targetIli) && isNa(existingIli))) {
                        if (!isNa(targetIli) || (isNa(targetIli) && synsetId != null
                                && synsetId.equals(existing.getSynsetId()))) {
                            errors.put("duplicate",
                                    "Exact duplicate blocked: \"" + word + "\" (" + pos.getCode()
                                            + ") already targets the same concept (submission "
                                            + existing.getId() + ").");
                            break;
                        }
                    }
                }
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", errors.isEmpty() && nearDuplicate == null);
        if (!errors.isEmpty()) {
            out.put("errors", errors);
        }
        if (nearDuplicate != null) {
            out.put("nearDuplicate", nearDuplicate);
        }
        return out;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> validateIli(String rawIli, PartOfSpeech pos) {
        Map<String, String> errors = new LinkedHashMap<>();
        String ili = normalizeIli(rawIli);
        if (ili.isEmpty()) {
            errors.put("iliCode", "Enter an ILI code or select N/A.");
        } else if (isNa(ili)) {
            ili = "n/a";
        } else if (!ILI.matcher(ili).matches()) {
            errors.put("iliCode",
                    "ILI code must follow the format {8-digit-offset}-{n|v|a|r}, e.g. 04192858-n — or select N/A.");
        } else if (pos != null && !ili.endsWith(pos.getCode())) {
            errors.put("iliCode", "ILI code POS suffix must match the selected part of speech.");
        } else if (synsetRepository.existsByIli(ili)) {
            errors.put("iliCode",
                    "This ILI code already has a Bengali synset — use “Link to existing synset” instead.");
        } else if (wordnetIliRepository.count() > 0 && !wordnetIliRepository.existsById(ili)) {
            // Soft registry: only warn when the code is absent from our seeded ILI table.
            // Format is still valid — allow create (full WN registry may be incomplete).
            // Keep as info-only via optional hint, not hard error.
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", errors.isEmpty());
        out.put("normalized", ili);
        if (!errors.isEmpty()) {
            out.put("errors", errors);
        }
        return out;
    }

    public static boolean isNa(String ili) {
        if (ili == null) {
            return false;
        }
        String t = ili.trim();
        return t.equalsIgnoreCase("n/a") || t.equalsIgnoreCase("na") || t.equalsIgnoreCase("N/A");
    }

    public static String normalizeIli(String ili) {
        if (ili == null) {
            return "";
        }
        String t = ili.trim();
        return isNa(t) ? "n/a" : t;
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static boolean bool(Object o) {
        return o instanceof Boolean b && b;
    }
}
