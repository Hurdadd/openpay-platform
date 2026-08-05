CREATE TABLE payments(
    payment_id UUID PRIMARY KEY,
    merchant_id UUID NOT NULL,
    -- NUMERIC(19,4): 4 decimal places covers all ISO-4217 minor units (incl. 3-digit currencies like KWD); avoids rounding money away
    amount NUMERIC(19, 4) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    status VARCHAR(20) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL ,
    version BIGINT NOT NULL

);