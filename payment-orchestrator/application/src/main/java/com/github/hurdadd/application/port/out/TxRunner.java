package com.github.hurdadd.application.port.out;

import java.util.function.Supplier;

// lets the service decide where transactions begin and end without depending on Spring
public interface TxRunner {
    <T> T inTx(Supplier<T> work);

    default void run(Runnable work) {
        inTx(() -> {
            work.run();
            return null;
        });
    }
}
