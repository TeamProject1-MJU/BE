package com.mju.linkro.subway.graph;

import com.mju.linkro.subway.api.*;
import com.mju.linkro.subway.graph.SubwayGraphData.*;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static com.mju.linkro.subway.graph.TimetableRouterTest.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * #187: a train listed without a stop between two of its stops (5694 둔촌동 -> 천호 without 강동) gets that stop back
 * when the timetable loads, so it can be ridden straight through instead of waiting for a later train.
 */
class TimetableRouterSkippedStopTest {
    // 5호선 가 -> 나 -> 다 -> 라 (DOWN, 100 s each).
    static final SubwayGraphData GRAPH = new SubwayGraphData(
            List.of(new Line(5, "5", "5호선", "#996CAC", 5)),
            List.of(station(1, "가"), station(2, "나"), station(3, "다"), station(4, "라")),
            List.of(new Node(51, 1, 5, "501", 1, "MAIN"), new Node(52, 2, 5, "502", 2, "MAIN"), new Node(53, 3, 5, "503", 3, "MAIN"),
                    new Node(54, 4, 5, "504", 4, "MAIN")),
            List.of(ride(1, 51, 52, DirectionCode.DOWN, 100), ride(2, 52, 53, DirectionCode.DOWN, 100), ride(3, 53, 54, DirectionCode.DOWN, 100)));

    // S is listed without 다; L stops everywhere 20 minutes later.
    static final List<List<String>> TRAINS = List.of(
            train("5호선", "S", "가", "라", "가:40000:40000", "나:40100:40120", "라:40330:40330"),
            train("5호선", "L", "가", "라", "가:41200:41200", "나:41300:41320", "다:41420:41440", "라:41540:41540"));

    @Test
    void theTrainSkippingAListedStopIsRiddenStraightThrough() {
        TimetableRouter router = TimetableRouterPruneTest.router(GRAPH, TRAINS);
        TimedRoute route = router.departingAt(1, at(THURSDAY, 40000)).routeTo(4).orElseThrow();
        // Before #187: 나 -> 라 was dropped, so S ended at 나 and the route waited for L (41540).
        assertThat(route.legs()).extracting(TimedLeg::trainCode).containsExactly("S");
        assertThat(route.boardAt()).isEqualTo(at(THURSDAY, 40000));
        assertThat(route.arriveAt()).isEqualTo(at(THURSDAY, 40330));
        assertThat(route.path().transferCount()).isZero();
        assertStepsAddUp(route);
        // Arriving by 40330 the search back finds S too.
        Map<Long, TimedRoute> back = router.latestDepartures(4, at(THURSDAY, 40330), Set.of(1L), 0);
        assertThat(back.get(1L).legs()).extracting(TimedLeg::trainCode).containsExactly("S");
    }
}
