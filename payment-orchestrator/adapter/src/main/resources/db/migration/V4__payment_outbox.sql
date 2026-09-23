CREATE TABLE payment_outbox (
                                id UUID PRIMARY KEY,
                                payment_id UUID NOT NULL,
                                event_type VARCHAR(100) NOT NULL,
                                payload JSONB NOT NULL,
                                published BOOLEAN NOT NULL DEFAULT FALSE,
                                created_at TIMESTAMPTZ NOT NULL,
                                published_at TIMESTAMPTZ NULL
);

CREATE INDEX idx_outbox_unpublished
    ON payment_outbox (created_at)
    WHERE published = FALSE;