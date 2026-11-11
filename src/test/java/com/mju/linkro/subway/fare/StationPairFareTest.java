package com.mju.linkro.subway.fare;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.mju.linkro.subway.api.FareCalculator;
import com.mju.linkro.subway.api.PathStop;
import com.mju.linkro.subway.api.RoutePath;
import com.mju.linkro.subway.api.StationFareLookup;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;

/** The shared fare rule (#109): table fare by station pair, the ridden distance otherwise, quiet when there is no table. */
class StationPairFareTest {
    /** 0 m is the base fare, as GraphFareCalculator and MockFareCalculator; otherwise a value showing the distance was used. */
    static final FareCalculator RIDDEN = path -> path.rideDistanceM() == 0 ? 1550 : 9000 + path.rideDistanceM();

    static RoutePath ridden(long from, long to, int meters) {
        return new RoutePath(List.of(), List.of(new PathStop(from, "x", 37.5, 127.0, from, 1, null, 0)), 0, 0, meters, from, to);
    }

    final AtomicInteger lookups = new AtomicInteger();
    final StationFareLookup table = (from, to) -> {
        lookups.incrementAndGet();
        return from == 1 && to == 2 ? OptionalInt.of(1650) : OptionalInt.empty();
    };
    ListAppender<ILoggingEvent> logs;

    @BeforeEach
    void captureLogs() {
        logs = new ListAppender<>();
        logs.start();
        ((Logger) LoggerFactory.getLogger(StationPairFare.class)).addAppender(logs);
    }

    @AfterEach
    void detach() {
        ((Logger) LoggerFactory.getLogger(StationPairFare.class)).detachAppender(logs);
    }

    long warnings() {
        return logs.list.stream().filter(event -> event.getLevel() == Level.WARN).count();
    }

    @Test
    void theTableFareOfThePairWinsOverTheRiddenDistance() {
        assertThat(new StationPairFare(RIDDEN, Optional.of(table)).fareOf(1, 2, ridden(1, 2, 11_000))).isEqualTo(1650);
        assertThat(warnings()).isZero();
    }

    @Test
    void aMissingPairUsesTheRiddenDistanceAndWarnsOncePerPair() {
        StationPairFare fare = new StationPairFare(RIDDEN, Optional.of(table));
        assertThat(fare.fareOf(2, 1, ridden(2, 1, 3000))).isEqualTo(12_000);
        assertThat(fare.fareOf(2, 1, ridden(2, 1, 3000))).isEqualTo(12_000);
        assertThat(fare.fareOf(3, 1, ridden(3, 1, 4000))).isEqualTo(13_000);
        assertThat(warnings()).isEqualTo(2);
    }

    @Test
    void theSameStationIsTheRiddenEmptyRouteWithoutLookupOrWarning() {
        // A candidate that is the member's own station (excluded from recommendations, but route search allows it):
        // the empty route, 0 m, the calculator's base fare 1,550원, as before #109.
        StationPairFare fare = new StationPairFare(RIDDEN, Optional.of(table));
        assertThat(fare.fareOf(1, 1, ridden(1, 1, 0))).isEqualTo(1550);
        assertThat(lookups).hasValue(0);
        assertThat(warnings()).isZero();
    }

    @Test
    void anEmptyOrMissingTableFallsBackWithoutPerPairWarnings() throws IOException {
        // A table that failed to load is empty (warned once when loading); a file with only the header has no pair either.
        FareTable headerOnly = FareTable.load(new ByteArrayInputStream(FareTableTest.gzip(FareTable.HEADER + "\n")), FareTableTest.CODES);
        assertThat(headerOnly.loaded()).isFalse();
        assertThat(FareTable.empty().loaded()).isFalse();
        for (StationFareLookup empty : List.of(FareTable.empty(), headerOnly)) {
            StationPairFare fare = new StationPairFare(RIDDEN, Optional.of(empty));
            for (long to = 2; to < 200; to++) assertThat(fare.fareOf(1, to, ridden(1, to, 3000))).isEqualTo(12_000);
        }
        assertThat(new StationPairFare(RIDDEN, Optional.empty()).fareOf(1, 2, ridden(1, 2, 3000))).isEqualTo(12_000);
        assertThat(warnings()).isZero();
    }
}
