package com.github.domain;

import java.util.Currency;
import java.util.List;

public record JournalEntry(
        List<Posting> postings,
        String description,
        Currency currency
) {
    public JournalEntry {
        postings = List.copyOf(postings);

        if (postings.size() < 2) {
            throw new IllegalArgumentException(
                    "A journal entry needs at least 2 postings (double-entry), got " + postings.size());
        }

        long sum = postings.stream()
                .mapToLong(Posting::amount)
                .sum();

        if (sum != 0) {
            throw new IllegalArgumentException(
                    "Journal entry is not balanced: sum = " + sum);
        }
    }
}
