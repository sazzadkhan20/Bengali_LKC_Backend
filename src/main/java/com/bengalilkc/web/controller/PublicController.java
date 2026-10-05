package com.bengalilkc.web.controller;

import com.bengalilkc.security.SecurityUtils;
import com.bengalilkc.service.admin.ApplicationService;
import com.bengalilkc.service.lexicon.PublicLexiconService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Dictionary read API. Browse, word and gloss search, suggestions and word pages are open to visitors;
 * synset pages, synset search and sense lookup expose internal ids and require a signed-in user.
 */
@RestController
@RequestMapping("/api/public")
@RequiredArgsConstructor
public class PublicController {

    private final PublicLexiconService lexicon;
    private final ApplicationService applications;

    @GetMapping({"/stats", "/overview"})
    public Map<String, Object> stats() {
        return lexicon.stats();
    }

    @GetMapping("/browse")
    public Map<String, Object> browse(
            @RequestParam(required = false) String letter,
            @RequestParam(required = false) String pos,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int size) {
        return lexicon.browse(letter, pos, page, size);
    }

    @GetMapping("/search/words")
    public Map<String, Object> searchWords(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String pos,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int size) {
        return lexicon.searchWords(q, pos, page, size);
    }

    @GetMapping("/search/gloss")
    public Map<String, Object> searchGloss(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String pos,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "25") int size) {
        return lexicon.searchGloss(q, pos, page, size);
    }

    @GetMapping("/suggest")
    public Map<String, Object> suggest(
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "8") int limit) {
        return lexicon.suggest(q, limit);
    }

    @GetMapping("/words/{id}")
    public Map<String, Object> word(@PathVariable String id) {
        return lexicon.word(id);
    }

    @GetMapping("/search/synsets")
    public Map<String, Object> searchSynsets(
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "25") int size) {
        SecurityUtils.requirePrincipal();
        return lexicon.searchSynsets(q, page, size);
    }

    @GetMapping("/synsets/{id}")
    public Map<String, Object> synset(@PathVariable String id) {
        SecurityUtils.requirePrincipal();
        return lexicon.synset(id);
    }

    @GetMapping("/senses/{id}")
    public Map<String, Object> sense(@PathVariable String id) {
        SecurityUtils.requirePrincipal();
        return lexicon.sense(id);
    }

    /** "Apply to contribute" form on the public site. */
    @PostMapping("/applications")
    public Map<String, Object> apply(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        return applications.submit(body, clientIp(request));
    }

    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
