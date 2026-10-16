package com.github.hurdadd.application.port.in;

import java.util.Currency;
import java.util.UUID;

public record RecordLedgerEntryCommand(
        UUID eventId,
        UUID paymentId,
        UUID merchantId,
        Currency currency,
        long amountMinor
) {
}
