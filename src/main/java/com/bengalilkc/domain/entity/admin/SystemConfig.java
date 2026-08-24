package com.bengalilkc.domain.entity.admin;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "system_config")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SystemConfig {

    @Id
    private Short id;

    @Column(name = "max_entries_per_session", nullable = false)
    private int maxEntriesPerSession;

    @Column(name = "max_entries_per_day", nullable = false)
    private int maxEntriesPerDay;

    @Column(name = "suspension_threshold", nullable = false)
    private int suspensionThreshold;

    @Column(name = "min_submission_seconds", nullable = false)
    private int minSubmissionSeconds;

    @Column(name = "anomaly_threshold_per_hour", nullable = false)
    private int anomalyThresholdPerHour;

    @Column(name = "ip_requests_per_minute", nullable = false)
    private int ipRequestsPerMinute;

    @Column(name = "session_timeout_minutes", nullable = false)
    private int sessionTimeoutMinutes;

    @Column(name = "captcha_enabled", nullable = false)
    private boolean captchaEnabled;

    @Column(name = "honeypot_enabled", nullable = false)
    private boolean honeypotEnabled;

    @Column(name = "captcha_score_threshold", nullable = false)
    private double captchaScoreThreshold;

    @Column(name = "password_min_length", nullable = false)
    private int passwordMinLength;

    @Column(name = "reward_point_per_entry", nullable = false)
    private int rewardPointPerEntry;

    @Column(name = "tier_gold_min", nullable = false)
    private int tierGoldMin;

    @Column(name = "tier_silver_min", nullable = false)
    private int tierSilverMin;

    @Column(name = "tier_bronze_min", nullable = false)
    private int tierBronzeMin;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }
}
