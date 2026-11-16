package com.mju.linkro.route.service;

import com.mju.linkro.subway.api.*;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** The rules of the plain search timetable alternatives (#153) on made-up routes. */
class TimetableCandidatesTest {
    static final Instant TEN = Instant.parse("2026-11-16T01:00:00Z");

    /** A route arriving {@code minutes} after TEN on {@code lines} (one leg each), changing at {@code transfers}. */
    static TimedRoute route(int minutes, List<Long> lines, Long... transfers) {
        List<PathStep> steps = new ArrayList<>();
        for (long station : transfers) {
            steps.add(new PathStep(StepType.TRANSFER, 1L, "1호선", "#000", null, station, "역" + station, station, "역" + station, 0, 60, 10));
        }
        List<PathStop> stops = List.of(new PathStop(1, "출발", 37.5, 127.0, 1, 1, DirectionCode.UP, 0));
        RoutePath path = new RoutePath(steps, stops, 0, transfers.length, 0, 1, 2);
        List<TimedLeg> legs = new ArrayList<>();
        for (long line : lines) {
            legs.add(new TimedLeg(line, line + "호선", "T", "종점", DirectionCode.UP, 1, "가", 2, "나", TEN, TEN, 1, 0, 0));
        }
        Instant arrive = TEN.plusSeconds(minutes * 60L);
        return new TimedRoute(path, legs, TEN, TEN, arrive);
    }

    /** Answers by the blocked set; records every call. */
    record Scripted(Map<Set<Long>, TimedRoute> answers, List<Set<Long>> calls) implements TimetableCandidates.Search {
        Scripted(Map<Set<Long>, TimedRoute> answers) {
            this(answers, new ArrayList<>());
        }

        @Override
        public Optional<TimedRoute> avoiding(Set<Long> stations) {
            calls.add(stations);
            return Optional.ofNullable(answers.get(stations));
        }
    }

    @Test
    void theFirstRouteStaysFirstAndTheOthersGoByArrivalThenTransfers() {
        TimedRoute first = route(30, List.of(4L, 3L, 2L), 10L, 11L);
        TimedRoute sadang = route(30, List.of(4L, 2L), 12L);
        TimedRoute twoChanges = route(36, List.of(4L, 7L, 2L), 13L, 14L);
        TimedRoute ddp = route(36, List.of(4L, 2L), 15L);
        Scripted search = new Scripted(Map.of(
                Set.of(10L, 11L), sadang,
                Set.of(10L, 11L, 12L), twoChanges,
                Set.of(10L, 11L, 12L, 13L, 14L), ddp));

        TimetableCandidates.Result result = TimetableCandidates.of(first, search, 10);

        assertThat(result.routes()).containsExactly(first, sadang, ddp, twoChanges);
        assertThat(result.limitReached()).isFalse();
        assertThat(result.cut()).isFalse();
        assertThat(result.duplicates()).isZero();
        // The blocked set grows with every route; the last search finds nothing.
        assertThat(search.calls()).containsExactly(Set.of(10L, 11L), Set.of(10L, 11L, 12L), Set.of(10L, 11L, 12L, 13L, 14L),
                Set.of(10L, 11L, 12L, 13L, 14L, 15L));
    }

    @Test
    void theFirstRouteStaysFirstEvenWhenAnotherArrivesEarlier() {
        TimedRoute first = route(30, List.of(4L, 2L), 10L);
        TimedRoute earlier = route(29, List.of(4L, 3L), 11L);
        TimetableCandidates.Result result = TimetableCandidates.of(first, new Scripted(Map.of(Set.of(10L), earlier)), 10);
        assertThat(result.routes()).containsExactly(first, earlier);
    }

    @Test
    void aRouteMoreThanFifteenMinutesLaterIsCutAndEndsTheSearch() {
        TimedRoute first = route(30, List.of(4L, 2L), 10L);
        TimedRoute exactly = route(45, List.of(4L, 3L), 11L);
        TimedRoute late = route(46, List.of(4L, 7L), 12L);
        Scripted search = new Scripted(Map.of(Set.of(10L), exactly, Set.of(10L, 11L), late));

        TimetableCandidates.Result result = TimetableCandidates.of(first, search, 10);

        assertThat(result.routes()).containsExactly(first, exactly);
        assertThat(result.cut()).isTrue();
        assertThat(search.calls()).hasSize(2);
    }

    @Test
    void theSameLinesAndTransferStationsAreOneRouteEvenWithAChangeOnTheSameLine() {
        TimedRoute first = route(30, List.of(4L, 2L), 10L, 20L);
        // 2호선 twice in a row (a change of trains at 성수) is the line sequence 4 -> 2.
        TimedRoute again = route(33, List.of(4L, 2L, 2L), 10L, 20L);
        assertThat(TimetableCandidates.key(again)).isEqualTo(TimetableCandidates.key(first));
        Scripted search = new Scripted(Map.of(Set.of(10L, 20L), again));

        TimetableCandidates.Result result = TimetableCandidates.of(first, search, 10);

        assertThat(result.routes()).containsExactly(first);
        assertThat(result.duplicates()).isEqualTo(1);
        // Nothing new to block: the search stops.
        assertThat(search.calls()).hasSize(1);
    }

    @Test
    void theSafetyLimitKeepsTheRoutesFoundSoFar() {
        TimedRoute first = route(30, List.of(1L, 2L), 100L);
        // Every search finds a new route with a new transfer station.
        Function<Set<Long>, TimedRoute> endless = blocked -> route(31, List.of(1L, (long) blocked.size() + 10), (long) blocked.size() + 100);
        List<Set<Long>> calls = new ArrayList<>();
        TimetableCandidates.Result result = TimetableCandidates.of(first, blocked -> {
            calls.add(blocked);
            return Optional.of(endless.apply(blocked));
        }, 3);

        assertThat(result.routes()).hasSize(3).first().isEqualTo(first);
        assertThat(result.limitReached()).isTrue();
        // Two routes added, the third search found a fourth route that did not fit.
        assertThat(calls).hasSize(3);
    }

    @Test
    void oneRouteWhenThereIsNoOtherOrNoTransferToBlock() {
        TimedRoute direct = route(30, List.of(2L));
        Scripted never = new Scripted(Map.of());
        assertThat(TimetableCandidates.of(direct, never, 10).routes()).containsExactly(direct);
        assertThat(never.calls()).isEmpty();

        TimedRoute changing = route(30, List.of(4L, 2L), 10L);
        Scripted none = new Scripted(Map.of());
        TimetableCandidates.Result result = TimetableCandidates.of(changing, none, 10);
        assertThat(result.routes()).containsExactly(changing);
        assertThat(result.limitReached()).isFalse();
        assertThat(none.calls()).containsExactly(Set.of(10L));
    }

    @Test
    void aDirectAlternativeIsKeptAndEndsTheSearch() {
        TimedRoute first = route(30, List.of(4L, 2L), 10L);
        TimedRoute direct = route(40, List.of(4L));
        Scripted search = new Scripted(Map.of(Set.of(10L), direct));
        assertThat(TimetableCandidates.of(first, search, 10).routes()).containsExactly(first, direct);
        assertThat(search.calls()).hasSize(1);
    }

    @Test
    void theLimitMustBePositive() {
        assertThatThrownBy(() -> TimetableCandidates.of(route(30, List.of(2L)), blocked -> Optional.empty(), 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
