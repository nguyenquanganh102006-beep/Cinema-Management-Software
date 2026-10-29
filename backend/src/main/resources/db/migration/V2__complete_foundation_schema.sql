ALTER TABLE users
    ADD COLUMN manager_pin_hash VARCHAR(255),
    ADD COLUMN security_version INTEGER NOT NULL DEFAULT 0 CHECK (security_version >= 0),
    ADD COLUMN updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ALTER COLUMN created_at SET NOT NULL,
    ADD CONSTRAINT chk_user_username_normalized CHECK (username = lower(btrim(username)) AND username ~ '^[a-z0-9._-]{3,50}$'),
    ADD CONSTRAINT chk_user_email_normalized CHECK (email = lower(btrim(email))),
    ADD CONSTRAINT chk_manager_pin_role CHECK (manager_pin_hash IS NULL OR role = 'MANAGER');

CREATE TABLE revoked_access_tokens (
    token_id VARCHAR(36) PRIMARY KEY,
    expires_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_revoked_token_expiry ON revoked_access_tokens(expires_at);

ALTER TABLE movies
    ADD COLUMN description TEXT,
    ADD COLUMN genre VARCHAR(150),
    ADD COLUMN country VARCHAR(100),
    ADD COLUMN language VARCHAR(100),
    ADD COLUMN director VARCHAR(150),
    ADD COLUMN poster VARCHAR(2048),
    ADD COLUMN trailer VARCHAR(2048),
    ADD CONSTRAINT chk_movie_duration CHECK (duration_minutes > 0);
ALTER TABLE auditoriums ADD COLUMN is_active BOOLEAN NOT NULL DEFAULT TRUE,
    ADD CONSTRAINT chk_auditorium_seats CHECK (total_seats > 0);
ALTER TABLE seats ADD CONSTRAINT chk_seat_number CHECK (seat_number > 0),
    ADD CONSTRAINT chk_seat_pair_self CHECK (pair_seat_id IS NULL OR pair_seat_id <> seat_id);
CREATE UNIQUE INDEX uq_seat_pair_target ON seats(pair_seat_id) WHERE pair_seat_id IS NOT NULL;
ALTER TABLE pricing_rules ADD CONSTRAINT chk_pricing_amount CHECK (amount >= 0);
ALTER TABLE showtimes ADD CONSTRAINT chk_showtime_price CHECK (base_price >= 0);
CREATE INDEX idx_showtimes_auditorium_time ON showtimes(auditorium_id, start_time, end_time);
ALTER TABLE concession_items ADD CONSTRAINT chk_item_price CHECK (price >= 0);
ALTER TABLE combo_items ADD CONSTRAINT chk_combo_not_self CHECK (combo_id <> component_item_id);
ALTER TABLE booking_concessions ADD CONSTRAINT chk_concession_snapshot CHECK (unit_price_snapshot >= 0);
ALTER TABLE tickets ADD CONSTRAINT chk_ticket_snapshot CHECK (price_snapshot >= 0);

CREATE TABLE inventory_transactions (
    movement_id BIGSERIAL PRIMARY KEY,
    item_id INT NOT NULL REFERENCES concession_items(item_id),
    movement_type VARCHAR(20) NOT NULL CHECK (movement_type IN ('RESTOCK', 'SALE', 'ADJUSTMENT')),
    quantity INT NOT NULL CHECK (quantity <> 0),
    before_quantity INT NOT NULL CHECK (before_quantity >= 0),
    after_quantity INT NOT NULL CHECK (after_quantity >= 0),
    manager_id BIGINT REFERENCES users(user_id),
    note VARCHAR(500),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_stock_movement_balance CHECK (after_quantity = before_quantity + quantity),
    CONSTRAINT chk_restock_manager CHECK (movement_type <> 'RESTOCK' OR (quantity > 0 AND manager_id IS NOT NULL))
);
CREATE INDEX idx_inventory_type_created ON inventory_transactions(movement_type, created_at DESC, movement_id DESC);
CREATE INDEX idx_inventory_item_created ON inventory_transactions(item_id, created_at DESC);
CREATE INDEX idx_inventory_manager_created ON inventory_transactions(manager_id, created_at DESC);

-- Online declaration belongs to the Booking; POS/Gate checks are separate audit events.
ALTER TABLE bookings ADD COLUMN age_confirmed_at TIMESTAMPTZ;
CREATE TABLE age_verifications (
    verification_id BIGSERIAL PRIMARY KEY,
    booking_id BIGINT REFERENCES bookings(booking_id),
    ticket_id BIGINT REFERENCES tickets(ticket_id),
    verification_stage VARCHAR(10) NOT NULL CHECK (verification_stage IN ('POS', 'GATE')),
    verified_by_staff_id BIGINT NOT NULL REFERENCES users(user_id),
    verified_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_age_verification_target CHECK (
        (verification_stage = 'POS' AND booking_id IS NOT NULL AND ticket_id IS NULL) OR
        (verification_stage = 'GATE' AND ticket_id IS NOT NULL AND booking_id IS NULL))
);
CREATE INDEX idx_age_booking ON age_verifications(booking_id);
CREATE INDEX idx_age_ticket ON age_verifications(ticket_id);
