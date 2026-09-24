package com.bengalilkc.web.controller;

import com.bengalilkc.domain.enums.PartOfSpeech;
import com.bengalilkc.security.SecurityUtils;
import com.bengalilkc.service.lexicon.LexiconQueryService;
import com.bengalilkc.service.lexicon.LexiconValidationService;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/lookup")
@RequiredArgsConstructor
public class LookupController {

    private final LexiconQueryService lexiconQueryService;
    private final LexiconValidationService lexiconValidationService;

    @GetMapping("/words")
    public Map<String, Object> words(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String pos) {
        SecurityUtils.requirePrincipal();
        return lexiconQueryService.lookupWords(q, pos);
    }

    @GetMapping("/synsets")
    public Map<String, Object> synsets(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String pos) {
        SecurityUtils.requirePrincipal();
        return lexiconQueryService.lookupSynsets(q, pos);
    }

    @GetMapping("/ili")
    public Map<String, Object> ili(
            @RequestParam(required = false) String word,
            @RequestParam(required = false, defaultValue = "any") String pos) {
        SecurityUtils.requirePrincipal();
        return lexiconQueryService.lookupIli(word, pos);
    }

    /** Live Quick Input validation: writtenForm + POS + synset/ILI linkage. */
    @PostMapping("/validate")
    public Map<String, Object> validate(@RequestBody Map<String, Object> body) {
        SecurityUtils.requirePrincipal();
        return lexiconValidationService.validateEntry(body);
    }

    @GetMapping("/validate/ili")
    public Map<String, Object> validateIli(
            @RequestParam(required = false) String ili,
            @RequestParam(required = false) String pos) {
        SecurityUtils.requirePrincipal();
        PartOfSpeech p = null;
        try {
            if (pos != null && !pos.isBlank()) {
                p = PartOfSpeech.from(pos);
            }
        } catch (Exception ignored) {
            // leave null
        }
        return lexiconValidationService.validateIli(ili, p);
    }
}
