package com.github.hurdadd.application.port.out.psp;

import com.github.hurdadd.domain.PspReference;

public interface PspClient {

    PspResult authorize(AuthorizationRequest dto);

    // used by the reconciler to ask the PSP "what happened to this one?" when its callback never arrived
    PspResult checkStatus(PspReference reference);
}
