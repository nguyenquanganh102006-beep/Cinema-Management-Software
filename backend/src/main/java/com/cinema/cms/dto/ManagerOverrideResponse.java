package com.cinema.cms.dto;
import java.math.BigDecimal;
import java.time.Instant;
public record ManagerOverrideResponse(String overrideToken, Instant expiresAt, BigDecimal approvedRefundAmount) {
    @Override public String toString() { return "ManagerOverrideResponse[REDACTED]"; }
}
