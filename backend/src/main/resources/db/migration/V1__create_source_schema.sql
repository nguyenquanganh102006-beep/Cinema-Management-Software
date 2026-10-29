-- Adapted from G3/script_cms.sql; no destructive schema reset or transaction demo seed.
CREATE TYPE user_role AS ENUM ('CUSTOMER', 'STAFF', 'MANAGER');
CREATE TYPE account_status AS ENUM ('ACTIVE', 'INACTIVE', 'LOCKED');
CREATE TYPE auditorium_type_enum AS ENUM ('STANDARD_2D', '3D', 'IMAX');
CREATE TYPE seat_type_enum AS ENUM ('STANDARD', 'VIP', 'SWEETBOX');
CREATE TYPE age_rating_enum AS ENUM ('P', 'T13', 'T16', 'T18');
CREATE TYPE pricing_rule_type_enum AS ENUM ('BASE_PRICE', 'ROOM_SURCHARGE', 'SEAT_SURCHARGE', 'TIME_SURCHARGE');
CREATE TYPE seat_status_enum AS ENUM ('AVAILABLE', 'HOLDING', 'BOOKED');
CREATE TYPE booking_channel_enum AS ENUM ('ONLINE', 'POS');
CREATE TYPE booking_status_enum AS ENUM ('PENDING', 'PAID', 'CANCELLED', 'EXCEPTION_PENDING_DECISION');
CREATE TYPE ticket_status_enum AS ENUM ('VALID', 'USED', 'CANCELLED');
CREATE TYPE payment_method_enum AS ENUM ('CASH', 'VIETQR', 'GATEWAY_ONLINE');
CREATE TYPE payment_status_enum AS ENUM ('PENDING', 'PAID', 'FAILED');

