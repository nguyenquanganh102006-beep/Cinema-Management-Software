CREATE TABLE audit_events (
    audit_event_id BIGSERIAL PRIMARY KEY,
    occurred_at TIMESTAMPTZ NOT NULL,
    actor_user_id BIGINT REFERENCES users(user_id) ON DELETE SET NULL,
    action VARCHAR(64) NOT NULL,
    outcome VARCHAR(24) NOT NULL,
    subject_type VARCHAR(64) NOT NULL,
    subject_id VARCHAR(128),
    details TEXT
);

CREATE INDEX idx_audit_events_occurred ON audit_events(occurred_at DESC, audit_event_id DESC);
CREATE INDEX idx_audit_events_actor ON audit_events(actor_user_id, occurred_at DESC);
