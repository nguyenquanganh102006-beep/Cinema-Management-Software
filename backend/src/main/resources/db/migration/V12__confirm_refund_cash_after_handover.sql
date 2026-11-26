-- A Manager approves the refund first, then confirms cash handover before the refund transaction.
-- Existing unconsumed overrides captured approval-time cash flags; they were never proof of handover.
UPDATE manager_overrides
SET cash_confirmed_at = NULL
WHERE refund_id IS NULL;

ALTER TABLE manager_overrides
    ALTER COLUMN cash_confirmed_at DROP NOT NULL;
