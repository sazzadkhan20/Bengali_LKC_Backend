package com.bengalilkc.web.controller;

import com.bengalilkc.service.lexicon.LexiconQueryService;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/public")
@RequiredArgsConstructor
public class PublicController {

    private final LexiconQueryService lexiconQueryService;

    @GetMapping({"/stats", "/overview"})
    public Map<String, Object> stats() {
        return lexiconQueryService.publicStats();
    }

    @GetMapping("/search")
    public Map<String, Object> search(
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "25") int limit) {
        return lexiconQueryService.searchByLemma(q, limit);
    }

    @GetMapping("/browse")
    public Map<String, Object> browse(
            @RequestParam String letter,
            @RequestParam(defaultValue = "50") int limit) {
        return lexiconQueryService.browseByLetter(letter, limit);
    }

    @GetMapping("/words/{id}")
    public Map<String, Object> word(@PathVariable String id) {
        return lexiconQueryService.getWord(id);
    }

    @GetMapping("/synsets/{id}")
    public Map<String, Object> synset(@PathVariable String id) {
        return lexiconQueryService.getSynset(id);
    }
}
