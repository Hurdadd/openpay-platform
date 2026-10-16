package com.github.hurdadd.adapter.out.tx;

import com.github.hurdadd.application.port.out.TxRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class SpringTxRunner implements TxRunner {

    private final TransactionTemplate template;

    public SpringTxRunner(PlatformTransactionManager transactionManager) {
        this.template = new TransactionTemplate(transactionManager);
    }

    @Override
    public void run(Runnable work) {
        template.executeWithoutResult(status -> work.run());
    }
}
