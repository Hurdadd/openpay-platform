package com.github.hurdadd.adapter.out.persistence.lock;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Minimal cluster-wide lock for scheduled jobs, backed by the {@code scheduler_lock} table, so that
 * two orchestrator instances do not both poll the PSP for the same stuck payments.
 * <p>
 * Plain portable SQL: an expired lock row is taken over with a single conditional UPDATE; a missing
 * row is created, and the loser of a concurrent INSERT gets a duplicate-key error and backs off.
 * The lease must be longer than one run; if an instance dies mid-run the lock frees itself when the
 * lease expires. Timestamps come from the JVM clock, so keep the lease well above any clock skew
 * (shortcut: ShedLock with database time is the upgrade if skew ever matters).
 */
@Component
public class JdbcSchedulerLock {

    private final JdbcTemplate jdbc;
    private final String owner = UUID.randomUUID().toString();

    public JdbcSchedulerLock(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean tryAcquire(String name, Duration lease) {
        Instant now = Instant.now();
        Timestamp until = Timestamp.from(now.plus(lease));

        int taken = jdbc.update(
                "UPDATE scheduler_lock SET locked_until = ?, locked_by = ? WHERE name = ? AND locked_until < ?",
                until, owner, name, Timestamp.from(now));
        if (taken == 1) {
            return true;
        }
        try {
            return jdbc.update("INSERT INTO scheduler_lock (name, locked_until, locked_by) VALUES (?, ?, ?)",
                    name, until, owner) == 1;
        } catch (DuplicateKeyException heldByAnother) {
            return false;
        }
    }

    public void release(String name) {
        jdbc.update("UPDATE scheduler_lock SET locked_until = ? WHERE name = ? AND locked_by = ?",
                Timestamp.from(Instant.now()), name, owner);
    }
}
