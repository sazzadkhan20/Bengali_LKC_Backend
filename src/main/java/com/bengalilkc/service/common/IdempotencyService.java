package com.bengalilkc.service.common;

import com.bengalilkc.common.exception.ApiException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Makes a POST safe to retry: the first request with a given {@code Idempotency-Key} runs,
 * later ones with the same key and payload get the stored response back.
 *
 * <p>Uses its own auto-committed statements (not the business transaction) so the claim on a key
 * is visible to concurrent requests immediately; the unique constraint decides the winner.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IdempotencyService {

    public static final String HEADER = "Idempotency-Key";
    private static final Pattern KEY = Pattern.compile("^[A-Za-z0-9_\\-:.]{8,100}$");
    /** A claimed key with no result after this long is treated as abandoned (e.g. server crash). */
    private static final long STALE_SECONDS = 120;
    private static final long RETENTION_HOURS = 24;

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public ResponseEntity<Map<String, Object>> execute(
            String userId,
            String endpoint,
            String key,
            Map<String, Object> request,
            Set<String> volatileFields,
            Supplier<Map<String, Object>> action) {
        if (key == null || key.isBlank()) {
            return ResponseEntity.ok(action.get());
        }
        String k = key.trim();
        if (!KEY.matcher(k).matches()) {
            throw ApiException.badRequest("Idempotency-Key must be 8–100 characters of letters, digits, - _ : .");
        }
        String hash = hash(request, volatileFields);

        ResponseEntity<Map<String, Object>> replay = claimOrReplay(userId, endpoint, k, hash);
        if (replay != null) {
            return replay;
        }

        Map<String, Object> result;
        try {
            result = action.get();
        } catch (RuntimeException e) {
            // Failed requests (validation, duplicate, conflict) release the key so a corrected retry can run.
            jdbc.update("delete from idempotency_key where user_id = ? and endpoint = ? and idem_key = ?",
                    userId, endpoint, k);
            throw e;
        }
        jdbc.update(
                "update idempotency_key set status_code = 200, response_json = ?, completed_at = now() "
                        + "where user_id = ? and endpoint = ? and idem_key = ?",
                toJson(result), userId, endpoint, k);
        return ResponseEntity.ok(result);
    }

    private ResponseEntity<Map<String, Object>> claimOrReplay(
            String userId, String endpoint, String key, String hash) {
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                jdbc.update(
                        "insert into idempotency_key (user_id, endpoint, idem_key, request_hash) values (?, ?, ?, ?)",
                        userId, endpoint, key, hash);
                return null;
            } catch (DuplicateKeyException dup) {
                List<Map<String, Object>> rows = jdbc.queryForList(
                        "select request_hash, status_code, response_json, created_at from idempotency_key "
                                + "where user_id = ? and endpoint = ? and idem_key = ?",
                        userId, endpoint, key);
                if (rows.isEmpty()) {
                    continue; // released between insert and select — try to claim again
                }
                Map<String, Object> row = rows.get(0);
                if (!hash.equals(row.get("request_hash"))) {
                    throw ApiException.unprocessable(
                            "This Idempotency-Key was already used for a different request.");
                }
                Object status = row.get("status_code");
                if (status != null) {
                    return ResponseEntity.status(((Number) status).intValue())
                            .header("Idempotent-Replayed", "true")
                            .body(fromJson((String) row.get("response_json")));
                }
                Instant created = ((Timestamp) row.get("created_at")).toInstant();
                if (created.isBefore(Instant.now().minusSeconds(STALE_SECONDS))) {
                    jdbc.update(
                            "delete from idempotency_key where user_id = ? and endpoint = ? and idem_key = ? "
                                    + "and status_code is null",
                            userId, endpoint, key);
                    continue;
                }
                throw ApiException.conflict("This request is already being processed. Please wait.");
            }
        }
        throw ApiException.conflict("This request is already being processed. Please wait.");
    }

    @Scheduled(fixedDelay = 3_600_000, initialDelay = 300_000)
    public void purgeExpired() {
        int n = jdbc.update("delete from idempotency_key where created_at < ?",
                Timestamp.from(Instant.now().minus(RETENTION_HOURS, ChronoUnit.HOURS)));
        if (n > 0) {
            log.info("Purged {} expired idempotency keys", n);
        }
    }

    private String hash(Map<String, Object> request, Set<String> volatileFields) {
        Map<String, Object> stable = new TreeMap<>();
        if (request != null) {
            request.forEach((k, v) -> {
                if (!volatileFields.contains(k)) {
                    stable.put(k, v);
                }
            });
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                    md.digest(objectMapper.writeValueAsString(stable).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String toJson(Map<String, Object> body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (Exception e) {
            return "{}";
        }
    }

    private Map<String, Object> fromJson(String json) {
        try {
            return json == null ? Map.of() : objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            return Map.of();
        }
    }
}
