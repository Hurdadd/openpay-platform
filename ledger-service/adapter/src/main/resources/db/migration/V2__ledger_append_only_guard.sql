ALTER TABLE account ADD CONSTRAINT uq_account_name_currency UNIQUE (name, currency);

CREATE OR REPLACE FUNCTION reject_ledger_mutation()
RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'ledger is append-only: % on % is forbidden', TG_OP, TG_TABLE_NAME;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_account_no_mutation
    BEFORE UPDATE OR DELETE ON account
    FOR EACH ROW
    EXECUTE FUNCTION reject_ledger_mutation();

CREATE TRIGGER trg_journal_entry_no_mutation
    BEFORE UPDATE OR DELETE ON journal_entry
    FOR EACH ROW
    EXECUTE FUNCTION reject_ledger_mutation();

CREATE TRIGGER trg_posting_no_mutation
    BEFORE UPDATE OR DELETE ON posting
    FOR EACH ROW
    EXECUTE FUNCTION reject_ledger_mutation();
