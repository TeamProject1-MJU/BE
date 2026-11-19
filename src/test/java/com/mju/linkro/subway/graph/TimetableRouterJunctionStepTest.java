package com.mju.linkro.subway.graph;

import com.mju.linkro.subway.api.*;
import com.mju.linkro.subway.congestion.HolidayCalendar;
import com.mju.linkro.subway.timetable.TimetableClock;
import com.mju.linkro.subway.timetable.TrainTimetable;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;

import static com.mju.linkro.subway.graph.TimetableRouterTest.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * #179: at 5호선 강동, a change from a train that goes on along another arm (하남검단산 side) to a 마천 train is its own RIDE
 * step, with no TRANSFER and the same times. The graph and trains of {@link TimetableRouterTest} plus:
 * H2 천호 -> 강동 -> 길동 (하남검단산행), M4 강동 -> 둔촌동 (마천행), K1 천호 -> 강동 (강동행, ends there), M5 강동 -> 둔촌동.
 *
 * <p>#181: when a 마천 train runs through 강동 (M1, M2), it is ridden instead of such a change; the change stays only
 * when there is none (after M2 forward; without M1 and M2 backward).
 */
class TimetableRouterJunctionStepTest {
    static final long CHEONHO = 12;
    static final long DUNCHON = 13;

    static TimetableRouter router() {
        return router(List.of(), Set.of());
    }

