package com.bengalilkc.service.admin;

import com.bengalilkc.common.exception.ApiException;
import com.bengalilkc.domain.entity.admin.SystemConfig;
import com.bengalilkc.domain.enums.AuditTargetType;
import com.bengalilkc.repository.admin.SystemConfigRepository;
import com.bengalilkc.security.UserPrincipal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Function;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ConfigService {

    public static final String CONFIG_UPDATED = "CONFIG_UPDATED";

    private record IntField(String key, String label, int min, int max,
            Function<SystemConfig, Integer> get, BiConsumer<SystemConfig, Integer> set) {}

    private record BoolField(String key, String label,
            Function<SystemConfig, Boolean> get, BiConsumer<SystemConfig, Boolean> set) {}

    private static final List<IntField> INT_FIELDS = List.of(
            new IntField("maxEntriesPerSession", "Max entries per session", 1, 10_000,
                    SystemConfig::getMaxEntriesPerSession, SystemConfig::setMaxEntriesPerSession),
            new IntField("maxEntriesPerDay", "Max entries per 24 hours", 1, 100_000,
                    SystemConfig::getMaxEntriesPerDay, SystemConfig::setMaxEntriesPerDay),
            new IntField("suspensionThreshold", "Suspension threshold", 2, 1_000_000,
                    SystemConfig::getSuspensionThreshold, SystemConfig::setSuspensionThreshold),
            new IntField("minSubmissionSeconds", "Minimum submission time", 0, 600,
                    SystemConfig::getMinSubmissionSeconds, SystemConfig::setMinSubmissionSeconds),
            new IntField("anomalyThresholdPerHour", "Anomaly alert threshold", 1, 100_000,
                    SystemConfig::getAnomalyThresholdPerHour, SystemConfig::setAnomalyThresholdPerHour),
            new IntField("ipRequestsPerMinute", "Request rate limit", 30, 100_000,
                    SystemConfig::getIpRequestsPerMinute, SystemConfig::setIpRequestsPerMinute),
            new IntField("sessionTimeoutMinutes", "Inactive session timeout", 5, 1_440,
                    SystemConfig::getSessionTimeoutMinutes, SystemConfig::setSessionTimeoutMinutes),
            new IntField("passwordMinLength", "Password minimum length", 8, 128,
                    SystemConfig::getPasswordMinLength, SystemConfig::setPasswordMinLength),
            new IntField("rewardPointPerEntry", "Reward points per validated entry", 1, 1_000,
                    SystemConfig::getRewardPointPerEntry, SystemConfig::setRewardPointPerEntry),
            new IntField("tierGoldMin", "Gold tier threshold", 1, 10_000_000,
                    SystemConfig::getTierGoldMin, SystemConfig::setTierGoldMin),
            new IntField("tierSilverMin", "Silver tier threshold", 1, 10_000_000,
                    SystemConfig::getTierSilverMin, SystemConfig::setTierSilverMin),
            new IntField("tierBronzeMin", "Bronze tier threshold", 1, 10_000_000,
                    SystemConfig::getTierBronzeMin, SystemConfig::setTierBronzeMin));

    private static final List<BoolField> BOOL_FIELDS = List.of(
            new BoolField("captchaEnabled", "CAPTCHA challenge",
                    SystemConfig::isCaptchaEnabled, SystemConfig::setCaptchaEnabled),
            new BoolField("honeypotEnabled", "Honeypot fields",
                    SystemConfig::isHoneypotEnabled, SystemConfig::setHoneypotEnabled));

    private final SystemConfigRepository systemConfigRepository;
    private final AuditService auditService;
    private final CaptchaService captchaService;
    private final NamedParameterJdbcTemplate jdbc;

    public ConfigService(
            SystemConfigRepository systemConfigRepository,
            AuditService auditService,
            CaptchaService captchaService,
            NamedParameterJdbcTemplate jdbc) {
        this.systemConfigRepository = systemConfigRepository;
        this.auditService = auditService;
        this.captchaService = captchaService;
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public SystemConfig getConfig() {
        return systemConfigRepository.findById((short) 1)
                .orElseThrow(() -> ApiException.notFound("System configuration not found."));
    }

    public Map<String, Object> toDto(SystemConfig c) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (IntField f : INT_FIELDS) {
            m.put(f.key(), f.get().apply(c));
        }
        for (BoolField f : BOOL_FIELDS) {
            m.put(f.key(), f.get().apply(c));
        }
        m.put("captchaScoreThreshold", c.getCaptchaScoreThreshold());
        return m;
    }

    /** Configuration plus who changed it last and the CAPTCHA provider in use. */
    @Transactional(readOnly = true)
    public Map<String, Object> view() {
        SystemConfig c = getConfig();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("config", toDto(c));
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("updatedAt", c.getUpdatedAt() == null ? null : c.getUpdatedAt().toString());
        List<Map<String, Object>> last = jdbc.queryForList("""
                select a.timestamp, a.detail, coalesce(u.name, a.actor_id) as actor_name
                from audit_event a left join app_user u on u.id = a.actor_id
                where a.action = :action order by a.timestamp desc limit 5
                """, new MapSqlParameterSource("action", CONFIG_UPDATED));
        meta.put("history", last.stream().map(r -> {
            Map<String, Object> h = new LinkedHashMap<>();
            h.put("at", r.get("timestamp") instanceof java.sql.Timestamp t ? t.toInstant().toString() : null);
            h.put("by", r.get("actor_name"));
            h.put("detail", r.get("detail"));
            return h;
        }).toList());
        meta.put("captchaProvider", captchaService.provider());
        m.put("meta", meta);
        m.put("lexicon", jdbc.query("""
                select l.id, l.label, l.language, l.email, l.license, l.version, l.dc_publisher, l.dc_source,
                       l.dc_description, l.updated_at,
                       (select count(distinct lower(ili)) from synset where ili is not null and ili <> '') as ili_codes
                from lexicon l order by l.created_at limit 1
                """, new MapSqlParameterSource(), rs -> {
                    if (!rs.next()) {
                        return null;
                    }
                    Map<String, Object> l = new LinkedHashMap<>();
                    l.put("id", rs.getString("id"));
                    l.put("label", rs.getString("label"));
                    l.put("language", rs.getString("language"));
                    l.put("email", rs.getString("email"));
                    l.put("license", rs.getString("license"));
                    l.put("version", rs.getString("version"));
                    l.put("publisher", rs.getString("dc_publisher"));
                    l.put("source", rs.getString("dc_source"));
                    l.put("description", rs.getString("dc_description"));
                    l.put("iliCodes", rs.getLong("ili_codes"));
                    return l;
                }));
        return m;
    }

    @Transactional
    public Map<String, Object> update(UserPrincipal principal, Map<String, Object> patch) {
        SystemConfig c = getConfig();
        Map<String, String> errors = new LinkedHashMap<>();
        Map<String, Integer> ints = new LinkedHashMap<>();
        for (IntField f : INT_FIELDS) {
            int current = f.get().apply(c);
            if (!patch.containsKey(f.key())) {
                ints.put(f.key(), current);
                continue;
            }
            Object v = patch.get(f.key());
            if (!(v instanceof Number n) || n.doubleValue() != Math.rint(n.doubleValue())) {
                errors.put(f.key(), "Enter a whole number.");
                ints.put(f.key(), current);
                continue;
            }
            long value = n.longValue();
            if (value < f.min() || value > f.max()) {
                errors.put(f.key(), "Must be between " + f.min() + " and " + String.format("%,d", f.max()) + ".");
            }
            ints.put(f.key(), (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, value)));
        }
        double score = c.getCaptchaScoreThreshold();
        if (patch.containsKey("captchaScoreThreshold")) {
            if (patch.get("captchaScoreThreshold") instanceof Number n && n.doubleValue() >= 0 && n.doubleValue() <= 1) {
                score = Math.round(n.doubleValue() * 100) / 100.0;
            } else {
                errors.put("captchaScoreThreshold", "Must be between 0.0 and 1.0.");
            }
        }
        for (BoolField f : BOOL_FIELDS) {
            if (patch.containsKey(f.key()) && !(patch.get(f.key()) instanceof Boolean)) {
                errors.put(f.key(), "Must be on or off.");
            }
        }

        if (!errors.containsKey("maxEntriesPerDay") && ints.get("maxEntriesPerDay") < ints.get("maxEntriesPerSession")) {
            errors.put("maxEntriesPerDay", "Must be at least the session limit (" + ints.get("maxEntriesPerSession") + ").");
        }
        if (!errors.containsKey("suspensionThreshold") && ints.get("suspensionThreshold") <= ints.get("maxEntriesPerDay")) {
            errors.put("suspensionThreshold", "Must be greater than the daily limit (" + ints.get("maxEntriesPerDay")
                    + "), otherwise normal use would suspend accounts.");
        }
        if (!errors.containsKey("tierSilverMin") && ints.get("tierSilverMin") <= ints.get("tierBronzeMin")) {
            errors.put("tierSilverMin", "Must be greater than the Bronze threshold (" + ints.get("tierBronzeMin") + ").");
        }
        if (!errors.containsKey("tierGoldMin") && ints.get("tierGoldMin") <= ints.get("tierSilverMin")) {
            errors.put("tierGoldMin", "Must be greater than the Silver threshold (" + ints.get("tierSilverMin") + ").");
        }
        if (!errors.isEmpty()) {
            throw ApiException.unprocessable(errors);
        }

        List<String> changes = new ArrayList<>();
        for (IntField f : INT_FIELDS) {
            int before = f.get().apply(c);
            int after = ints.get(f.key());
            if (before != after) {
                changes.add(f.label() + ": " + before + " → " + after);
                f.set().accept(c, after);
            }
        }
        if (Double.compare(score, c.getCaptchaScoreThreshold()) != 0) {
            changes.add("CAPTCHA score threshold: " + c.getCaptchaScoreThreshold() + " → " + score);
            c.setCaptchaScoreThreshold(score);
        }
        for (BoolField f : BOOL_FIELDS) {
            if (patch.get(f.key()) instanceof Boolean b && b != f.get().apply(c)) {
                changes.add(f.label() + ": " + (b ? "off → on" : "on → off"));
                f.set().accept(c, b);
            }
        }

        if (!changes.isEmpty()) {
            c.setUpdatedAt(Instant.now());
            systemConfigRepository.save(c);
            auditService.record(principal, CONFIG_UPDATED, AuditTargetType.CONFIG, "system", String.join("; ", changes));
            systemConfigRepository.flush();
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("changed", changes);
        body.putAll(view());
        return body;
    }
}
