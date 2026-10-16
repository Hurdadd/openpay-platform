package com.github.hurdadd.adapter;

import com.github.hurdadd.adapter.out.persistence.lock.JdbcSchedulerLock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcSchedulerLockTest {

    private JdbcTemplate jdbc;

    @BeforeEach
    void freshDatabase() {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:lock-" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE scheduler_lock (name VARCHAR(100) PRIMARY KEY, locked_until TIMESTAMP NOT NULL, locked_by VARCHAR(100) NOT NULL)");
    }

    @Test
    void a_held_lock_blocks_the_other_instance_until_released() {
        var a = new JdbcSchedulerLock(jdbc);
        var b = new JdbcSchedulerLock(jdbc);

        assertTrue(a.tryAcquire("reconciler", Duration.ofMinutes(5)));
        assertFalse(b.tryAcquire("reconciler", Duration.ofMinutes(5)));

        a.release("reconciler");
        assertTrue(b.tryAcquire("reconciler", Duration.ofMinutes(5)));
    }

    @Test
    void only_the_owner_can_release() {
        var a = new JdbcSchedulerLock(jdbc);
        var b = new JdbcSchedulerLock(jdbc);
        a.tryAcquire("reconciler", Duration.ofMinutes(5));

        b.release("reconciler");

        assertFalse(b.tryAcquire("reconciler", Duration.ofMinutes(5)), "a non-owner release must not free the lock");
    }

    @Test
    void a_crashed_holder_loses_the_lock_when_the_lease_expires() throws InterruptedException {
        var crashed = new JdbcSchedulerLock(jdbc);
        var survivor = new JdbcSchedulerLock(jdbc);
        crashed.tryAcquire("reconciler", Duration.ofMillis(20));

        Thread.sleep(60);

        assertTrue(survivor.tryAcquire("reconciler", Duration.ofMinutes(5)));
    }

    @Test
    void exactly_one_of_many_concurrent_instances_wins() throws Exception {
        int instances = 8;
        var barrier = new CyclicBarrier(instances);
        ExecutorService pool = Executors.newFixedThreadPool(instances);
        List<Future<Boolean>> results = new ArrayList<>();
        for (int i = 0; i < instances; i++) {
            var lock = new JdbcSchedulerLock(jdbc);
            Callable<Boolean> contender = () -> {
                barrier.await();
                return lock.tryAcquire("reconciler", Duration.ofMinutes(5));
            };
            results.add(pool.submit(contender));
        }
        long winners = 0;
        for (var f : results) {
            if (f.get()) winners++;
        }
        pool.shutdown();

        assertEquals(1, winners);
    }
}
