-- PAY-303: keys are scoped per merchant and expire.
-- Legacy rows have no known merchant: park them under the nil UUID (no real merchant can match) and let them expire.
ALTER TABLE idempotency_keys ADD COLUMN merchant_id UUID;
ALTER TABLE idempotency_keys ADD COLUMN expires_at TIMESTAMPTZ;

UPDATE idempotency_keys
SET merchant_id = '00000000-0000-0000-0000-000000000000',
    expires_at  = created_at + INTERVAL '24 hours';

ALTER TABLE idempotency_keys ALTER COLUMN merchant_id SET NOT NULL;
ALTER TABLE idempotency_keys ALTER COLUMN expires_at SET NOT NULL;

ALTER TABLE idempotency_keys DROP CONSTRAINT idempotency_keys_pkey;
ALTER TABLE idempotency_keys ADD PRIMARY KEY (merchant_id, idempotency_key);

CREATE INDEX idx_idempotency_keys_expires_at ON idempotency_keys (expires_at);
