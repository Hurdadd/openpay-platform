CREATE OR REPLACE FUNCTION check_entry_balanced()
RETURNS trigger AS $$
DECLARE
    target_entry_id UUID;
    entry_sum BIGINT;
    posting_count BIGINT;
BEGIN
    IF TG_TABLE_NAME = 'journal_entry' THEN
        target_entry_id := NEW.id;
    ELSE
        target_entry_id := NEW.journal_entry_id;
    END IF;

    SELECT COALESCE(SUM(amount), 0), COUNT(*)
      INTO entry_sum, posting_count
      FROM posting
     WHERE journal_entry_id = target_entry_id;

    IF posting_count < 2 OR entry_sum <> 0 THEN
        RAISE EXCEPTION
            'Journal entry % must have at least two postings and be balanced: count = %, sum = %',
            target_entry_id, posting_count, entry_sum;
    END IF;

    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_entry_balanced ON posting;

CREATE CONSTRAINT TRIGGER trg_entry_balanced
    AFTER INSERT ON posting
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    EXECUTE FUNCTION check_entry_balanced();

CREATE CONSTRAINT TRIGGER trg_journal_entry_balanced
    AFTER INSERT ON journal_entry
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    EXECUTE FUNCTION check_entry_balanced();
