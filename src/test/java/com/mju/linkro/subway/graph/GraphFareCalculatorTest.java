package com.mju.linkro.subway.graph;

import com.mju.linkro.subway.api.PathStop;
import com.mju.linkro.subway.api.RoutePath;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GraphFareCalculatorTest {

    static SubwayFareProperties defaults() {
        SubwayFareProperties fare = new SubwayFareProperties();
        fare.setBaseFare(1550);
        fare.setBaseDistanceM(10_000);
        fare.setMidLimitM(50_000);
        fare.setMidStepM(5_000);
        fare.setLongStepM(8_000);
        fare.setStepFare(100);
        return fare;
    }

    @ParameterizedTest
    @CsvSource({"0,1550", "10000,1550", "10001,1650", "15000,1650", "15001,1750",
        "50000,2350", "50001,2450", "58000,2450", "58001,2550"})
    void boundariesMatchTheMock(int rideDistanceM, int fare) {
        assertThat(new GraphFareCalculator(defaults()).fareOf(route(rideDistanceM))).isEqualTo(fare);
    }

    @Test
    void settingsMustBePositive() {
        List<Consumer<SubwayFareProperties>> invalid = List.of(
                fare -> fare.setBaseFare(0), fare -> fare.setBaseDistanceM(-1), fare -> fare.setMidLimitM(0),
                fare -> fare.setMidStepM(0), fare -> fare.setLongStepM(0), fare -> fare.setStepFare(0),
                fare -> fare.setMidLimitM(9_999));
        for (var change : invalid) {
            SubwayFareProperties fare = defaults();
            change.accept(fare);
            assertThatThrownBy(() -> new GraphFareCalculator(fare)).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageStartingWith("linkro.subway.fare.");
        }
    }

    private static RoutePath route(int rideDistanceM) {
        return new RoutePath(List.of(), List.of(new PathStop(1, "역", 37.5, 127.0, 1, 1, null, 0)), 0, 0, rideDistanceM, 1, 1);
    }
}
