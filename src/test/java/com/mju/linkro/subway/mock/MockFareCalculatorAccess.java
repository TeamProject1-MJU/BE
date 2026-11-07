package com.mju.linkro.subway.mock;

import com.mju.linkro.subway.api.FareCalculator;

/** The real fare formula for tests outside this package. */
public final class MockFareCalculatorAccess {
    private MockFareCalculatorAccess() {}

    public static FareCalculator calculator() {
        return new MockFareCalculator();
    }
}
