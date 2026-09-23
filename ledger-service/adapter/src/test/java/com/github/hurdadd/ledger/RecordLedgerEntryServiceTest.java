package com.github.hurdadd.ledger;

import com.github.hurdadd.adapter.application.RecordLedgerEntryService;
import com.github.hurdadd.adapter.in.messaging.exception.UnprocessableMessageException;
import com.github.hurdadd.adapter.out.persistence.ledger.JdbcLedgerRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RecordLedgerEntryServiceTest {

    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    static JdbcTemplate jdbc;
    static RecordLedgerEntryService service;

    static final String PAYMENT = "11111111-1111-1111-1111-111111111111";
    static final String MERCHANT = "22222222-2222-2222-2222-222222222222";
    static final String EVENT_A = "33333333-3333-3333-3333-333333333333";
    static final String EVENT_B = "44444444-4444-4444-4444-444444444444";

    @BeforeAll
    static void start() {
        postgres.start();
        jdbc = LedgerTestSupport.migratedJdbcTemplate(postgres);
        service = new RecordLedgerEntryService(new JdbcLedgerRepository(jdbc), new ObjectMapper());
    }

    @AfterAll
    static void stop() {
        postgres.stop();
    }

    @BeforeEach
    void clean() {
        LedgerTestSupport.truncateAll(jdbc);
    }

    private static String envelope(String eventId, String paymentId, String eventType) {
        return """
                {"eventId":"%s","eventType":"%s","data":{"paymentId":"%s","merchantId":"%s","amountMinor":10000,"currency":"IRR","settledAt":"2026-09-21T10:00:00Z"}}"""
                .formatted(eventId, eventType, paymentId, MERCHANT);
    }

    @Test
    void redelivery_of_same_event_posts_only_once_but_new_event_posts_again() {
        LedgerTestSupport.inTransaction(jdbc, () -> service.record(envelope(EVENT_A, PAYMENT, "PaymentSettled.v1")));
        LedgerTestSupport.inTransaction(jdbc, () -> service.record(envelope(EVENT_A, PAYMENT, "PaymentSettled.v1")));

        assertEquals(1, LedgerTestSupport.count(jdbc, "journal_entry"));
        assertEquals(2, LedgerTestSupport.count(jdbc, "posting"));

        LedgerTestSupport.inTransaction(jdbc, () -> service.record(envelope(EVENT_B, PAYMENT, "PaymentSettled.v1")));

        assertEquals(2, LedgerTestSupport.count(jdbc, "journal_entry"));
        assertEquals(4, LedgerTestSupport.count(jdbc, "posting"));

        var balances = LedgerTestSupport.balancesByName(jdbc);
        assertEquals(20000, balances.get("psp_clearing"));
        assertEquals(-20000, balances.get("merchant:" + MERCHANT));
    }

    @Test
    void malformed_json_throws_unprocessable() {
        assertThrows(UnprocessableMessageException.class, () -> service.record("not-json{"));
    }

    @Test
    void wrong_event_type_throws_unprocessable() {
        assertThrows(UnprocessableMessageException.class,
                () -> service.record(envelope(EVENT_A, PAYMENT, "PaymentFailed.v1")));
    }

    @Test
    void missing_event_id_throws_unprocessable() {
        var noId = """
                {"eventType":"PaymentSettled.v1","data":{"paymentId":"%s","merchantId":"%s","amountMinor":10000,"currency":"IRR","settledAt":"2026-09-21T10:00:00Z"}}"""
                .formatted(PAYMENT, MERCHANT);
        assertThrows(UnprocessableMessageException.class, () -> service.record(noId));
    }
}
