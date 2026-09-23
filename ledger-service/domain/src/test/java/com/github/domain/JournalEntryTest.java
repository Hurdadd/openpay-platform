package com.github.domain;

import org.junit.jupiter.api.Test;

import java.util.Currency;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JournalEntryTest {

    private static final Currency IRR = Currency.getInstance("IRR");

    @Test
    void balanced_two_postings_construct() {
        var entry = new JournalEntry(
                List.of(new Posting(10000, "a"), new Posting(-10000, "b")),
                "test",
                IRR);
        assertEquals(0, entry.postings().stream().mapToLong(Posting::amount).sum());
    }

    @Test
    void unbalanced_postings_throw_with_sum() {
        var thrown = assertThrows(IllegalArgumentException.class, () ->
                new JournalEntry(
                        List.of(new Posting(10000, "a"), new Posting(-5000, "b")),
                        "test",
                        IRR));
        assertTrue(thrown.getMessage().contains("sum = 5000"));
    }

    @Test
    void single_posting_throws() {
        assertThrows(IllegalArgumentException.class, () ->
                new JournalEntry(List.of(new Posting(0, "a")), "test", IRR));
    }

    @Test
    void empty_postings_throw() {
        assertThrows(IllegalArgumentException.class, () ->
                new JournalEntry(List.of(), "test", IRR));
    }
}
