-- Sprint E ticket gate check: record when a ticket was scanned and marked USED.
-- V1-V6 stay untouched.
ALTER TABLE tickets
    ADD COLUMN used_at TIMESTAMPTZ;
