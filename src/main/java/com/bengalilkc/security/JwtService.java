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
import org.springframework.stereotype.Service;

@Service
public class JwtService {

    private final LkcProperties properties;
    private final SecretKey key;

    public JwtService(LkcProperties properties) {
        this.properties = properties;
        byte[] secretBytes = properties.getSecurity().getJwt().getSecret().getBytes(StandardCharsets.UTF_8);
        this.key = Keys.hmacShaKeyFor(secretBytes);
    }

    public String createToken(String userId, String username, Role role, boolean mustChangePassword, long ttlMinutes) {
        Instant now = Instant.now();
        Instant exp = now.plusSeconds(ttlMinutes * 60);
        return Jwts.builder()
                .issuer(properties.getSecurity().getJwt().getIssuer())
                .subject(userId)
                .claim("username", username)
                .claim("role", role.name())
                .claim("mustChangePassword", mustChangePassword)
                .issuedAt(Date.from(now))
                .expiration(Date.from(exp))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }

    public Claims parse(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .requireIssuer(properties.getSecurity().getJwt().getIssuer())
                .build()
                .parseSignedClaims(token)
                .getPayload();
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
