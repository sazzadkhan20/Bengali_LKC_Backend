package com.bengalilkc.service.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Google reCAPTCHA v3 verification. Without both keys configured the platform falls back to the
 * checkbox challenge and the score threshold is not applied.
 */
@Slf4j
@Service
public class CaptchaService {

    public static final String PROVIDER_RECAPTCHA = "RECAPTCHA_V3";
    public static final String PROVIDER_CHECKBOX = "CHECKBOX";
    public static final String ACTION = "submit_entry";
    private static final URI VERIFY_URI = URI.create("https://www.google.com/recaptcha/api/siteverify");

    private final String siteKey;
    private final String secretKey;
    private final ObjectMapper objectMapper;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public CaptchaService(
            @Value("${lkc.captcha.recaptcha.site-key:}") String siteKey,
            @Value("${lkc.captcha.recaptcha.secret-key:}") String secretKey,
            ObjectMapper objectMapper) {
        this.siteKey = siteKey == null ? "" : siteKey.strip();
        this.secretKey = secretKey == null ? "" : secretKey.strip();
        this.objectMapper = objectMapper;
    }

    public boolean recaptchaConfigured() {
        return !siteKey.isEmpty() && !secretKey.isEmpty();
    }

    public String provider() {
        return recaptchaConfigured() ? PROVIDER_RECAPTCHA : PROVIDER_CHECKBOX;
    }

    /** What the Quick Input form needs to render the challenge. Never exposes the secret key. */
    public Map<String, Object> clientConfig(boolean enabled) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", enabled);
        m.put("provider", provider());
        m.put("siteKey", recaptchaConfigured() ? siteKey : null);
        m.put("action", ACTION);
        return m;
    }

    /**
     * Verifies the challenge carried in a submission payload.
     *
     * @return {@code null} when it passes, otherwise a message for the user
     */
    public String verify(Map<String, Object> payload, double scoreThreshold) {
        if (!recaptchaConfigured()) {
            return Boolean.TRUE.equals(payload.get("captchaPassed"))
                    ? null
                    : "Please complete the CAPTCHA verification.";
        }
        Object raw = payload.get("captchaToken");
        String token = raw == null ? "" : String.valueOf(raw).strip();
        if (token.isEmpty()) {
            return "CAPTCHA verification is missing. Reload the page and try again.";
        }
        try {
            String form = "secret=" + URLEncoder.encode(secretKey, StandardCharsets.UTF_8)
                    + "&response=" + URLEncoder.encode(token, StandardCharsets.UTF_8);
            HttpRequest request = HttpRequest.newBuilder(VERIFY_URI)
                    .timeout(Duration.ofSeconds(8))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(form))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            JsonNode body = objectMapper.readTree(response.body());
            if (!body.path("success").asBoolean(false)) {
                return "CAPTCHA verification failed or expired. Please try again.";
            }
            String action = body.path("action").asText("");
            if (!ACTION.equals(action)) {
                return "CAPTCHA verification failed. Please try again.";
            }
            double score = body.path("score").asDouble(0);
            if (score < scoreThreshold) {
                return "Your submission looks automated (CAPTCHA score " + String.format("%.1f", score)
                        + "). Wait a moment and try again.";
            }
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "CAPTCHA service is unreachable. Please try again shortly.";
        } catch (Exception e) {
            log.warn("reCAPTCHA verification failed: {}", e.getMessage());
            return "CAPTCHA service is unreachable. Please try again shortly.";
        }
    }
}
