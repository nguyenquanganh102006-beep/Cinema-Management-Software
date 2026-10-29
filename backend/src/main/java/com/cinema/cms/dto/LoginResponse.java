package com.cinema.cms.dto;
public record LoginResponse(String accessToken, long expiresIn, UserResponse user) {
    @Override public String toString() { return "LoginResponse[token redacted]"; }
}
