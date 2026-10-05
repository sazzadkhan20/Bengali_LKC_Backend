package com.bengalilkc.security;

import com.bengalilkc.repository.admin.SystemConfigRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.RequiredArgsConstructor;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Fixed-window rate limiter driven by {@code system_config.ip_requests_per_minute}.
 *
 * <p>Signed-in requests are limited per user (from the verified JWT), not per IP: all browser traffic
 * reaches the API through the Next.js BFF, so the remote IP is the same for every user.
 * Anonymous requests fall back to the client IP.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
@RequiredArgsConstructor
public class RateLimitFilter extends OncePerRequestFilter {

    private static final long LIMIT_CACHE_MS = 30_000;

    private final SystemConfigRepository systemConfigRepository;
    private final ObjectMapper objectMapper;
    private final JwtService jwtService;

    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();
    private volatile int cachedLimit = 60;
    private volatile long cachedLimitAt;

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String path = request.getRequestURI();
        // The frontend server resolves the session on every page render; throttling it would sign
        // people out mid-navigation, and the token check behind it is cheap.
        boolean sessionRead = "GET".equals(request.getMethod()) && "/api/auth/session".equals(path);
        if (sessionRead || path.startsWith("/actuator") || path.startsWith("/swagger") || path.startsWith("/v3/api-docs")) {
            filterChain.doFilter(request, response);
            return;
        }

        int limit = currentLimit();
        long minute = Instant.now().getEpochSecond() / 60;
        Window window = windows.compute(clientKey(request), (k, existing) -> {
            if (existing == null || existing.minute != minute) {
                return new Window(minute, new AtomicInteger(1));
            }
            existing.count.incrementAndGet();
            return existing;
        });

        if (window.count.get() > limit) {
            response.setStatus(429);
            response.setHeader("Retry-After", String.valueOf(60 - Instant.now().getEpochSecond() % 60));
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            objectMapper.writeValue(response.getOutputStream(),
                    Map.of("error", "Too many requests. Please slow down."));
            return;
        }
        filterChain.doFilter(request, response);
    }

    private int currentLimit() {
        long now = System.currentTimeMillis();
        if (now - cachedLimitAt > LIMIT_CACHE_MS) {
            cachedLimit = systemConfigRepository.findById((short) 1)
                    .map(c -> Math.max(1, c.getIpRequestsPerMinute()))
                    .orElse(60);
            cachedLimitAt = now;
        }
        return cachedLimit;
    }

    private String clientKey(HttpServletRequest request) {
        String auth = request.getHeader("Authorization");
        if (auth != null && auth.startsWith("Bearer ")) {
            try {
                Claims claims = jwtService.parse(auth.substring(7));
                if (claims.getSubject() != null) {
                    return "u:" + claims.getSubject();
                }
            } catch (Exception ignored) {
                // invalid / expired token: limit by IP; the JWT filter rejects it afterwards
            }
        }
        return "ip:" + clientIp(request);
    }

    /** Drop counters from past minutes so the map does not grow with every user / IP ever seen. */
    @Scheduled(fixedDelay = 120_000)
    public void evictExpiredWindows() {
        long minute = Instant.now().getEpochSecond() / 60;
        windows.entrySet().removeIf(e -> e.getValue().minute < minute);
    }

    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr() != null ? request.getRemoteAddr() : "unknown";
    }

    private record Window(long minute, AtomicInteger count) {}
}
