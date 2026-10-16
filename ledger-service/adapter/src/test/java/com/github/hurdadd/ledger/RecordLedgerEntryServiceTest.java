package com.github.hurdadd.ledger;

import com.github.hurdadd.adapter.out.persistence.ledger.JdbcLedgerRepository;
import com.github.hurdadd.application.port.in.RecordLedgerEntryCommand;
import com.github.hurdadd.application.service.RecordLedgerEntryService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.Currency;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RecordLedgerEntryServiceTest {

    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    static JdbcTemplate jdbc;
    static RecordLedgerEntryService service;

    static final UUID PAYMENT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    static final UUID MERCHANT = UUID.fromString("22222222-2222-2222-2222-222222222222");
    static final UUID EVENT_A = UUID.fromString("33333333-3333-3333-3333-333333333333");
    static final UUID EVENT_B = UUID.fromString("44444444-4444-4444-4444-444444444444");

    @BeforeAll
    static void start() {
        postgres.start();
        jdbc = LedgerTestSupport.migratedJdbcTemplate(postgres);
        service = new RecordLedgerEntryService(new JdbcLedgerRepository(jdbc), LedgerTestSupport.txRunner(jdbc));
    }

    @AfterAll
    static void stop() {
        postgres.stop();
    }

    @BeforeEach
    void clean() {
        LedgerTestSupport.truncateAll(jdbc);
    }

    private static RecordLedgerEntryCommand command(UUID eventId, long amountMinor) {
        return new RecordLedgerEntryCommand(eventId, PAYMENT, MERCHANT, Currency.getInstance("IRR"), amountMinor);
    }

    @Test
    void redelivery_of_same_event_posts_only_once_but_new_event_posts_again() {
        service.record(command(EVENT_A, 10000));
        service.record(command(EVENT_A, 10000));

        assertEquals(1, LedgerTestSupport.count(jdbc, "journal_entry"));
        assertEquals(2, LedgerTestSupport.count(jdbc, "posting"));

        service.record(command(EVENT_B, 10000));

        assertEquals(2, LedgerTestSupport.count(jdbc, "journal_entry"));
        assertEquals(4, LedgerTestSupport.count(jdbc, "posting"));

        var balances = LedgerTestSupport.balancesByName(jdbc);
        assertEquals(20000, balances.get("psp_clearing"));
        assertEquals(-20000, balances.get("merchant:" + MERCHANT));
    }

    @Test
    void same_event_delivered_five_times_makes_one_entry_and_balance_does_not_move() {
        for (int i = 0; i < 5; i++) {
            service.record(command(EVENT_A, 10000));
        }

        assertEquals(1, LedgerTestSupport.count(jdbc, "journal_entry"));
        assertEquals(2, LedgerTestSupport.count(jdbc, "posting"));
        var balances = LedgerTestSupport.balancesByName(jdbc);
        assertEquals(10000, balances.get("psp_clearing"));
        assertEquals(-10000, balances.get("merchant:" + MERCHANT));
    }

    @Test
    void non_positive_amount_is_rejected_and_nothing_is_written() {
        assertThrows(IllegalArgumentException.class, () -> service.record(command(EVENT_A, 0)));
        assertEquals(0, LedgerTestSupport.count(jdbc, "journal_entry"));
    }
}
