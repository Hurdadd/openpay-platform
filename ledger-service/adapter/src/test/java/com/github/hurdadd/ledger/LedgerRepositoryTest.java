package com.github.hurdadd.ledger;

import com.github.domain.JournalEntry;
import com.github.domain.Posting;
import com.github.hurdadd.adapter.out.persistence.ledger.ConflictingRedeliveryException;
import com.github.hurdadd.adapter.out.persistence.ledger.JdbcLedgerRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.TransactionSystemException;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.Currency;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LedgerRepositoryTest {

    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    static JdbcTemplate jdbc;
    static JdbcLedgerRepository repository;

    @BeforeAll
    static void start() {
        postgres.start();
        jdbc = LedgerTestSupport.migratedJdbcTemplate(postgres);
        repository = new JdbcLedgerRepository(jdbc);
    }

    @AfterAll
    static void stop() {
        postgres.stop();
    }

    @BeforeEach
    void clean() {
        LedgerTestSupport.truncateAll(jdbc);
    }

    @Test
    void same_key_saved_five_times_keeps_one_entry() {
        var clearing = repository.ensureAccount("psp_clearing", "CLEARING", "IRR");
        var merchant = repository.ensureAccount("merchant:m-1", "MERCHANT_PAYABLE", "IRR");
        var entry = new JournalEntry(
                List.of(new Posting(10000, clearing.toString()), new Posting(-10000, merchant.toString())),
                "Settlement test",
                Currency.getInstance("IRR"));

        for (int i = 0; i < 5; i++) {
            LedgerTestSupport.inTransaction(jdbc, () -> repository.save(entry, "evt-1"));
        }

        assertEquals(1, LedgerTestSupport.count(jdbc, "journal_entry"));
        assertEquals(2, LedgerTestSupport.count(jdbc, "posting"));
        assertEquals(10000, LedgerTestSupport.balanceOf(jdbc, clearing));
        assertEquals(-10000, LedgerTestSupport.balanceOf(jdbc, merchant));
    }

    @Test
    void same_key_with_a_different_amount_is_rejected_not_silently_dropped() {
        var clearing = repository.ensureAccount("psp_clearing", "CLEARING", "IRR");
        var merchant = repository.ensureAccount("merchant:m-1", "MERCHANT_PAYABLE", "IRR");
        var original = new JournalEntry(
                List.of(new Posting(10000, clearing.toString()), new Posting(-10000, merchant.toString())),
                "Settlement test", Currency.getInstance("IRR"));
        var tampered = new JournalEntry(
                List.of(new Posting(99999, clearing.toString()), new Posting(-99999, merchant.toString())),
                "Settlement test", Currency.getInstance("IRR"));

        LedgerTestSupport.inTransaction(jdbc, () -> repository.save(original, "evt-1"));

        assertThrows(ConflictingRedeliveryException.class,
                () -> LedgerTestSupport.inTransaction(jdbc, () -> repository.save(tampered, "evt-1")));
        assertEquals(10000, LedgerTestSupport.balanceOf(jdbc, clearing));
    }

    @Test
    void account_identity_matches_database_uniqueness() {
        var payable = repository.ensureAccount("merchant:m-1", "MERCHANT_PAYABLE", "IRR");
        var payableAgain = repository.ensureAccount("merchant:m-1", "MERCHANT_PAYABLE", "IRR");
        var clearing = repository.ensureAccount("merchant:m-1", "CLEARING", "IRR");

        assertEquals(payable, payableAgain);
        assertNotEquals(payable, clearing);
        assertEquals(2, LedgerTestSupport.count(jdbc, "account"));
    }

    @Test
    void unbalanced_postings_cannot_persist() {
        var clearing = repository.ensureAccount("psp_clearing", "CLEARING", "IRR");
        var entryId = UUID.randomUUID();

        var thrown = assertThrows(TransactionSystemException.class, () ->
                LedgerTestSupport.inTransaction(jdbc, () -> {
                    jdbc.update(
                            "INSERT INTO journal_entry (id, description, idempotency_key, currency) VALUES (?, ?, ?, ?)",
                            entryId, "raw", "raw-1", "IRR");
                    jdbc.update(
                            "INSERT INTO posting (id, journal_entry_id, account_id, amount) VALUES (?, ?, ?, ?)",
                            UUID.randomUUID(), entryId, clearing, 10000);
                })
        );

        assertTrue(thrown.getRootCause().getMessage().contains("at least two postings")
                || thrown.getRootCause().getMessage().contains("balanced"));
    }

    @Test
    void journal_entry_without_postings_cannot_commit() {
        var entryId = UUID.randomUUID();

        assertThrows(TransactionSystemException.class, () ->
                LedgerTestSupport.inTransaction(jdbc, () ->
                        jdbc.update(
                                "INSERT INTO journal_entry (id, description, idempotency_key, currency) VALUES (?, ?, ?, ?)",
                                entryId, "empty", "empty-1", "IRR"))
        );
    }

    @Test
    void single_zero_posting_is_not_valid_double_entry() {
        var clearing = repository.ensureAccount("psp_clearing", "CLEARING", "IRR");
        var entryId = UUID.randomUUID();

        assertThrows(TransactionSystemException.class, () ->
                LedgerTestSupport.inTransaction(jdbc, () -> {
                    jdbc.update(
                            "INSERT INTO journal_entry (id, description, idempotency_key, currency) VALUES (?, ?, ?, ?)",
                            entryId, "zero", "zero-1", "IRR");
                    jdbc.update(
                            "INSERT INTO posting (id, journal_entry_id, account_id, amount) VALUES (?, ?, ?, ?)",
                            UUID.randomUUID(), entryId, clearing, 0);
                })
        );
    }

    @Test
    void tables_reject_update_and_delete() {
        var clearing = repository.ensureAccount("psp_clearing", "CLEARING", "IRR");
        var merchant = repository.ensureAccount("merchant:m-1", "MERCHANT_PAYABLE", "IRR");
        var entry = new JournalEntry(
                List.of(new Posting(5000, clearing.toString()), new Posting(-5000, merchant.toString())),
                "Settlement test",
                Currency.getInstance("IRR"));
        LedgerTestSupport.inTransaction(jdbc, () -> repository.save(entry, "evt-guarded"));

        var updateThrown = assertThrows(DataAccessException.class, () ->
                jdbc.update("UPDATE posting SET amount = 1 WHERE amount = 5000"));
        assertTrue(updateThrown.getMessage().contains("append-only"));

        var deleteThrown = assertThrows(DataAccessException.class, () ->
                jdbc.execute("DELETE FROM posting"));
        assertTrue(deleteThrown.getMessage().contains("append-only"));
    }
}
