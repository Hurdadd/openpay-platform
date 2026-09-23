package com.github.hurdadd.application.port.out.psp;

public interface PspSecretResolver {
    String resolve(String pspId);
}
