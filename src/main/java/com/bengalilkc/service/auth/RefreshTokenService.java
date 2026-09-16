package com.bengalilkc.service.auth;

import com.bengalilkc.common.exception.ApiException;
import com.bengalilkc.config.LkcProperties;
import com.bengalilkc.domain.entity.identity.RefreshToken;
import com.bengalilkc.repository.identity.RefreshTokenRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class RefreshTokenService {

    private final RefreshTokenRepository refreshTokenRepository;
    private final LkcProperties properties;
    private final SecureRandom secureRandom = new SecureRandom();

    /** Issues an opaque refresh token and stores only its SHA-256 hash. */
    @Transactional
    public String issue(String userId) {
        String raw = UUID.randomUUID() + "." + Long.toHexString(secureRandom.nextLong());
        long ttlDays = properties.getSecurity().getJwt().getRefreshTtlDays();
        RefreshToken entity = RefreshToken.builder()
                .userId(userId)
                .tokenHash(hash(raw))
                .expiresAt(Instant.now().plusSeconds(ttlDays * 24L * 60L * 60L))
                .revoked(false)
                .build();
        refreshTokenRepository.save(entity);
        return raw;
    }

    @Transactional(readOnly = true)
    public RefreshToken requireActive(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            throw ApiException.unauthorized("Refresh token is required.");
        }
        RefreshToken token = refreshTokenRepository
                .findByTokenHashAndRevokedFalse(hash(rawToken.trim()))
                .orElseThrow(() -> ApiException.unauthorized("Invalid or revoked refresh token."));
        if (token.getExpiresAt().isBefore(Instant.now())) {
            throw ApiException.unauthorized("Refresh token has expired. Please sign in again.");
        }
        return token;
    }

    @Transactional
    public void revoke(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return;
        }
        refreshTokenRepository.findByTokenHashAndRevokedFalse(hash(rawToken.trim())).ifPresent(t -> {
            t.setRevoked(true);
            refreshTokenRepository.save(t);
        });
    }

    @Transactional
    public void revokeAllForUser(String userId) {
        refreshTokenRepository.revokeAllForUser(userId);
    }

    public static String hash(String raw) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashed);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to hash refresh token", e);
        }
    }
}
