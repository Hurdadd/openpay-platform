package com.github.hurdadd.adapter.out.persistence.ledger;

import com.github.domain.JournalEntry;
import com.github.hurdadd.adapter.application.port.out.LedgerRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

@Repository
public class JdbcLedgerRepository implements LedgerRepository {

    private final JdbcTemplate jdbcTemplate;

    public JdbcLedgerRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void save(JournalEntry entry, String idempotencyKey) {
        UUID entryId = UUID.randomUUID();

        int inserted = jdbcTemplate.update(
                "INSERT INTO journal_entry (id, description, idempotency_key, currency) VALUES (?, ?, ?, ?) ON CONFLICT (idempotency_key) DO NOTHING",
                entryId,
                entry.description(),
                idempotencyKey,
                entry.currency().getCurrencyCode());

        if (inserted == 0) {
            return;
        }

        for (var posting : entry.postings()) {
            jdbcTemplate.update(
                    "INSERT INTO posting (id, journal_entry_id, account_id, amount) VALUES (?, ?, ?, ?)",
                    UUID.randomUUID(),
                    entryId,
                    UUID.fromString(posting.accountId()),
                    posting.amount());
        }
    }

    @Override
    public UUID ensureAccount(String name, String type, String currency) {
        UUID id = UUID.nameUUIDFromBytes(
                ("account:" + type + ":" + name + ":" + currency).getBytes(StandardCharsets.UTF_8));
        jdbcTemplate.update(
                "INSERT INTO account (id, name, type, currency) VALUES (?, ?, ?, ?) ON CONFLICT (name, currency) DO NOTHING",
                id,
                name,
                type,
                currency);
        return id;
    }
}
