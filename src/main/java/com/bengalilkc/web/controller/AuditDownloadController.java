package com.bengalilkc.web.controller;

import com.bengalilkc.domain.enums.AuditTargetType;
import com.bengalilkc.domain.enums.Permission;
import com.bengalilkc.security.RequirePermission;
import com.bengalilkc.security.SecurityUtils;
import com.bengalilkc.security.UserPrincipal;
import com.bengalilkc.service.admin.AuditQueryService;
import com.bengalilkc.service.admin.AuditService;
import java.time.LocalDate;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@RestController
@RequestMapping("/api/audit")
@RequiredArgsConstructor
public class AuditDownloadController {

    private final AuditQueryService auditQueryService;
    private final AuditService auditService;

    @GetMapping
    @RequirePermission(Permission.AUDIT_LOG)
    public Map<String, Object> search(
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String actor,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return auditQueryService.search(new AuditQueryService.Filter(type, action, actor, q, from, to), page, size);
    }

    @GetMapping("/download")
    @RequirePermission(Permission.AUDIT_LOG)
    public ResponseEntity<StreamingResponseBody> download(
            @RequestParam(defaultValue = "json") String fmt,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String actor,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        UserPrincipal principal = SecurityUtils.requirePrincipal();
        String format = "csv".equalsIgnoreCase(fmt) ? "csv" : "json";
        AuditQueryService.Filter filter = new AuditQueryService.Filter(type, action, actor, q, from, to);
        // Validate filters before the response is committed.
        auditQueryService.search(filter, 0, 1);
        auditService.record(principal, "AUDIT_EXPORTED", AuditTargetType.CONFIG, "audit",
                format.toUpperCase() + " export of " + AuditQueryService.describe(filter));
        String filename = "lkc-audit-trail-" + LocalDate.now(AuditQueryService.PLATFORM_ZONE) + "." + format;
        StreamingResponseBody body = out -> auditQueryService.export(filter, format, out);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType("csv".equals(format)
                        ? new MediaType("text", "csv", java.nio.charset.StandardCharsets.UTF_8)
                        : MediaType.APPLICATION_JSON)
                .body(body);
    }
}
