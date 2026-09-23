CREATE TABLE account (
                         id       UUID PRIMARY KEY,
                         name     VARCHAR(100) NOT NULL,
                         type     VARCHAR(100) NOT NULL,
                         currency VARCHAR(3)   NOT NULL
);

CREATE TABLE journal_entry (
                               id              UUID PRIMARY KEY,
                               description     VARCHAR(100) NOT NULL,
                               idempotency_key VARCHAR(255) NOT NULL UNIQUE,
                               currency         VARCHAR(3) NOT NULL,
                                   created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE posting (
                         id               UUID PRIMARY KEY,
                         journal_entry_id UUID   NOT NULL REFERENCES journal_entry(id),
                         account_id       UUID   NOT NULL REFERENCES account(id),
                         amount           BIGINT NOT NULL,
                         created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE FUNCTION check_entry_balanced()
    RETURNS trigger AS $$
DECLARE
entry_sum BIGINT;
BEGIN
SELECT SUM(amount) INTO entry_sum FROM posting WHERE journal_entry_id = NEW.journal_entry_id;

IF entry_sum <> 0 THEN
        RAISE EXCEPTION
            'Journal entry % is not balanced: sum = %',
            NEW.journal_entry_id, entry_sum;
END IF;

RETURN NULL;
END;
$$ LANGUAGE plpgsql;


CREATE CONSTRAINT TRIGGER trg_entry_balanced
    AFTER INSERT ON posting
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    EXECUTE FUNCTION check_entry_balanced();