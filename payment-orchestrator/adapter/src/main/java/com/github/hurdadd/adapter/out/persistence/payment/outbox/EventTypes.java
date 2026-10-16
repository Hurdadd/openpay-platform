package com.github.hurdadd.adapter.out.persistence.payment.outbox;

public enum EventTypes {
    // Only settlements are published: a failed payment moves no money, so the ledger has nothing to record
    PAYMENT_SETTLED_V1("PaymentSettled.v1");

    private final String wireFormat;
    EventTypes(String wireFormat) {
        this.wireFormat = wireFormat;
    }

    public String wireFormat(){
        return wireFormat;
    }

    public static EventTypes fromWireFormat(String value){

        for (EventTypes types : values()){
            if (types.wireFormat.equals(value)){
                return types;
            }
        }
        throw new IllegalArgumentException("Unknown event type: " + value);
    }
}
