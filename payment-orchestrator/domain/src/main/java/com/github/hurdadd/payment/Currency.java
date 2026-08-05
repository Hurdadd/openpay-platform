package com.github.hurdadd.payment;

public enum Currency {
    IRR(0),
    USD(2),
    EUR(2),
    AUD(2);

    private final int minorUnits;

    Currency(int minorUnits) {
        this.minorUnits = minorUnits;
    }

    public int getMinorUnits() {
        return minorUnits;
    }
}
