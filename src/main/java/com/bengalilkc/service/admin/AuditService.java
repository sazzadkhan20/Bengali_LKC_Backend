package com.bengalilkc.service.admin;

import com.bengalilkc.domain.entity.admin.AuditEvent;
import com.bengalilkc.domain.enums.AuditTargetType;
import com.bengalilkc.domain.enums.Role;
import com.bengalilkc.repository.admin.AuditEventRepository;
import com.bengalilkc.security.UserPrincipal;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AuditService {

    private final AuditEventRepository auditEventRepository;
    private final IdGeneratorService idGeneratorService;

    @Transactional
    public AuditEvent record(
            String actorId,
            String actorRole,
            String action,
            AuditTargetType targetType,
            String targetId,
            String detail) {
        AuditEvent event = AuditEvent.builder()
                .id("aud-" + idGeneratorService.nextValue("audit"))
                .timestamp(Instant.now())
                .actorId(actorId)
                .actorRole(actorRole)
                .action(action)
                .targetType(targetType)
                .targetId(targetId)
                .detail(detail)
                .build();
        return auditEventRepository.save(event);
    }

    @Transactional
    public AuditEvent record(UserPrincipal principal, String action, AuditTargetType targetType, String targetId, String detail) {
        return record(principal.getId(), principal.getRole().name(), action, targetType, targetId, detail);
    }

    @Transactional
    public AuditEvent recordSystem(String action, AuditTargetType targetType, String targetId, String detail) {
        return record("system", "SYSTEM", action, targetType, targetId, detail);
    }

    @Transactional(readOnly = true)
    public List<AuditEvent> listAll() {
        return auditEventRepository.findAllByOrderByTimestampDesc();
    }

    public String toCsv(List<AuditEvent> events) {
        StringBuilder sb = new StringBuilder();
        sb.append("id,timestamp,actorId,actorRole,action,targetType,targetId,detail\n");
        for (AuditEvent e : events) {
            sb.append(csv(e.getId())).append(',')
                    .append(csv(e.getTimestamp() != null ? e.getTimestamp().toString() : "")).append(',')
                    .append(csv(e.getActorId())).append(',')
                    .append(csv(e.getActorRole())).append(',')
                    .append(csv(e.getAction())).append(',')
                    .append(csv(e.getTargetType() != null ? e.getTargetType().name() : "")).append(',')
                    .append(csv(e.getTargetId())).append(',')
                    .append(csv(e.getDetail()))
                    .append('\n');
        }
        return sb.toString();
    }

    private static String csv(String v) {
        String s = v == null ? "" : v.replace("\"", "\"\"");
        return "\"" + s + "\"";
    }
}
