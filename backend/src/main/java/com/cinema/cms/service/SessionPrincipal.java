package com.cinema.cms.service;
import com.cinema.cms.entity.UserRole;
import java.time.Instant;
public record SessionPrincipal(Long userId, String username, UserRole role, String tokenId, Instant expiresAt) {}
