package com.github.hurdadd.application.service;

import com.github.domain.JournalEntry;
import com.github.domain.Posting;
import com.github.hurdadd.application.port.in.RecordLedgerEntryCommand;
import com.github.hurdadd.application.port.in.RecordLedgerEntryUseCase;
import com.github.hurdadd.application.port.out.LedgerRepository;
import com.github.hurdadd.application.port.out.TxRunner;

import java.util.List;
import java.util.UUID;

public class RecordLedgerEntryService implements RecordLedgerEntryUseCase {

    private final LedgerRepository ledgerRepository;
    private final TxRunner tx;

    public RecordLedgerEntryService(LedgerRepository ledgerRepository, TxRunner tx) {
        this.ledgerRepository = ledgerRepository;
        this.tx = tx;
    }

    @Override
    public void record(RecordLedgerEntryCommand command) {
        if (command.amountMinor() <= 0) {
            throw new IllegalArgumentException("Settlement amount must be positive: " + command.amountMinor());
        }

        tx.run(() -> {
            String currency = command.currency().getCurrencyCode();
            UUID clearingId = ledgerRepository.ensureAccount("psp_clearing", "CLEARING", currency);
            UUID merchantAccountId = ledgerRepository.ensureAccount(
                    "merchant:" + command.merchantId(), "MERCHANT_PAYABLE", currency);

            JournalEntry entry = new JournalEntry(
                    List.of(new Posting(command.amountMinor(), clearingId.toString()),
                            new Posting(-command.amountMinor(), merchantAccountId.toString())),
                    "Settlement " + command.paymentId(),
                    command.currency());

            ledgerRepository.save(entry, command.eventId().toString());
        });
    }
}
