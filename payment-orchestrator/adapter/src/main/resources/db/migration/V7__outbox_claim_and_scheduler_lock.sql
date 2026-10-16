-- Outbox: a publisher claims rows in a short transaction, sends to Kafka outside any transaction,
-- then marks them published. A claim older than the lease is considered abandoned (publisher crashed).
ALTER TABLE payment_outbox ADD COLUMN claimed_at TIMESTAMPTZ NULL;

-- Minimal cluster-wide lock for scheduled jobs (the reconciler): one row per job name.
CREATE TABLE scheduler_lock (
    name         VARCHAR(100) PRIMARY KEY,
    locked_until TIMESTAMPTZ  NOT NULL,
    locked_by    VARCHAR(100) NOT NULL
);
