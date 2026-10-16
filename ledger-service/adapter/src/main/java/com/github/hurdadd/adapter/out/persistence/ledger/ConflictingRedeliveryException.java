package com.github.hurdadd.adapter.out.persistence.ledger;

/** The same event id arrived again with different content; never a harmless redelivery. */
public class ConflictingRedeliveryException extends RuntimeException {
    public ConflictingRedeliveryException(String idempotencyKey) {
        super("Event " + idempotencyKey + " was already recorded with different postings or currency");
    }
}