    /** With {@code extra} trains and without the trains coded {@code without}. */
    static TimetableRouter router(List<List<String>> extra, Set<String> without) {
        List<List<String>> trains = new ArrayList<>();
        for (List<String> train : TRAINS) {
            if (!without.contains(train.get(0).split(",")[5])) trains.add(train);
        }
        trains.add(train("5호선", "H2", "방화", "하남검단산", "천호:41000:41000", "강동:41070:41080", "길동:41170:41170"));
        trains.add(train("5호선", "M4", "강동", "마천", "강동:41100:41100", "둔촌동:41210:41210"));
        trains.add(train("5호선", "K1", "방화", "강동", "천호:42000:42000", "강동:42070:42070"));
        trains.add(train("5호선", "M5", "강동", "마천", "강동:42100:42100", "둔촌동:42210:42210"));
        trains.addAll(extra);
        StringBuilder csv = new StringBuilder(HEADER).append('\n');
        trains.forEach(rows -> rows.forEach(row -> csv.append(row).append('\n')));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(bytes)) {
            gzip.write(csv.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
        TrainTimetable timetable = TrainTimetable.load(new ByteArrayInputStream(bytes.toByteArray()), DATA);
        SubwayGraphProperties costs = new SubwayGraphProperties();
        costs.setDefaultBranchTransferSeconds(120);
        costs.setBranchTransfers(List.of(new SubwayGraphProperties.BranchTransfer("강동", "5", 16, 19)));
        return new TimetableRouter(SubwayGraph.of(DATA), timetable, new TimetableClock(new HolidayCalendar("")), costs, 0,
                Duration.ofMinutes(60));
    }

    @Test
    void aChangeFromATrainGoingOnToTheOtherArmIsItsOwnStep() {
        TimedRoute route = router().departingAt(CHEONHO, at(THURSDAY, 40900)).routeTo(DUNCHON).orElseThrow();
        assertThat(route.legs()).extracting(TimedLeg::trainCode).containsExactly("H2", "M4");
        assertThat(route.path().steps()).extracting(PathStep::type, PathStep::fromStationName, PathStep::toStationName)
                .containsExactly(tuple(StepType.RIDE, "천호", "강동"), tuple(StepType.RIDE, "강동", "둔촌동"));
        // Times and transfers as before: no TRANSFER step, the wait at 강동 is in the second step.
        assertThat(route.path().transferCount()).isZero();
        assertThat(route.arriveAt()).isEqualTo(at(THURSDAY, 41210));
        assertThat(route.path().steps().stream().mapToInt(PathStep::durationSec).sum()).isEqualTo(route.path().totalSeconds());
        assertThat(route.path().steps()).extracting(PathStep::durationSec).containsExactly(70, 140);
    }

    @Test
    void theBackwardSearchSplitsTheSameWay() {
        // Without M1 and M2 no 마천 train runs through 강동 before 41210 (the backward search has no travel limit here), so
        // the change stays (#181 falls back to #179).
        TimedRoute route = latest(router(List.of(), Set.of("M1", "M2")), CHEONHO, DUNCHON, 41210);
        assertThat(route.legs()).extracting(TimedLeg::trainCode).containsExactly("H2", "M4");
        assertThat(route.path().steps()).extracting(PathStep::type).containsExactly(StepType.RIDE, StepType.RIDE);
        assertThat(route.path().transferCount()).isZero();
    }

    @Test
    void aTrainEndingAtTheJunctionKeepsOneStep() {
        // K1 ends at 강동 (as 2호선 trips end at 성수): changing to M5 is not a train going on along another arm.
        TimedRoute route = router().departingAt(CHEONHO, at(THURSDAY, 41900)).routeTo(DUNCHON).orElseThrow();
        assertThat(route.legs()).extracting(TimedLeg::trainCode).containsExactly("K1", "M5");
        assertThat(route.path().steps()).extracting(PathStep::type).containsExactly(StepType.RIDE);
    }

    @Test
    void aThroughTrainKeepsOneStepAndTheBranchChangeKeepsItsTransfer() {
        TimedRoute through = router().departingAt(CHEONHO, at(THURSDAY, 39900)).routeTo(DUNCHON).orElseThrow();
        assertThat(through.legs()).extracting(TimedLeg::trainCode).containsExactly("M1");
        assertThat(through.path().steps()).extracting(PathStep::type).containsExactly(StepType.RIDE);
        // 길동 -> 둔촌동 (MAIN+ -> MACHEON) is a branch change with its TRANSFER step, unchanged.
        TimedRoute changed = router().departingAt(11, at(THURSDAY, 39900)).routeTo(DUNCHON).orElseThrow();
        assertThat(changed.path().steps()).extracting(PathStep::type)
                .containsExactly(StepType.RIDE, StepType.TRANSFER, StepType.RIDE);
    }

    @Test
    void theBackwardSearchRidesTheThroughTrainWhenItArrivesInTime() {
        // #181: M2 runs 천호 -> 둔촌동 through 강동 and arrives by 41210 (40690), so it is taken over H2 and M4.
        TimedRoute route = latest(router(), CHEONHO, DUNCHON, 41210);
        assertThat(route.legs()).extracting(TimedLeg::trainCode).containsExactly("M2");
        assertThat(route.path().steps()).extracting(PathStep::type, PathStep::fromStationName, PathStep::toStationName)
                .containsExactly(tuple(StepType.RIDE, "천호", "둔촌동"));
        assertThat(route.arriveAt()).isEqualTo(at(THURSDAY, 40690));
        assertThat(route.path().transferCount()).isZero();
    }

    @Test
    void aThroughTrainIsPreferredToAChangeAtTheJunctionEvenWhenItArrivesLater() {
        // H3 (하남검단산행) to 강동 and M6 (강동-origin 마천행) arrive at 40610; M2 runs through and arrives at 40690.
        List<List<String>> extra = List.of(
                train("5호선", "H3", "방화", "하남검단산", "천호:40400:40400", "강동:40470:40480", "길동:40570:40570"),
                train("5호선", "M6", "강동", "마천", "강동:40500:40500", "둔촌동:40610:40610"));
        TimedRoute route = router(extra, Set.of()).departingAt(CHEONHO, at(THURSDAY, 40300)).routeTo(DUNCHON).orElseThrow();
        assertThat(route.legs()).extracting(TimedLeg::trainCode).containsExactly("M2");
        assertThat(route.path().steps()).extracting(PathStep::type).containsExactly(StepType.RIDE);
        assertThat(route.arriveAt()).isEqualTo(at(THURSDAY, 40690));
        assertThat(route.path().transferCount()).isZero();
        // Without M2 the change stays, as its own step (#179).
        TimedRoute changed = router(extra, Set.of("M2")).departingAt(CHEONHO, at(THURSDAY, 40300)).routeTo(DUNCHON).orElseThrow();
        assertThat(changed.legs()).extracting(TimedLeg::trainCode).containsExactly("H3", "M6");
        assertThat(changed.path().steps()).extracting(PathStep::type).containsExactly(StepType.RIDE, StepType.RIDE);
        assertThat(changed.arriveAt()).isEqualTo(at(THURSDAY, 40610));
    }
}
