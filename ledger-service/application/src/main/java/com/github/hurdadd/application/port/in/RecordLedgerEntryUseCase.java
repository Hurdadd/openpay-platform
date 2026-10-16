package com.github.hurdadd.application.port.in;

public interface RecordLedgerEntryUseCase {
    void record(RecordLedgerEntryCommand command);
}
