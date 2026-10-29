package com.cinema.cms.service;

import com.cinema.cms.entity.User;
import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class JwtService {
    private static final String ISSUER = "cinema-management";
    private final SecretKey key;
    private final long ttl;
    private final Clock clock;
    public JwtService(@Value("${app.jwt.secret}") String secret, @Value("${app.jwt.ttl-seconds}") long ttl, Clock clock) {
        if (ttl < 60 || ttl > 86400) throw new IllegalArgumentException("JWT TTL must be 60..86400 seconds");
        if (secret.getBytes(StandardCharsets.UTF_8).length < 32) throw new IllegalArgumentException("JWT secret must be at least 32 bytes");
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8)); this.ttl = ttl; this.clock = clock;
    }
    public String issue(User user) {
        Instant now = clock.instant();
        return Jwts.builder().issuer(ISSUER).subject(user.getId().toString()).id(UUID.randomUUID().toString())
                .claim("role", user.getRole().name()).claim("sv", user.getSecurityVersion())
                .issuedAt(Date.from(now)).expiration(Date.from(now.plusSeconds(ttl)))
                .signWith(key, Jwts.SIG.HS256).compact();
    }
    public Claims parse(String token) {
        Claims c = Jwts.parser().verifyWith(key).requireIssuer(ISSUER)
                .clock(() -> Date.from(clock.instant())).build().parseSignedClaims(token).getPayload();
        if (c.getExpiration() == null || c.getIssuedAt() == null || c.getId() == null || c.getSubject() == null
                || c.getIssuedAt().toInstant().isAfter(clock.instant()) || !(c.get("sv") instanceof Number)) {
            throw new MalformedJwtException("Missing or invalid claims");
        }
        return c;
    }
    public long ttlSeconds() { return ttl; }
}
