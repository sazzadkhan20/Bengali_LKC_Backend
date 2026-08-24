package com.bengalilkc.service.admin;

import com.bengalilkc.common.exception.ApiException;
import com.bengalilkc.domain.entity.admin.SystemConfig;
import com.bengalilkc.domain.enums.AuditTargetType;
import com.bengalilkc.repository.admin.SystemConfigRepository;
import com.bengalilkc.security.UserPrincipal;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ConfigService {

    private final SystemConfigRepository systemConfigRepository;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public SystemConfig getConfig() {
        return systemConfigRepository.findById((short) 1)
                .orElseThrow(() -> ApiException.notFound("System configuration not found."));
    }

    @Transactional(readOnly = true)
    public Map<String, Object> toDto(SystemConfig c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("maxEntriesPerSession", c.getMaxEntriesPerSession());
        m.put("maxEntriesPerDay", c.getMaxEntriesPerDay());
        m.put("suspensionThreshold", c.getSuspensionThreshold());
        m.put("minSubmissionSeconds", c.getMinSubmissionSeconds());
        m.put("anomalyThresholdPerHour", c.getAnomalyThresholdPerHour());
        m.put("ipRequestsPerMinute", c.getIpRequestsPerMinute());
        m.put("sessionTimeoutMinutes", c.getSessionTimeoutMinutes());
        m.put("captchaEnabled", c.isCaptchaEnabled());
        m.put("honeypotEnabled", c.isHoneypotEnabled());
        m.put("captchaScoreThreshold", c.getCaptchaScoreThreshold());
        m.put("passwordMinLength", c.getPasswordMinLength());
        m.put("rewardPointPerEntry", c.getRewardPointPerEntry());
        m.put("tierGoldMin", c.getTierGoldMin());
        m.put("tierSilverMin", c.getTierSilverMin());
        m.put("tierBronzeMin", c.getTierBronzeMin());
        return m;
    }

    @Transactional
    public Map<String, Object> update(UserPrincipal principal, Map<String, Object> patch) {
        SystemConfig c = getConfig();
        setInt(patch, "maxEntriesPerSession", c::setMaxEntriesPerSession);
        setInt(patch, "maxEntriesPerDay", c::setMaxEntriesPerDay);
        setInt(patch, "suspensionThreshold", c::setSuspensionThreshold);
        setInt(patch, "minSubmissionSeconds", c::setMinSubmissionSeconds);
        setInt(patch, "anomalyThresholdPerHour", c::setAnomalyThresholdPerHour);
        setInt(patch, "ipRequestsPerMinute", c::setIpRequestsPerMinute);
        setInt(patch, "sessionTimeoutMinutes", c::setSessionTimeoutMinutes);
        setInt(patch, "passwordMinLength", c::setPasswordMinLength);
        setInt(patch, "rewardPointPerEntry", c::setRewardPointPerEntry);
        setInt(patch, "tierGoldMin", c::setTierGoldMin);
        setInt(patch, "tierSilverMin", c::setTierSilverMin);
        setInt(patch, "tierBronzeMin", c::setTierBronzeMin);
        if (patch.get("captchaScoreThreshold") instanceof Number n) {
            c.setCaptchaScoreThreshold(n.doubleValue());
        }
        if (patch.get("captchaEnabled") instanceof Boolean b) {
            c.setCaptchaEnabled(b);
        }
        if (patch.get("honeypotEnabled") instanceof Boolean b) {
            c.setHoneypotEnabled(b);
        }
        c.setUpdatedAt(Instant.now());
        systemConfigRepository.save(c);
        try {
            auditService.record(principal, "CONFIG_UPDATED", AuditTargetType.CONFIG, null, objectMapper.writeValueAsString(toDto(c)));
        } catch (Exception e) {
            auditService.record(principal, "CONFIG_UPDATED", AuditTargetType.CONFIG, null, "config updated");
        }
        return toDto(c);
    }

    private static void setInt(Map<String, Object> patch, String key, java.util.function.IntConsumer setter) {
        Object v = patch.get(key);
        if (v instanceof Number n && n.doubleValue() >= 0) {
            setter.accept(n.intValue());
        }
    }
}
