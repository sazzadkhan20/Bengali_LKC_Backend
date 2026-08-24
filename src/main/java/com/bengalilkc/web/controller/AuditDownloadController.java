package com.bengalilkc.web.controller;

import com.bengalilkc.domain.entity.admin.AuditEvent;
import com.bengalilkc.domain.enums.Permission;
import com.bengalilkc.security.RequirePermission;
import com.bengalilkc.service.admin.AuditService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/audit")
@RequiredArgsConstructor
public class AuditDownloadController {

    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    @GetMapping("/download")
    @RequirePermission(Permission.AUDIT_LOG)
    public ResponseEntity<String> download(@RequestParam(defaultValue = "json") String fmt) throws Exception {
        List<AuditEvent> events = auditService.listAll();
        if ("csv".equalsIgnoreCase(fmt)) {
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"lkc-audit-trail.csv\"")
                    .contentType(new MediaType("text", "csv"))
                    .body(auditService.toCsv(events));
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"lkc-audit-trail.json\"")
                .contentType(MediaType.APPLICATION_JSON)
                .body(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(events));
    }
}
