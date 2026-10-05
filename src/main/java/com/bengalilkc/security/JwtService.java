package com.bengalilkc.security;

import com.bengalilkc.config.LkcProperties;
import com.bengalilkc.domain.enums.Role;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import javax.crypto.SecretKey;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Service;

@Service
public class JwtService {

    public static final String CLAIM_TOKEN_TYPE = "typ";
    public static final String TYPE_ACCESS = "access";

    private final LkcProperties properties;
    private final SecretKey key;

    public JwtService(LkcProperties properties, Environment environment) {
        this.properties = properties;
        String secret = properties.getSecurity().getJwt().getSecret();
        if (environment.acceptsProfiles(Profiles.of("prod"))
                && (secret == null || secret.length() < 32 || secret.contains("DevOnly"))) {
            throw new IllegalStateException(
                    "JWT_SECRET must be set to a private random value (at least 32 characters) in production.");
        }
        byte[] secretBytes = secret.getBytes(StandardCharsets.UTF_8);
        this.key = Keys.hmacShaKeyFor(secretBytes);
    }

    public String createAccessToken(
            String userId, String username, Role role, boolean mustChangePassword, long ttlMinutes) {
        return createAccessToken(
                userId, username, role, mustChangePassword, Instant.now().plusSeconds(Math.max(ttlMinutes, 1) * 60));
    }

    public String createAccessToken(
            String userId, String username, Role role, boolean mustChangePassword, Instant exp) {
        Instant now = Instant.now();
        return Jwts.builder()
                .issuer(properties.getSecurity().getJwt().getIssuer())
                .subject(userId)
                .claim("username", username)
                .claim("role", role.name())
                .claim("mustChangePassword", mustChangePassword)
                .claim(CLAIM_TOKEN_TYPE, TYPE_ACCESS)
                .issuedAt(Date.from(now))
                .expiration(Date.from(exp))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }

    /** @deprecated use {@link #createAccessToken} */
    @Deprecated
    public String createToken(String userId, String username, Role role, boolean mustChangePassword, long ttlMinutes) {
        return createAccessToken(userId, username, role, mustChangePassword, ttlMinutes);
    }

    public Claims parse(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .requireIssuer(properties.getSecurity().getJwt().getIssuer())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public Claims parseAccessToken(String token) {
        Claims claims = parse(token);
        String typ = claims.get(CLAIM_TOKEN_TYPE, String.class);
        if (typ != null && !TYPE_ACCESS.equals(typ)) {
            throw new IllegalArgumentException("Not an access token");
        }
        return claims;
    }

    public Instant expiration(String token) {
        return parse(token).getExpiration().toInstant();
    }

    public boolean isValid(String token) {
        try {
            parse(token);
            return true;
        } catch (Exception ex) {
            return false;
        }
    }
}
