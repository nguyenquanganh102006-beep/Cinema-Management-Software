package com.cinema.cms.service;

import java.time.Clock;
import java.time.Instant;
import java.sql.Timestamp;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TokenRevocationService {
    private final JdbcTemplate jdbc;
    private final Clock clock;
    public TokenRevocationService(JdbcTemplate jdbc, Clock clock) { this.jdbc = jdbc; this.clock = clock; }
    public boolean isRevoked(String tokenId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from revoked_access_tokens where token_id = ?)", Boolean.class, tokenId));
    }
    @Transactional
    public void revoke(String tokenId, Instant expiresAt) {
        jdbc.update("insert into revoked_access_tokens(token_id, expires_at) values (?, ?) on conflict (token_id) do nothing", tokenId, Timestamp.from(expiresAt));
    }
    @Scheduled(fixedDelayString = "${app.jwt.cleanup-delay-ms:600000}")
    @Transactional
    public void cleanup() {
        jdbc.update("delete from revoked_access_tokens where expires_at <= ?", Timestamp.from(clock.instant()));
    }
}
