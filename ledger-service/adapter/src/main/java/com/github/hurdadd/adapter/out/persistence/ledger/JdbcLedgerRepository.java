package com.github.hurdadd.adapter.out.persistence.ledger;

import com.github.domain.JournalEntry;
import com.github.hurdadd.application.port.out.LedgerRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Repository;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
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
                "INSERT INTO journal_entry (id, description, idempotency_key, currency) VALUES (?, ?, ?, ?) " +
                        "ON CONFLICT (idempotency_key) DO NOTHING",
                entryId,
                entry.description(),
                idempotencyKey,
                entry.currency().getCurrencyCode()
        );

        if (inserted == 0) {
            // a redelivery is a no-op only if it carries the same entry; a different amount under the
            // same event id means a producer bug, and silently dropping it would mask that forever
            requireSameAsStored(entry, idempotencyKey);
            return;
        }

        for (var posting : entry.postings()) {
            jdbcTemplate.update(
                    "INSERT INTO posting (id, journal_entry_id, account_id, amount) VALUES (?, ?, ?, ?)",
                    UUID.randomUUID(),
                    entryId,
                    UUID.fromString(posting.accountId()),
                    posting.amount()
            );
        }
    }

    private void requireSameAsStored(JournalEntry entry, String idempotencyKey) {
        Map<String, Long> stored = new HashMap<>();
        jdbcTemplate.query(
                "SELECT p.account_id, p.amount FROM posting p JOIN journal_entry j ON j.id = p.journal_entry_id " +
                        "WHERE j.idempotency_key = ?",
                (RowCallbackHandler) rs -> stored.merge(rs.getString("account_id"), rs.getLong("amount"), Long::sum),
                idempotencyKey);
        String storedCurrency = jdbcTemplate.queryForObject(
                "SELECT currency FROM journal_entry WHERE idempotency_key = ?", String.class, idempotencyKey);

        Map<String, Long> incoming = new HashMap<>();
        entry.postings().forEach(p -> incoming.merge(p.accountId(), p.amount(), Long::sum));

        if (!stored.equals(incoming) || !entry.currency().getCurrencyCode().equals(storedCurrency)) {
            throw new ConflictingRedeliveryException(idempotencyKey);
        }
    }

    @Override
    public UUID ensureAccount(String name, String type, String currency) {
        UUID id = UUID.nameUUIDFromBytes(
                ("account:" + type + ":" + name + ":" + currency)
                        .getBytes(StandardCharsets.UTF_8)
        );

        jdbcTemplate.update(
                "INSERT INTO account (id, name, type, currency) VALUES (?, ?, ?, ?) " +
                        "ON CONFLICT (name, type, currency) DO NOTHING",
                id,
                name,
                type,
                currency
        );

        return id;
    }
}
