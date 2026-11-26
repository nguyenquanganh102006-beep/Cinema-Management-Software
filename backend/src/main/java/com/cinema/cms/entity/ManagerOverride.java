package com.cinema.cms.entity;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
/** Persisted approval, never an authentication credential for a Manager session. */
public record ManagerOverride(String tokenHash, Long staffId, String staffTokenId,
    Long managerId, int managerSecurityVersion, Long bookingId, List<Long> ticketIds,
    String reason, BigDecimal approvedAmount, Instant cashConfirmedAt, Instant expiresAt, Long refundId) {
    public ManagerOverride withCashConfirmedAt(Instant confirmedAt) {
        return new ManagerOverride(tokenHash, staffId, staffTokenId, managerId,
                managerSecurityVersion, bookingId, ticketIds, reason, approvedAmount,
                confirmedAt, expiresAt, refundId);
    }
    @Override public String toString() { return "ManagerOverride[REDACTED]"; }
}
