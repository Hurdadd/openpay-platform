package com.github.hurdadd;

import com.github.hurdadd.application.port.in.RecordLedgerEntryUseCase;
import com.github.hurdadd.application.port.out.LedgerRepository;
import com.github.hurdadd.application.port.out.TxRunner;
import com.github.hurdadd.application.service.RecordLedgerEntryService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class UseCaseConfig {

    @Bean
    RecordLedgerEntryUseCase recordLedgerEntryUseCase(LedgerRepository repository, TxRunner tx) {
        return new RecordLedgerEntryService(repository, tx);
    }
}
