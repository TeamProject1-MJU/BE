package com.mju.linkro.subway.graph;

import com.mju.linkro.subway.api.DirectionCode;
import com.mju.linkro.subway.api.StepType;
import com.mju.linkro.subway.graph.SubwayGraphData.*;
import java.util.ArrayList;
import java.util.List;

/** Hand-made graph data for unit tests. */
final class GraphFixture {
    private final List<Line> lines = new ArrayList<>();
    private final List<Station> stations = new ArrayList<>();
    private final List<Node> nodes = new ArrayList<>();
    private final List<Edge> edges = new ArrayList<>();
    private long edgeId = 1;

    GraphFixture line(long id, String code) {
        lines.add(new Line(id, code, code + "호선", "#00000" + id, (int) id));
        return this;
    }

    GraphFixture station(long id, String name) {
        stations.add(new Station(id, name, 37.5 + id * 0.001, 127.0 + id * 0.001, true));
        return this;
    }

    GraphFixture unsupported(long id, String name) {
        stations.add(new Station(id, name, 37.5, 127.0, false));
        return this;
    }

    GraphFixture node(long id, long stationId, long lineId, int seq, String branch) {
        nodes.add(new Node(id, stationId, lineId, String.valueOf(id), seq, branch));
        return this;
    }

    GraphFixture ride(long from, long to, DirectionCode direction, int seconds, int meters) {
        edges.add(new Edge(edgeId++, from, to, StepType.RIDE, direction, seconds, meters));
        return this;
    }

    /** RIDE edges both ways with the same time and distance. */
    GraphFixture rides(long a, long b, DirectionCode ab, DirectionCode ba, int seconds, int meters) {
        return ride(a, b, ab, seconds, meters).ride(b, a, ba, seconds, meters);
    }

    GraphFixture transfer(long from, long to, int seconds, int meters) {
        edges.add(new Edge(edgeId++, from, to, StepType.TRANSFER, null, seconds, meters));
        return this;
    }

    GraphFixture transfers(long a, long b, int seconds, int meters) {
        return transfer(a, b, seconds, meters).transfer(b, a, seconds, meters);
    }

    SubwayGraphData data() {
        return new SubwayGraphData(lines, stations, nodes, edges);
    }

    SubwayGraph graph() {
        return SubwayGraph.of(data());
    }

    /** Defaults of subway-graph-defaults.properties: waits 0, default branch transfer 120 s, 15044419 rows. */
    static SubwayGraphProperties costs() {
        SubwayGraphProperties costs = new SubwayGraphProperties();
        costs.setDefaultBranchTransferSeconds(120);
        costs.setBranchTransfers(new ArrayList<>(List.of(
                new SubwayGraphProperties.BranchTransfer("성수", "2", 19, 23),
                new SubwayGraphProperties.BranchTransfer("신도림", "2", 68, 81),
                new SubwayGraphProperties.BranchTransfer("강동", "5", 16, 19))));
        return costs;
    }

    /**
     * Lines L1~L3. L1: A(1)-B(2)-C(3)-D(4), 100 s and 1,000 m per hop. L2: C-E(5), 100 s. Transfer C L1<->L2 60 s,
     * 50 m. L3: A-E direct 360 s (ties A->E via L1+L2 at 360 s with one transfer). Station 8 is unsupported and
     * station 9 is isolated (a node without edges).
     */
    static GraphFixture basic() {
        return new GraphFixture().line(1, "1").line(2, "2").line(3, "3")
                .station(1, "A").station(2, "B").station(3, "C").station(4, "D").station(5, "E")
                .unsupported(8, "미지원").station(9, "고립")
                .node(11, 1, 1, 1, "MAIN").node(21, 2, 1, 2, "MAIN").node(31, 3, 1, 3, "MAIN").node(41, 4, 1, 4, "MAIN")
                .node(32, 3, 2, 1, "MAIN").node(52, 5, 2, 2, "MAIN")
                .node(13, 1, 3, 1, "MAIN").node(53, 5, 3, 2, "MAIN")
                .node(81, 8, 1, 5, "MAIN").node(92, 9, 2, 3, "MAIN")
                .rides(11, 21, DirectionCode.DOWN, DirectionCode.UP, 100, 1000)
                .rides(21, 31, DirectionCode.DOWN, DirectionCode.UP, 100, 1000)
                .rides(31, 41, DirectionCode.DOWN, DirectionCode.UP, 100, 1000)
                .rides(41, 81, DirectionCode.DOWN, DirectionCode.UP, 100, 1000)
                .rides(32, 52, DirectionCode.DOWN, DirectionCode.UP, 100, 1000)
                .transfers(31, 32, 60, 50)
                .rides(13, 53, DirectionCode.DOWN, DirectionCode.UP, 360, 3000);
    }

