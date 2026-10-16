-- the reconciler measures "stuck" from the last status change, not from creation:
-- a payment AUTHORIZED yesterday and untouched since is stuck even though created_at is old for every row
ALTER TABLE payments ADD COLUMN status_changed_at TIMESTAMPTZ;

-- best available history for existing rows
UPDATE payments SET status_changed_at = created_at;

ALTER TABLE payments ALTER COLUMN status_changed_at SET NOT NULL;

CREATE INDEX idx_payments_status_changed_at ON payments (status, status_changed_at);
