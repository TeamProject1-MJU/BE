package com.mju.linkro.subway.mock;

import com.mju.linkro.subway.api.PathStop;
import com.mju.linkro.subway.api.RoutePath;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class MockFareCalculatorTest {
    private final MockFareCalculator calculator = new MockFareCalculator();

    @ParameterizedTest
    @CsvSource({"0,1550", "10000,1550", "10001,1650", "15000,1650", "15001,1750",
        "50000,2350", "50001,2450", "58000,2450", "58001,2550"})
    void fareFollowsTheDistanceSteps(int rideDistanceM, int fare) {
        assertThat(calculator.fareOf(route(rideDistanceM))).isEqualTo(fare);
    }

    private static RoutePath route(int rideDistanceM) {
        PathStop stop = new PathStop(1, "역", 37.5, 127.0, 1, 1, null, 0);
        return new RoutePath(List.of(), List.of(stop), 0, 0, rideDistanceM, 1, 1);
    }
}
