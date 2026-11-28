package com.cinema.cms.dto;

import com.cinema.cms.entity.AuditEvent;
import java.time.Instant;

public record AuditEventResponse(
        Long id,
        Instant occurredAt,
        Long actorUserId,
        String action,
        String outcome,
        String subjectType,
        String subjectId,
        String details) {
    public static AuditEventResponse from(AuditEvent event) {
        return new AuditEventResponse(
                event.getId(), event.getOccurredAt(), event.getActorUserId(), event.getAction(),
                event.getOutcome(), event.getSubjectType(), event.getSubjectId(), event.getDetails());
    }
}
