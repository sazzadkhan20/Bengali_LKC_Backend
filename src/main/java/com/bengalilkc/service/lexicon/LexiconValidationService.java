package com.bengalilkc.service.lexicon;

import com.bengalilkc.domain.enums.EntryState;
import com.bengalilkc.domain.enums.PartOfSpeech;
import com.bengalilkc.domain.enums.Permission;
import com.bengalilkc.repository.workflow.SubmissionRepository;
import com.bengalilkc.security.PermissionMatrix;
import com.bengalilkc.security.UserPrincipal;
import com.bengalilkc.service.workflow.EntryValidator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Live Quick Input validation; runs exactly the rules enforced on submit. */
@Service
@RequiredArgsConstructor
public class LexiconValidationService {

    private static final Pattern ILI = Pattern.compile("^\\d{8}-[nvar]$");

    private final EntryValidator entryValidator;
    private final SubmissionRepository submissionRepository;

    /**
     * {@code excludeSubmissionId} (the entry being edited) is honoured only for its owner, or for the
     * System Moderator correcting an entry that is still in arbitration (tie resolution).
     * {@code nearDuplicate} stays in the response after confirmation so the UI keeps showing it.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> validateEntry(Map<String, Object> body, UserPrincipal principal) {
        String excludeId = str(body.get("excludeSubmissionId")).trim();
        boolean tieBreaker = PermissionMatrix.has(principal.getRole(), Permission.TIE_BREAKER);
        if (!excludeId.isEmpty() && submissionRepository.findById(excludeId)
                .filter(s -> s.getContributorId().equals(principal.getId())
                        || (tieBreaker && s.getState() == EntryState.ARBITRATION))
                .isEmpty()) {
            excludeId = "";
        }
        EntryValidator.Result result = entryValidator.validate(body, excludeId.isEmpty() ? null : excludeId);
        boolean confirmNear = body.get("confirmNearDuplicate") instanceof Boolean b && b;

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", !result.blocked(confirmNear));
        out.put("errors", result.errors());
        out.put("nearDuplicate", result.nearDuplicate());
        out.put("writtenForm", result.input().word());
        return out;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> validateIli(String rawIli, PartOfSpeech pos) {
        Map<String, String> errors = new LinkedHashMap<>();
        String raw = rawIli == null ? "" : rawIli.trim();
        String ili = EntryValidator.isNa(raw) ? "n/a" : raw.toLowerCase();
        if (raw.isEmpty()) {
            errors.put("iliCode", "Enter an ILI code or select N/A.");
        } else if (!"n/a".equals(ili) && !ILI.matcher(ili).matches()) {
            errors.put("iliCode",
                    "ILI code must follow the format {8-digit-offset}-{n|v|a|r}, e.g. 04192858-n — or select N/A.");
        } else if (!"n/a".equals(ili) && pos != null && !ili.endsWith("-" + pos.getCode())) {
            errors.put("iliCode", "The ILI code's POS suffix must match the selected part of speech.");
        } else if (!"n/a".equals(ili)) {
            String problem = entryValidator.iliRegistryError(ili);
            if (problem == null) {
                problem = entryValidator.iliTaken(ili, null);
            }
            if (problem != null) {
                errors.put("iliCode", problem);
            }
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
        return EntryValidator.isNa(ili);
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
}
