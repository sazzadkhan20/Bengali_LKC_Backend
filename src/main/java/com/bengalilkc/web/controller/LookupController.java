package com.bengalilkc.web.controller;

import com.bengalilkc.security.SecurityUtils;
import com.bengalilkc.service.lexicon.LexiconQueryService;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/lookup")
@RequiredArgsConstructor
public class LookupController {

    private final LexiconQueryService lexiconQueryService;

    @GetMapping("/words")
    public Map<String, Object> words(@RequestParam(required = false) String q) {
        SecurityUtils.requirePrincipal();
        return lexiconQueryService.lookupWords(q);
    }

    @GetMapping("/synsets")
    public Map<String, Object> synsets(@RequestParam(required = false) String q) {
        SecurityUtils.requirePrincipal();
        return lexiconQueryService.lookupSynsets(q);
    }

    @GetMapping("/ili")
    public Map<String, Object> ili(
            @RequestParam(required = false) String word,
            @RequestParam(required = false, defaultValue = "any") String pos) {
        SecurityUtils.requirePrincipal();
        return lexiconQueryService.lookupIli(word, pos);
    }
}
