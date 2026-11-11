-- Preserve the original financial records and add relational protection for each refunded ticket.
ALTER TABLE refund_records ADD COLUMN staff_id BIGINT REFERENCES users(user_id);
CREATE INDEX idx_refund_booking ON refund_records(booking_id, refund_id);
CREATE TABLE refund_ticket_links (
    ticket_id BIGINT PRIMARY KEY REFERENCES tickets(ticket_id),
    refund_id BIGINT NOT NULL REFERENCES refund_records(refund_id),
    price_snapshot NUMERIC(10,2) NOT NULL CHECK (price_snapshot >= 0)
);
-- Fail visibly on invalid or duplicated legacy ticket lists rather than discard audit history.
INSERT INTO refund_ticket_links(ticket_id, refund_id, price_snapshot)
SELECT value::bigint, r.refund_id, t.price_snapshot
FROM refund_records r CROSS JOIN LATERAL jsonb_array_elements_text(r.ticket_ids_json) ids(value)
JOIN tickets t ON t.ticket_id = value::bigint AND t.booking_id = r.booking_id;
DO $$ BEGIN
    IF EXISTS (SELECT 1 FROM refund_records r WHERE
        jsonb_array_length(r.ticket_ids_json) = 0 OR
        jsonb_array_length(r.ticket_ids_json) <> (SELECT count(*) FROM refund_ticket_links l WHERE l.refund_id=r.refund_id) OR
        r.refund_amount <> (SELECT coalesce(sum(l.price_snapshot),0) FROM refund_ticket_links l WHERE l.refund_id=r.refund_id))
    THEN RAISE EXCEPTION 'Legacy refund ticket lists or amounts are inconsistent'; END IF;
END $$;
CREATE TABLE manager_overrides (
    token_hash VARCHAR(64) PRIMARY KEY,
    staff_id BIGINT NOT NULL REFERENCES users(user_id),
    staff_token_id VARCHAR(36) NOT NULL,
    manager_id BIGINT NOT NULL REFERENCES users(user_id),
    manager_security_version INTEGER NOT NULL,
    booking_id BIGINT NOT NULL REFERENCES bookings(booking_id),
    ticket_ids_json JSONB NOT NULL,
    reason VARCHAR(255) NOT NULL,
    approved_amount NUMERIC(12,2) NOT NULL CHECK (approved_amount > 0),
    cash_confirmed_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    refund_id BIGINT UNIQUE REFERENCES refund_records(refund_id),
    consumed_at TIMESTAMPTZ,
    CHECK ((refund_id IS NULL) = (consumed_at IS NULL))
);
CREATE INDEX idx_overrides_expiry ON manager_overrides(expires_at);
CREATE INDEX idx_payments_paid_time ON payments(paid_at, booking_id) WHERE status='PAID';
