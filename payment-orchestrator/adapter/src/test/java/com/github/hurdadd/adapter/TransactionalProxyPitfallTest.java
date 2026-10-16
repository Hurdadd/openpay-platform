package com.github.hurdadd.adapter;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** PAY-204 experiment behind ADR-0011: @Transactional is applied by a proxy, so it only works on calls that go through the proxy. */
@DataJpaTest
@Import(TransactionalProxyPitfallTest.Demo.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class TransactionalProxyPitfallTest {

    @Component
    static class Demo {
        @Transactional
        public boolean publicTx() {
            return TransactionSynchronizationManager.isActualTransactionActive();
        }

        @Transactional
        private boolean privateTx() {
            return TransactionSynchronizationManager.isActualTransactionActive();
        }

        public boolean callsPrivate() {
            return privateTx();
        }

        public boolean callsPublicOnThis() {
            return publicTx(); // self-invocation: bypasses the proxy
        }
    }

    @Autowired Demo demo;

    @Test
    void public_method_called_through_the_proxy_gets_a_transaction() {
        assertTrue(demo.publicTx());
    }

    @Test
    void private_transactional_method_silently_gets_no_transaction() {
        assertFalse(demo.callsPrivate());
    }

    @Test
    void self_invocation_of_a_public_transactional_method_gets_no_transaction() {
        assertFalse(demo.callsPublicOnThis());
    }
}
