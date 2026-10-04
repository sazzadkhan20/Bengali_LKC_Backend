package com.bengalilkc.web.controller;

import com.bengalilkc.domain.enums.Permission;
import com.bengalilkc.security.RequirePermission;
import com.bengalilkc.security.SecurityUtils;
import com.bengalilkc.service.export.ExportService;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
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

    /** {@code dryRun=1} returns projected counts as JSON; otherwise generates, logs and returns the XML file. */
    @GetMapping
    @RequirePermission(Permission.XML_EXPORT)
    public ResponseEntity<?> export(@RequestParam Map<String, String> params) {
        if ("1".equals(params.get("dryRun"))) {
            return ResponseEntity.ok(exportService.dryRun(params));
        }
        ExportService.ExportFile file = exportService.export(SecurityUtils.requirePrincipal(), params);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(file.filename(), StandardCharsets.UTF_8).build().toString())
                .header("X-Export-SHA256", file.sha256())
                .header("X-Export-Id", file.exportId())
                .contentType(new MediaType(MediaType.APPLICATION_XML, StandardCharsets.UTF_8))
                .body(file.xml());
    }

    @GetMapping("/summary")
    @RequirePermission(Permission.XML_EXPORT)
    public Map<String, Object> summary(@RequestParam(defaultValue = "0") int page,
                                       @RequestParam(defaultValue = "10") int size) {
        return exportService.summary(page, size);
    }
}
