package com.cinema.cms.entity;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "audit_events")
public class AuditEvent {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "audit_event_id")
    private Long id;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "actor_user_id")
    private Long actorUserId;

    @Column(nullable = false, length = 64)
    private String action;

    @Column(nullable = false, length = 64)
    private String outcome;

    @Column(name = "subject_type", nullable = false, length = 64)
    private String subjectType;

    @Column(name = "subject_id", length = 128)
    private String subjectId;

    @Column(columnDefinition = "text")
    private String details;

    protected AuditEvent() {}

    public AuditEvent(Instant occurredAt, Long actorUserId, String action, String outcome,
                      String subjectType, String subjectId, String details) {
        this.occurredAt = occurredAt;
        this.actorUserId = actorUserId;
        this.action = action;
        this.outcome = outcome;
        this.subjectType = subjectType;
        this.subjectId = subjectId;
        this.details = details;
    }

    public Long getId() { return id; }
    public Instant getOccurredAt() { return occurredAt; }
    public Long getActorUserId() { return actorUserId; }
    public String getAction() { return action; }
    public String getOutcome() { return outcome; }
    public String getSubjectType() { return subjectType; }
    public String getSubjectId() { return subjectId; }
    public String getDetails() { return details; }
}
