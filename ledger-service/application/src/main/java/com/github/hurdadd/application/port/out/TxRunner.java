package com.github.hurdadd.application.port.out;

// lets the service decide where the transaction begins and ends without depending on Spring
public interface TxRunner {
    void run(Runnable work);
}
