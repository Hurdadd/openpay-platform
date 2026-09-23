package com.github.hurdadd.ledger;

import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public final class LedgerTestSupport {

    private LedgerTestSupport() {
    }

    public static JdbcTemplate migratedJdbcTemplate(PostgreSQLContainer postgres) {
        var dataSource = new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        Flyway.configure().dataSource(dataSource).load().migrate();
        return new JdbcTemplate(dataSource);
    }

    public static void truncateAll(JdbcTemplate jdbc) {
        jdbc.execute("TRUNCATE posting, journal_entry, account");
    }

    public static void inTransaction(JdbcTemplate jdbc, Runnable action) {
        var transactions = new TransactionTemplate(
                new DataSourceTransactionManager(Objects.requireNonNull(jdbc.getDataSource())));
        transactions.executeWithoutResult(status -> action.run());
    }

    public static int count(JdbcTemplate jdbc, String table) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
        return count == null ? 0 : count;
    }

    public static long balanceOf(JdbcTemplate jdbc, UUID accountId) {
        Long sum = jdbc.queryForObject(
                "SELECT COALESCE(SUM(amount), 0) FROM posting WHERE account_id = ?", Long.class, accountId);
        return sum == null ? 0 : sum;
    }

    public static Map<String, Long> balancesByName(JdbcTemplate jdbc) {
        Map<String, Long> out = new HashMap<>();
        jdbc.query("SELECT a.name, SUM(p.amount) AS total FROM posting p JOIN account a ON a.id = p.account_id GROUP BY a.name",
                (RowCallbackHandler) rs -> out.put(rs.getString("name"), rs.getLong("total")));
        return out;
    }
}