    /**
     * Junctions shaped like the seed (times from V11 edges):
     * 성수 line 2: 뚝섬(10)-성수(11)-건대입구(12), SEONGSU 용답. 신도림 line 2: 대림(33)-신도림(34)-문래(35), SINJEONG 도림천.
     * 강동 line 5: 천호(38)-강동(39)-길동(40), MACHEON 둔촌동. 응암 line 6: 응암(1)-새절(2), one-way loop 응암->역촌->구산->응암.
     * 분기역 line 9 is a junction missing from the rule table. Line 7 is a small INNER/OUTER loop P1-P2-P3.
     */
    static GraphFixture junctions() {
        var down = DirectionCode.DOWN;
        var up = DirectionCode.UP;
        var inner = DirectionCode.INNER;
        var outer = DirectionCode.OUTER;
        return new GraphFixture().line(2, "2").line(5, "5").line(6, "6").line(7, "7").line(9, "9")
                .station(101, "뚝섬").station(102, "성수").station(103, "건대입구").station(104, "용답")
                .node(1012, 101, 2, 10, "MAIN").node(1022, 102, 2, 11, "MAIN").node(1032, 103, 2, 12, "MAIN").node(1042, 104, 2, 1, "SEONGSU")
                .rides(1012, 1022, inner, outer, 60, 800).rides(1022, 1032, inner, outer, 90, 1200).rides(1022, 1042, down, up, 180, 2300)
                .station(111, "대림").station(112, "신도림").station(113, "문래").station(114, "도림천")
                .node(1112, 111, 2, 33, "MAIN").node(1122, 112, 2, 34, "MAIN").node(1132, 113, 2, 35, "MAIN").node(1142, 114, 2, 1, "SINJEONG")
                .rides(1112, 1122, inner, outer, 150, 1800).rides(1122, 1132, inner, outer, 120, 1200).rides(1122, 1142, down, up, 90, 1000)
                .station(121, "천호").station(122, "강동").station(123, "길동").station(124, "둔촌동")
                .node(1215, 121, 5, 38, "MAIN").node(1225, 122, 5, 39, "MAIN").node(1235, 123, 5, 40, "MAIN").node(1245, 124, 5, 1, "MACHEON")
                .rides(1215, 1225, down, up, 70, 800).rides(1225, 1235, down, up, 90, 900).rides(1225, 1245, down, up, 110, 1200)
                .station(131, "응암").station(132, "새절").station(133, "역촌").station(134, "구산")
                .node(1316, 131, 6, 1, "MAIN").node(1326, 132, 6, 2, "MAIN").node(1336, 133, 6, 1, "EUNGAM_LOOP").node(1346, 134, 6, 2, "EUNGAM_LOOP")
                .rides(1316, 1326, down, up, 80, 900)
                .ride(1316, 1336, DirectionCode.LOOP, 100, 1100).ride(1336, 1346, DirectionCode.LOOP, 100, 1000)
                .ride(1346, 1316, DirectionCode.LOOP, 120, 1500)
                .station(141, "P1").station(142, "P2").station(143, "P3")
                .node(1417, 141, 7, 1, "MAIN").node(1427, 142, 7, 2, "MAIN").node(1437, 143, 7, 3, "MAIN")
                .rides(1417, 1427, inner, outer, 100, 1000).rides(1427, 1437, inner, outer, 100, 1000).rides(1437, 1417, inner, outer, 100, 1000)
                .station(151, "X1").station(152, "분기역").station(153, "X3").station(154, "Y1")
                .node(1519, 151, 9, 1, "MAIN").node(1529, 152, 9, 2, "MAIN").node(1539, 153, 9, 3, "MAIN").node(1549, 154, 9, 1, "SIDE")
                .rides(1519, 1529, down, up, 100, 1000).rides(1529, 1539, down, up, 100, 1000).rides(1529, 1549, down, up, 100, 1000);
    }
}