CREATE TABLE users (
    user_id BIGSERIAL PRIMARY KEY,
    username VARCHAR(50) NOT NULL UNIQUE,
    full_name VARCHAR(100) NOT NULL,
    email VARCHAR(150) NOT NULL UNIQUE,
    phone VARCHAR(20) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    role user_role NOT NULL DEFAULT 'CUSTOMER',
    status account_status NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE auditoriums (
    auditorium_id SERIAL PRIMARY KEY,
    name VARCHAR(50) NOT NULL UNIQUE,
    total_seats INT NOT NULL,
    auditorium_type auditorium_type_enum NOT NULL DEFAULT 'STANDARD_2D'
);

CREATE TABLE seats (
    seat_id BIGSERIAL PRIMARY KEY,
    auditorium_id INT NOT NULL,
    seat_row VARCHAR(5) NOT NULL,
    seat_number INT NOT NULL,
    seat_type seat_type_enum NOT NULL DEFAULT 'STANDARD',
    pair_seat_id BIGINT NULL,
    CONSTRAINT fk_seats_auditorium FOREIGN KEY (auditorium_id) REFERENCES auditoriums(auditorium_id) ON DELETE CASCADE,
    CONSTRAINT fk_seats_pair FOREIGN KEY (pair_seat_id) REFERENCES seats(seat_id) ON DELETE SET NULL,
    CONSTRAINT uq_auditorium_seat UNIQUE (auditorium_id, seat_row, seat_number)
);

CREATE TABLE movies (
    movie_id BIGSERIAL PRIMARY KEY,
    title VARCHAR(200) NOT NULL,
    duration_minutes INT NOT NULL,
    age_rating age_rating_enum NOT NULL DEFAULT 'P',
    release_date DATE NOT NULL,
    is_active BOOLEAN NOT NULL DEFAULT TRUE
);

CREATE TABLE pricing_rules (
    rule_id SERIAL PRIMARY KEY,
    rule_code VARCHAR(50) NOT NULL UNIQUE,
    rule_type pricing_rule_type_enum NOT NULL,
    target_ref VARCHAR(50) NOT NULL,
    amount NUMERIC(10, 2) NOT NULL,
    description VARCHAR(255) NULL
);

CREATE TABLE showtimes (
    showtime_id BIGSERIAL PRIMARY KEY,
    movie_id BIGINT NOT NULL,
    auditorium_id INT NOT NULL,
    start_time TIMESTAMPTZ NOT NULL,
    end_time TIMESTAMPTZ NOT NULL,
    base_price NUMERIC(10, 2) NOT NULL,
    CONSTRAINT fk_showtimes_movie FOREIGN KEY (movie_id) REFERENCES movies(movie_id),
    CONSTRAINT fk_showtimes_auditorium FOREIGN KEY (auditorium_id) REFERENCES auditoriums(auditorium_id),
    CONSTRAINT chk_showtime_duration CHECK (end_time > start_time)
);

CREATE TABLE showtime_seats (
    showtime_seat_id BIGSERIAL PRIMARY KEY,
    showtime_id BIGINT NOT NULL,
    seat_id BIGINT NOT NULL,
    status seat_status_enum NOT NULL DEFAULT 'AVAILABLE',
    owner_id VARCHAR(100) NULL,
    hold_token VARCHAR(100) NULL,
    hold_until TIMESTAMPTZ NULL,
    extension_used BOOLEAN NOT NULL DEFAULT FALSE,
    version INT NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_ss_showtime FOREIGN KEY (showtime_id) REFERENCES showtimes(showtime_id) ON DELETE CASCADE,
    CONSTRAINT fk_ss_seat FOREIGN KEY (seat_id) REFERENCES seats(seat_id),
    CONSTRAINT uq_showtime_seat UNIQUE (showtime_id, seat_id)
);

CREATE TABLE concession_items (
    item_id SERIAL PRIMARY KEY,
    name VARCHAR(100) NOT NULL UNIQUE,
    price NUMERIC(10, 2) NOT NULL,
    is_combo BOOLEAN NOT NULL DEFAULT FALSE,
    is_active BOOLEAN NOT NULL DEFAULT TRUE
);

CREATE TABLE combo_items (
    combo_id INT NOT NULL,
    component_item_id INT NOT NULL,
    quantity INT NOT NULL DEFAULT 1,
    PRIMARY KEY (combo_id, component_item_id),
    CONSTRAINT fk_combo_parent FOREIGN KEY (combo_id) REFERENCES concession_items(item_id) ON DELETE CASCADE,
    CONSTRAINT fk_combo_component FOREIGN KEY (component_item_id) REFERENCES concession_items(item_id),
    CONSTRAINT chk_combo_quantity CHECK (quantity > 0)
);

CREATE TABLE concession_inventory (
    item_id INT PRIMARY KEY,
    stock_quantity INT NOT NULL DEFAULT 0,
    low_stock_threshold INT NOT NULL DEFAULT 10,
    CONSTRAINT fk_inv_item FOREIGN KEY (item_id) REFERENCES concession_items(item_id),
    CONSTRAINT chk_stock_non_negative CHECK (stock_quantity >= 0),
    CONSTRAINT chk_threshold_non_negative CHECK (low_stock_threshold >= 0)
);

CREATE TABLE bookings (
    booking_id BIGSERIAL PRIMARY KEY,
    booking_code VARCHAR(30) NOT NULL UNIQUE,
    user_id BIGINT NULL,
    staff_id BIGINT NULL,
    channel booking_channel_enum NOT NULL,
    total_amount NUMERIC(12, 2) NOT NULL,
    status booking_status_enum NOT NULL DEFAULT 'PENDING',
    created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_bookings_user FOREIGN KEY (user_id) REFERENCES users(user_id),
    CONSTRAINT fk_bookings_staff FOREIGN KEY (staff_id) REFERENCES users(user_id),
    CONSTRAINT chk_booking_total CHECK (total_amount >= 0)
);

CREATE TABLE booking_concessions (
    booking_id BIGINT NOT NULL,
    item_id INT NOT NULL,
    quantity INT NOT NULL,
    unit_price_snapshot NUMERIC(10, 2) NOT NULL,
    PRIMARY KEY (booking_id, item_id),
    CONSTRAINT fk_bc_booking FOREIGN KEY (booking_id) REFERENCES bookings(booking_id) ON DELETE CASCADE,
    CONSTRAINT fk_bc_item FOREIGN KEY (item_id) REFERENCES concession_items(item_id),
    CONSTRAINT chk_bc_quantity CHECK (quantity > 0)
);

CREATE TABLE tickets (
    ticket_id BIGSERIAL PRIMARY KEY,
    ticket_code VARCHAR(50) NOT NULL UNIQUE,
    booking_id BIGINT NOT NULL,
    showtime_seat_id BIGINT NOT NULL,
    price_snapshot NUMERIC(10, 2) NOT NULL,
    status ticket_status_enum NOT NULL DEFAULT 'VALID',
    issued_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_tickets_booking FOREIGN KEY (booking_id) REFERENCES bookings(booking_id),
    CONSTRAINT fk_tickets_showtime_seat FOREIGN KEY (showtime_seat_id) REFERENCES showtime_seats(showtime_seat_id)
);

CREATE TABLE payments (
    payment_id BIGSERIAL PRIMARY KEY,
    booking_id BIGINT NOT NULL UNIQUE,
    payment_method payment_method_enum NOT NULL,
    amount NUMERIC(12, 2) NOT NULL,
    status payment_status_enum NOT NULL DEFAULT 'PENDING',
    gateway_transaction_code VARCHAR(100) NULL UNIQUE,
    idempotency_key VARCHAR(100) NULL UNIQUE,
    webhook_signature VARCHAR(255) NULL,
    paid_at TIMESTAMPTZ NULL,
    CONSTRAINT fk_payments_booking FOREIGN KEY (booking_id) REFERENCES bookings(booking_id),
    CONSTRAINT chk_payment_amount CHECK (amount > 0)
);

CREATE TABLE refund_records (
    refund_id BIGSERIAL PRIMARY KEY,
    booking_id BIGINT NOT NULL,
    ticket_ids_json JSONB NOT NULL,
    manager_id BIGINT NOT NULL,
    refund_amount NUMERIC(12, 2) NOT NULL,
    reason VARCHAR(255) NOT NULL,
    cash_confirmed_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_refund_booking FOREIGN KEY (booking_id) REFERENCES bookings(booking_id),
    CONSTRAINT fk_refund_manager FOREIGN KEY (manager_id) REFERENCES users(user_id),
    CONSTRAINT chk_refund_amount CHECK (refund_amount > 0)
);

CREATE INDEX idx_users_login ON users(username, status);
CREATE INDEX idx_users_email ON users(email, status);
CREATE INDEX idx_showtime_seats_query ON showtime_seats(showtime_id, status);
CREATE INDEX idx_showtime_seats_hold ON showtime_seats(status, hold_until);
CREATE INDEX idx_showtimes_schedule ON showtimes(movie_id, start_time);
CREATE INDEX idx_pricing_lookup ON pricing_rules(rule_type, target_ref);
CREATE INDEX idx_bookings_lookup ON bookings(booking_code, channel, status);
CREATE INDEX idx_payments_idempotency ON payments(idempotency_key, status);
CREATE INDEX idx_tickets_code_status ON tickets(ticket_code, status);
CREATE UNIQUE INDEX uq_active_ticket_showtime_seat ON tickets(showtime_seat_id) WHERE status IN ('VALID', 'USED');

