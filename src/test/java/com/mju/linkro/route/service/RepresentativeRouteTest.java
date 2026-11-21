package com.mju.linkro.route.service;

import com.mju.linkro.subway.api.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** The first route of a plain search (#190) on made-up routes: cap 900 s as the default. */
class RepresentativeRouteTest {
    static final Instant TEN = TimetableCandidatesTest.TEN;
    static final int CAP = 900;

    /** Leaves {@code leave} seconds after TEN, arrives {@code arrive} seconds after TEN, with {@code transfers}. */
    static TimedRoute route(int leave, int arrive, int transfers) {
        List<PathStep> steps = new ArrayList<>();
        for (int i = 0; i < transfers; i++) {
            steps.add(new PathStep(StepType.TRANSFER, 1L, "1호선", "#000", null, 10L + i, "역", 10L + i, "역", 0, 60, 10));
        }
        RoutePath path = new RoutePath(steps, List.of(new PathStop(1, "출발", 37.5, 127.0, 1, 1, DirectionCode.UP, 0)), 0, transfers, 0, 1, 2);
        Instant at = TEN.plusSeconds(leave);
        return new TimedRoute(path, List.of(), at, at, TEN.plusSeconds(arrive));
    }

    static List<TimedRoute> reorder(TimedRoute... routes) {
        List<TimedRoute> list = List.of(routes);
        return RepresentativeRoute.reorder(list, list, CAP);
    }

    @Test
    void aRouteWithFewerTransfersWithinTheCapGoesFirst() {
        // 까치산 -> 오금 10:05: transfers 2 arriving 11:11:30, the 5호선 direct 30 s later.
        TimedRoute fastest = route(0, 3930, 2);
        TimedRoute direct = route(20, 3960, 0);
        assertThat(reorder(fastest, direct)).containsExactly(direct, fastest);
    }

    @Test
    void travelTimeOverTheCapKeepsTheFastestFirst() {
        // Same arrival, leaving 27 minutes earlier (금 연신내 -> 신내 16:30): 1620 s more travel.
        TimedRoute fastest = route(1620, 5000, 1);
        TimedRoute early = route(0, 5000, 0);
        assertThat(reorder(fastest, early)).containsExactly(fastest, early);
    }

    @Test
    void exactlyTheCapStillCounts() {
        TimedRoute fastest = route(0, 1000, 1);
        TimedRoute exactly = route(0, 1000 + CAP, 0);
        TimedRoute oneMore = route(0, 1001 + CAP, 0);
        assertThat(reorder(fastest, exactly)).containsExactly(exactly, fastest);
        assertThat(reorder(fastest, oneMore)).containsExactly(fastest, oneMore);
    }

    @Test
    void theFewestTransfersWinThenTheShortestTravelThenTheEarliestArrival() {
        TimedRoute fastest = route(0, 1000, 3);
        TimedRoute two = route(0, 1060, 2);
        TimedRoute zeroSlow = route(0, 1600, 0);
        TimedRoute zeroQuick = route(300, 1700, 0);   // travel 1400 < 1600
        TimedRoute one = route(0, 1100, 1);
        // The rest stay by arrival then transfers, the former first route among them.
        assertThat(reorder(fastest, two, one, zeroSlow, zeroQuick)).containsExactly(zeroQuick, fastest, two, one, zeroSlow);
        // Equal transfers and travel: the earlier arrival.
        TimedRoute later = route(100, 1700, 0);
        TimedRoute earlier = route(0, 1600, 0);
        assertThat(RepresentativeRoute.pick(List.of(fastest, later, earlier), CAP)).isEqualTo(2);
    }

    @Test
    void aSingleRouteStaysAsItIs() {
        TimedRoute only = route(0, 1000, 2);
        assertThat(reorder(only)).containsExactly(only);
        assertThat(RepresentativeRoute.reorder(List.of(), List.of(), CAP)).isEmpty();
    }

    @Test
    void asManyOrMoreTransfersAreNeverMovedUp() {
        TimedRoute fastest = route(0, 1000, 1);
        TimedRoute same = route(0, 1060, 1);
        TimedRoute more = route(0, 1060, 2);
        assertThat(reorder(fastest, same, more)).containsExactly(fastest, same, more);
    }

    @Test
    void aShorterTravelTimeArrivingLaterCounts() {
        // Leaving later and arriving later, but taking less time on the way: the travel time does not grow.
        TimedRoute fastest = route(0, 1000, 1);
        TimedRoute later = route(1200, 2000, 0);
        assertThat(reorder(fastest, later)).containsExactly(later, fastest);
    }
}
