ALTER TABLE account
    DROP CONSTRAINT uq_account_name_currency;

ALTER TABLE account
    ADD CONSTRAINT uq_account_identity UNIQUE (name, type, currency);
