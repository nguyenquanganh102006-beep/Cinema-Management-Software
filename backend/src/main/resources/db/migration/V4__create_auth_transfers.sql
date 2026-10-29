CREATE TABLE auth_transfers (
    code_hash VARCHAR(64) PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(user_id),
    target_role user_role NOT NULL,
    security_version INTEGER NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_auth_transfer_expiry ON auth_transfers(expires_at);
