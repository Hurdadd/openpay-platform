CREATE TABLE idempotency_keys(
    idempotency_key VARCHAR(255)  PRIMARY KEY ,
    body_hash CHAR(64) NOT NULL,
    response JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);