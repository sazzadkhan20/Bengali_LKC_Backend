package com.bengalilkc.service.workflow;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/** Timing rules of arbitration: one daily deadline check at 10:00 Asia/Dhaka closes windows and voting rounds. */
public final class ArbitrationSchedule {

    public static final String ZONE_ID = "Asia/Dhaka";
    public static final ZoneId ZONE = ZoneId.of(ZONE_ID);
    public static final LocalTime CHECK_TIME = LocalTime.of(10, 0);
    /** Spring cron (second minute hour day month weekday) matching {@link #CHECK_TIME}. */
    public static final String CHECK_CRON = "0 0 10 * * *";

    public static final Duration RESPONSE_WINDOW = Duration.ofHours(72);
    public static final Duration VOTING_ROUND = Duration.ofDays(7);

    private ArbitrationSchedule() {
    }

    /** The first daily check at or after {@code instant}: when a deadline falling at that instant is processed. */
    public static Instant checkAtOrAfter(Instant instant) {
        ZonedDateTime local = instant.atZone(ZONE);
        ZonedDateTime check = local.toLocalDate().atTime(CHECK_TIME).atZone(ZONE);
        if (check.toInstant().isBefore(instant)) {
            check = check.plusDays(1);
        }
        return check.toInstant();
    }
}
