package com.github.hurdadd.application.port.out;

import com.github.domain.JournalEntry;

import java.util.UUID;

public interface LedgerRepository {

    void save(JournalEntry entry, String idempotencyKey);

    UUID ensureAccount(String name, String type, String currency);
}
