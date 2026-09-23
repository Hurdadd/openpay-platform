package com.github.hurdadd.application.port.out.psp;

import com.github.hurdadd.domain.PspReference;

public record PspResult(PspOutcome pspOutcome , PspReference reference) {
}
