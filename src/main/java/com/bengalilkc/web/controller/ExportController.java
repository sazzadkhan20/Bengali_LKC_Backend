package com.bengalilkc.web.controller;

import com.bengalilkc.domain.enums.Permission;
import com.bengalilkc.security.RequirePermission;
import com.bengalilkc.security.SecurityUtils;
import com.bengalilkc.service.export.ExportService;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/export")
@RequiredArgsConstructor
public class ExportController {

    private final ExportService exportService;

    @GetMapping
    @RequirePermission(Permission.XML_EXPORT)
    public ResponseEntity<?> export(@RequestParam Map<String, String> params) {
        Object result = exportService.export(SecurityUtils.requirePrincipal(), params);
        if (result instanceof Map<?, ?> map && Boolean.TRUE.equals(map.get("dryRun"))) {
            return ResponseEntity.ok(result);
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) result;
        String xml = String.valueOf(payload.get("xml"));
        String sha = String.valueOf(payload.get("sha256"));
        String version = String.valueOf(payload.get("version"));
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"Bangla_Mono_Lingual_Lexicons_v" + version + ".xml\"")
                .header("X-Export-SHA256", sha)
                .contentType(MediaType.APPLICATION_XML)
                .body(xml);
    }
}
