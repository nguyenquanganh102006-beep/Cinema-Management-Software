-- Sprint E booking/payment/ticket persistence foundation.
-- V1-V5 stay untouched; this migration only adds what checkout needs.

ALTER TABLE bookings
    ADD COLUMN showtime_id BIGINT NOT NULL REFERENCES showtimes(showtime_id),
    ADD COLUMN hold_token VARCHAR(100),
    ADD COLUMN hold_owner VARCHAR(100),
    ADD COLUMN ticket_total NUMERIC(12, 2),
    ADD COLUMN concession_total NUMERIC(12, 2),
    ADD COLUMN paid_at TIMESTAMPTZ,
    ADD COLUMN version INT NOT NULL DEFAULT 0,
    ADD CONSTRAINT chk_booking_ticket_total CHECK (ticket_total IS NULL OR ticket_total >= 0),
    ADD CONSTRAINT chk_booking_concession_total CHECK (concession_total IS NULL OR concession_total >= 0),
    ADD CONSTRAINT chk_booking_version CHECK (version >= 0);

CREATE INDEX idx_bookings_user_created ON bookings(user_id, created_at DESC);
CREATE INDEX idx_bookings_channel_status_paid ON bookings(channel, status, paid_at DESC);

-- Planned seats quoted when the Booking is created. Sweetbox stores both physical seats.
CREATE TABLE booking_seats (
    booking_id BIGINT NOT NULL,
    seat_id BIGINT NOT NULL,
    quoted_price NUMERIC(10, 2) NOT NULL,
    PRIMARY KEY (booking_id, seat_id),
    CONSTRAINT fk_booking_seats_booking FOREIGN KEY (booking_id) REFERENCES bookings(booking_id) ON DELETE CASCADE,
    CONSTRAINT fk_booking_seats_seat FOREIGN KEY (seat_id) REFERENCES seats(seat_id),
    CONSTRAINT chk_booking_seat_quoted_price CHECK (quoted_price >= 0)
);
CREATE INDEX idx_booking_seats_seat ON booking_seats(seat_id);

-- Daily counter used to generate BK-yyMMdd-NNNN inside the Booking transaction.
CREATE TABLE booking_code_counters (
    booking_date DATE PRIMARY KEY,
    last_number INT NOT NULL,
    CONSTRAINT chk_booking_code_counter_positive CHECK (last_number > 0)
);

ALTER TABLE payments
    ADD COLUMN vnp_txn_ref VARCHAR(100),
    ADD COLUMN expires_at TIMESTAMPTZ,
    ADD COLUMN confirmed_by_staff_id BIGINT REFERENCES users(user_id),
    ADD COLUMN cash_received NUMERIC(12, 2),
    ADD CONSTRAINT chk_payment_cash_received CHECK (cash_received IS NULL OR cash_received >= amount);

CREATE UNIQUE INDEX uq_payments_vnp_txn_ref ON payments(vnp_txn_ref) WHERE vnp_txn_ref IS NOT NULL;
CREATE INDEX idx_payments_confirmed_by ON payments(confirmed_by_staff_id) WHERE confirmed_by_staff_id IS NOT NULL;

CREATE INDEX idx_tickets_booking ON tickets(booking_id);
