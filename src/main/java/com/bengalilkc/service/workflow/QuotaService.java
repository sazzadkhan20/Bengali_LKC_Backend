package com.bengalilkc.service.workflow;

import com.bengalilkc.domain.entity.admin.SystemConfig;
import com.bengalilkc.domain.enums.AuditTargetType;
import com.bengalilkc.repository.identity.AppUserRepository;
import com.bengalilkc.repository.identity.CredentialRepository;
import com.bengalilkc.security.UserPrincipal;
import com.bengalilkc.service.admin.AuditService;
import com.bengalilkc.service.admin.ConfigService;
import com.bengalilkc.service.auth.RefreshTokenService;
import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Contributor submission quotas and anti-abuse gates.
 *
 * <ul>
 *   <li>Session limit: entries since the user's most recent sign-in (or, without one, within the
 *       session timeout).</li>
 *   <li>Daily limit: entries in a rolling 24 hours.</li>
 *   <li>Suspension: accepted entries plus attempts blocked by either limit in a rolling 24 hours
 *       reaching {@code suspension_threshold} suspends the account pending Architect review.</li>
 *   <li>Anomaly: an account creating {@code anomaly_threshold_per_hour} or more entries within 60
 *       minutes raises one moderator alert per burst; its further entries are held UNDER_REVIEW.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class QuotaService {

    public static final String QUOTA_EXCEEDED = "QUOTA_EXCEEDED";
    public static final String ANOMALY_DETECTED = "ANOMALY_DETECTED";
    public static final String ACCOUNT_AUTO_SUSPENDED = "ACCOUNT_AUTO_SUSPENDED";

    public enum Outcome { OK, ANOMALY_HOLD, BLOCKED_SESSION, BLOCKED_DAY, SUSPENDED }

    public record Gate(Outcome outcome, String message, long lastHour) {}

    private static final String COUNTS_SQL = """
            with session_start as (
              select coalesce(
                (select max(timestamp) from audit_event
                  where actor_id = :me and action in ('LOGIN', 'LOGIN_ONE_TIME_PASSWORD')),
                now() - make_interval(mins => :timeout)) as at
            )
            select
              (select count(*) from submission
                where contributor_id = :me and created_at > now() - interval '24 hours') as day_used,
              (select count(*) from submission, session_start
                where contributor_id = :me and created_at >= session_start.at) as session_used,
              (select at from session_start) as session_started,
              (select count(*) from quota_block
                where user_id = :me and blocked_at > now() - interval '24 hours') as blocked,
              (select count(*) from submission
                where contributor_id = :me and created_at > now() - interval '60 minutes') as last_hour
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final ConfigService configService;
    private final AuditService auditService;
    private final AppUserRepository userRepository;
    private final CredentialRepository credentialRepository;
    private final RefreshTokenService refreshTokenService;

    @Transactional(readOnly = true)
    public Map<String, Object> usage(String userId) {
        SystemConfig cfg = configService.getConfig();
        Map<String, Object> row = counts(userId, cfg);
        long dayUsed = num(row, "day_used");
        long blocked = num(row, "blocked");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("dayUsed", dayUsed);
        m.put("dayLimit", cfg.getMaxEntriesPerDay());
        m.put("sessionUsed", num(row, "session_used"));
        m.put("sessionLimit", cfg.getMaxEntriesPerSession());
        Object started = row.get("session_started");
        m.put("sessionStartedAt", started instanceof Timestamp t ? t.toInstant().toString() : null);
        m.put("blocked", blocked);
        m.put("attempts", dayUsed + blocked);
        m.put("suspensionThreshold", cfg.getSuspensionThreshold());
        m.put("lastHour", num(row, "last_hour"));
        m.put("anomalyThresholdPerHour", cfg.getAnomalyThresholdPerHour());
        return m;
    }

    /**
     * Decides whether the user may create another entry. Runs in its own transaction so blocked
     * attempts, suspensions and anomaly alerts persist even though the caller rejects the request.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Gate check(UserPrincipal principal) {
        SystemConfig cfg = configService.getConfig();
        String me = principal.getId();
        Map<String, Object> row = counts(me, cfg);
        long dayUsed = num(row, "day_used");
        long sessionUsed = num(row, "session_used");
        long blocked = num(row, "blocked");
        long lastHour = num(row, "last_hour");

        long attempts = dayUsed + blocked + 1;
        if (cfg.getSuspensionThreshold() > 0 && attempts >= cfg.getSuspensionThreshold()) {
            suspend(me, attempts, cfg.getSuspensionThreshold());
            return new Gate(Outcome.SUSPENDED,
                    "Your account was suspended automatically after " + attempts
                            + " submission attempts in 24 hours. The Platform Architect will review it.",
                    lastHour);
        }
        if (cfg.getMaxEntriesPerSession() > 0 && sessionUsed >= cfg.getMaxEntriesPerSession()) {
            block(principal, "SESSION", cfg.getMaxEntriesPerSession());
            return new Gate(Outcome.BLOCKED_SESSION,
                    "Session entry limit reached (" + cfg.getMaxEntriesPerSession()
                            + " per session). Sign out and back in later to continue. "
                            + "Repeated attempts past a limit lead to automatic suspension.",
                    lastHour);
        }
        if (cfg.getMaxEntriesPerDay() > 0 && dayUsed >= cfg.getMaxEntriesPerDay()) {
            block(principal, "DAY", cfg.getMaxEntriesPerDay());
            return new Gate(Outcome.BLOCKED_DAY,
                    "Daily entry limit reached (" + cfg.getMaxEntriesPerDay()
                            + " in 24 hours). Repeated attempts past a limit lead to automatic suspension.",
                    lastHour);
        }
        if (cfg.getAnomalyThresholdPerHour() > 0 && lastHour >= cfg.getAnomalyThresholdPerHour()) {
            if (!recentlyAudited(ANOMALY_DETECTED, me, "%")) {
                auditService.recordSystem(ANOMALY_DETECTED, AuditTargetType.USER, me,
                        principal.getName() + " created " + lastHour + " entries in the last hour (alert threshold "
                                + cfg.getAnomalyThresholdPerHour() + "); further entries are held UNDER_REVIEW");
            }
            return new Gate(Outcome.ANOMALY_HOLD,
                    "Held UNDER_REVIEW: " + lastHour + " entries in the last hour exceeds the anomaly threshold of "
                            + cfg.getAnomalyThresholdPerHour() + ".",
                    lastHour);
        }
        return new Gate(Outcome.OK, null, lastHour);
    }

    private void block(UserPrincipal principal, String kind, int limit) {
        jdbc.update("insert into quota_block (user_id, limit_kind) values (:me, :kind)",
                new MapSqlParameterSource("me", principal.getId()).addValue("kind", kind));
        // One audit row per limit per hour; the blocked attempts themselves are counted in quota_block.
        String label = "SESSION".equals(kind) ? "Session" : "Daily";
        if (!recentlyAudited(QUOTA_EXCEEDED, principal.getId(), label + "%")) {
            auditService.record(principal, QUOTA_EXCEEDED, AuditTargetType.USER, principal.getId(),
                    label + " entry limit of " + limit + " reached");
        }
    }

    private void suspend(String userId, long attempts, int threshold) {
        userRepository.findById(userId).ifPresent(user -> {
            if (user.isSuspended()) {
                return;
            }
            user.setSuspended(true);
            userRepository.save(user);
            credentialRepository.findByUserId(userId).ifPresent(c -> {
                c.setDeactivated(true);
                credentialRepository.save(c);
            });
            refreshTokenService.revokeAllForUser(userId);
            auditService.recordSystem(ACCOUNT_AUTO_SUSPENDED, AuditTargetType.USER, userId,
                    user.getName() + " suspended automatically: " + attempts
                            + " submission attempts in 24 hours (threshold " + threshold
                            + "); awaiting Platform Architect review");
        });
    }

    private boolean recentlyAudited(String action, String userId, String detailLike) {
        MapSqlParameterSource p = new MapSqlParameterSource("action", action)
                .addValue("type", AuditTargetType.USER.name())
                .addValue("target", userId)
                .addValue("detail", detailLike);
        Boolean exists = jdbc.queryForObject("""
                select exists (select 1 from audit_event
                  where action = :action and target_type = :type and target_id = :target
                    and detail like :detail and timestamp > now() - interval '60 minutes')
                """, p, Boolean.class);
        return Boolean.TRUE.equals(exists);
    }

    private Map<String, Object> counts(String userId, SystemConfig cfg) {
        return jdbc.queryForMap(COUNTS_SQL, new MapSqlParameterSource("me", userId)
                .addValue("timeout", Math.max(1, cfg.getSessionTimeoutMinutes())));
    }

    private static long num(Map<String, Object> row, String key) {
        return row.get(key) instanceof Number n ? n.longValue() : 0L;
    }
}
