package com.mju.linkro.route.service;

import com.mju.linkro.subway.api.TimedRoute;
import java.time.Clock;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import static com.mju.linkro.route.service.TimetableCandidatesTest.route;
import static org.assertj.core.api.Assertions.*;

/** The grace of the "more transfers" removal and the configurable cut (#155) on made-up routes. */
class TimetableCandidatesGraceTest {
    static final Duration CUT = TimetableCandidates.DEFAULT_CUT;
    static final Duration GRACE = TimetableCandidates.DEFAULT_GRACE;

    /** The same route arriving {@code seconds} later. */
    static TimedRoute later(TimedRoute route, long seconds) {
        return new TimedRoute(route.path(), route.legs(), route.leaveAt(), route.boardAt(), route.arriveAt().plusSeconds(seconds));
    }

    /** first, then each alternative found once (blocked sets grow by the stations of each route). */
    static TimetableCandidates.Result chain(Duration cut, Duration grace, TimedRoute first, TimedRoute... alternatives) {
        Map<Set<Long>, TimedRoute> answers = new HashMap<>();
        Set<Long> blocked = new HashSet<>(TimetableCandidates.transferStations(first));
        for (TimedRoute alternative : alternatives) {
            answers.put(Set.copyOf(blocked), alternative);
            blocked.addAll(TimetableCandidates.transferStations(alternative));
        }
        return TimetableCandidates.of(first, new TimetableCandidatesTest.Scripted(answers), 10, cut, grace);
    }

    @Test
    void theDefaultsAreFifteenAndSevenMinutes() {
        assertThat(CUT).isEqualTo(Duration.ofMinutes(15));
        assertThat(GRACE).isEqualTo(Duration.ofMinutes(7));
    }

    @Test
    void moreTransfersStayUpToTheGraceAndGoJustAfterIt() {
        TimedRoute first = route(30, List.of(4L, 3L, 2L), 10L, 11L);
        TimedRoute one = route(31, List.of(4L, 2L), 12L);
        TimedRoute twoBase = route(31, List.of(4L, 7L, 2L), 13L, 14L);
        long grace = GRACE.toSeconds();

        // A second short of the grace, exactly the grace: kept. One second more: removed.
        assertThat(chain(CUT, GRACE, first, one, later(twoBase, grace - 1)).routes()).hasSize(3);
        TimetableCandidates.Result exactly = chain(CUT, GRACE, first, one, later(twoBase, grace));
        assertThat(exactly.routes()).hasSize(3);
        assertThat(exactly.removed()).isZero();
        TimetableCandidates.Result past = chain(CUT, GRACE, first, one, later(twoBase, grace + 1));
        assertThat(past.routes()).containsExactly(first, one);
        assertThat(past.removed()).isEqualTo(1);
    }

    @Test
    void theSameArrivalWithMoreTransfersStays() {
        // 창동 -> 잠실: 노원·건대입구 (2 transfers) arrives with 동대문역사문화공원 (1 transfer) and stays.
        TimedRoute first = route(41, List.of(4L, 2L), 10L);
        TimedRoute nowon = route(41, List.of(4L, 7L, 2L), 11L, 12L);
        TimetableCandidates.Result result = chain(CUT, GRACE, first, nowon);
        assertThat(result.routes()).containsExactly(first, nowon);
        assertThat(result.removed()).isZero();
    }

    @Test
    void aZeroGraceRemovesOnlyLaterRoutes() {
        TimedRoute first = route(30, List.of(4L, 2L), 10L);
        TimedRoute same = route(30, List.of(4L, 7L, 2L), 11L, 12L);
        TimedRoute secondLater = later(route(30, List.of(4L, 8L, 2L), 13L, 14L), 1);
        TimetableCandidates.Result result = chain(CUT, Duration.ZERO, first, same, secondLater);
        assertThat(result.routes()).containsExactly(first, same);
        assertThat(result.removed()).isEqualTo(1);
    }

    @Test
    void theFirstRouteIsNeverRemovedButCountsAsAComparison() {
        // The first route has two transfers more and arrives 10 minutes after an alternative: it stays first.
        TimedRoute first = route(40, List.of(4L, 3L, 2L), 10L, 11L);
        TimedRoute direct = route(30, List.of(4L));
        assertThat(chain(CUT, GRACE, first, direct).routes()).containsExactly(first, direct);

        // As a comparison it removes an alternative with more transfers arriving more than the grace later.
        TimedRoute oneTransfer = route(30, List.of(4L, 2L), 10L);
        TimedRoute twoLate = later(route(30, List.of(4L, 7L, 2L), 11L, 12L), GRACE.toSeconds() + 1);
        TimetableCandidates.Result result = chain(CUT, GRACE, oneTransfer, twoLate);
        assertThat(result.routes()).containsExactly(oneTransfer);
        assertThat(result.removed()).isEqualTo(1);
    }

    @Test
    void theCutIsAParameter() {
        TimedRoute first = route(30, List.of(4L, 2L), 10L);
        TimedRoute late = route(48, List.of(4L, 3L), 11L);
        assertThat(chain(CUT, GRACE, first, late).routes()).containsExactly(first);
        assertThat(chain(CUT, GRACE, first, late).cut()).isTrue();
        assertThat(chain(Duration.ofMinutes(20), GRACE, first, late).routes()).containsExactly(first, late);
    }

    @Test
    void theCutMustBePositiveAndTheGraceNotNegative() {
        TimedRoute first = route(30, List.of(2L));
        TimetableCandidates.Search none = blocked -> Optional.empty();
        assertThatThrownBy(() -> TimetableCandidates.of(first, none, 10, Duration.ZERO, GRACE)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TimetableCandidates.of(first, none, 10, Duration.ofMinutes(-1), GRACE)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TimetableCandidates.of(first, none, 10, CUT, Duration.ofMinutes(-1))).isInstanceOf(IllegalArgumentException.class);
        assertThat(TimetableCandidates.of(first, none, 10, Duration.ofMinutes(1), Duration.ZERO).routes()).containsExactly(first);
    }

    @Test
    void theSettingsAreCheckedAtStartup() {
        assertThatThrownBy(() -> new RouteSearchService(null, null, null, null, null, null, Clock.systemUTC(), null, null, 365, 10, 0, 7))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("linkro.route.timetable-candidate-cut-minutes");
        assertThatThrownBy(() -> new RouteSearchService(null, null, null, null, null, null, Clock.systemUTC(), null, null, 365, 10, 15, -1))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("linkro.route.timetable-candidate-grace-minutes");
        assertThatCode(() -> new RouteSearchService(null, null, null, null, null, null, Clock.systemUTC(), null, null, 365, 10, 1, 0))
                .doesNotThrowAnyException();
    }
}
