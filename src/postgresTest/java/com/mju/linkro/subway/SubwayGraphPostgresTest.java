package com.mju.linkro.subway;

import com.mju.linkro.subway.api.*;
import com.mju.linkro.subway.graph.*;
import com.mju.linkro.subway.mock.MockCongestionLookup;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import static org.assertj.core.api.Assertions.*;

/** Graph module on the real V1~V12 seed. Expected times are sums of V11 station_connections rows (data/edges.csv). */
@Testcontainers
@SpringBootTest(properties = {
        "spring.config.import=", "spring.jpa.hibernate.ddl-auto=validate", "spring.jpa.open-in-view=false",
        "spring.jpa.show-sql=false", "auth.kakao.app-id=1234", "auth.jwt.access-token-ttl=15m",
        "auth.jwt.secret=dGVzdC1vbmx5LXNlY3JldC0wMTIzNDU2Nzg5YWJjZGVmZ2hpams=",
        "linkro.subway.module=graph"})
@Timeout(120)
class SubwayGraphPostgresTest {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }
    @Autowired SubwayRouteFinder finder;
    @Autowired FareCalculator fares;
    @Autowired CongestionLookup congestion;
    @Autowired SubwayGraph graph;
    @Autowired NamedParameterJdbcTemplate named;
    @Autowired JdbcTemplate jdbc;
    @Autowired SubwayGraphProperties properties;

    @Test void graphModeUsesRealRoutesAndFaresButMockCongestion() {
        assertThat(finder).isInstanceOf(DijkstraSubwayRouteFinder.class);
        assertThat(fares).isInstanceOf(GraphFareCalculator.class);
        assertThat(congestion).isInstanceOf(MockCongestionLookup.class);
    }

    @Test void graphHasEveryNodeAndEdgeOfTheSeed() {
        assertThat(graph.nodeCount()).isEqualTo(277);
        assertThat(graph.rideCount()).isEqualTo(536);
        assertThat(graph.transferCount()).isEqualTo(78);
        assertThat(graph.stationCount()).isEqualTo(240);
    }

    @Test void everyStationReachesAllStations() {
        List<Long> stations = jdbc.queryForList("select id from stations order by id", Long.class);
        Map<Long, Integer> missing = new TreeMap<>();
        for (long origin : stations) {
            int reachable = finder.shortestPathsFrom(origin).reachableStationIds().size();
            if (reachable != stations.size()) missing.put(origin, reachable);
        }
        assertThat(missing).as("origins that cannot reach all 240 stations").isEmpty();
    }

    /** Branch transfers and waits at 0, so route time is a pure edge-time shortest path, checked by Floyd–Warshall. */
    @Test void allPairsMatchAnIndependentFloydWarshall() {
        SubwayGraphData data = new SubwayGraphLoader(named).load();
        SubwayGraphProperties zero = new SubwayGraphProperties();
        zero.setBranchTransfers(new ArrayList<>(List.of(
                new SubwayGraphProperties.BranchTransfer("성수", "2", 0, 0),
                new SubwayGraphProperties.BranchTransfer("신도림", "2", 0, 0),
                new SubwayGraphProperties.BranchTransfer("강동", "5", 0, 0))));
        DijkstraSubwayRouteFinder plain = new DijkstraSubwayRouteFinder(SubwayGraph.of(data), zero);

        Map<Long, Integer> index = new HashMap<>();
        data.nodes().forEach(node -> index.put(node.id(), index.size()));
        int n = index.size();
        long infinity = Long.MAX_VALUE / 4;
        long[][] dist = new long[n][n];
        for (long[] row : dist) Arrays.fill(row, infinity);
        for (int i = 0; i < n; i++) dist[i][i] = 0;
        for (var edge : data.edges()) {
            int a = index.get(edge.fromNodeId());
            int b = index.get(edge.toNodeId());
            dist[a][b] = Math.min(dist[a][b], edge.seconds());
        }
        for (int k = 0; k < n; k++) for (int i = 0; i < n; i++) for (int j = 0; j < n; j++) {
            if (dist[i][k] + dist[k][j] < dist[i][j]) dist[i][j] = dist[i][k] + dist[k][j];
        }
        Map<Long, List<Integer>> nodesByStation = new HashMap<>();
        data.nodes().forEach(node -> nodesByStation.computeIfAbsent(node.stationId(), id -> new ArrayList<>()).add(index.get(node.id())));

        int compared = 0;
        for (var from : data.stations()) {
            ShortestPathTree tree = plain.shortestPathsFrom(from.id());
            for (var to : data.stations()) {
                long expected = infinity;
                for (int a : nodesByStation.get(from.id())) for (int b : nodesByStation.get(to.id())) expected = Math.min(expected, dist[a][b]);
                RoutePath path = tree.pathTo(to.id()).orElseThrow();
                assertThat((long) path.totalSeconds()).as(from.name() + " -> " + to.name()).isEqualTo(expected);
                compared++;
            }
        }
        assertThat(compared).isEqualTo(240 * 240);
    }

    @Test void representativeRoutes() {
        // 2호선 강남 -> 역삼 -> 선릉 -> 삼성: 60 + 90 + 90
        RoutePath direct = route("강남", "삼성");
        assertThat(direct.totalSeconds()).isEqualTo(240);
        assertThat(direct.transferCount()).isZero();
        assertThat(direct.steps()).singleElement().satisfies(step -> {
            assertThat(step.lineName()).isEqualTo("2호선");
            assertThat(step.stationCount()).isEqualTo(3);
            assertThat(step.color()).isEqualTo("#00A84D");
        });
        assertThat(fares.fareOf(direct)).isEqualTo(1550);

        // 강남 -> 교대 (2호선 90) + 교대 2->3 transfer (63 s, 75 m) + 교대 -> 고속터미널 (3호선 120)
        RoutePath oneTransfer = route("강남", "고속터미널");
        assertThat(oneTransfer.totalSeconds()).isEqualTo(273);
        assertThat(oneTransfer.transferCount()).isEqualTo(1);
        assertThat(oneTransfer.steps()).extracting(PathStep::type).containsExactly(StepType.RIDE, StepType.TRANSFER, StepType.RIDE);
        assertThat(oneTransfer.steps().get(1)).satisfies(step -> {
            assertThat(step.lineName()).isEqualTo("3호선");
            assertThat(step.durationSec()).isEqualTo(63);
            assertThat(step.distanceM()).isEqualTo(75);
        });

        // 2호선 loop closure, one 90 s edge each way.
        assertSingleRide(route("시청", "충정로"), DirectionCode.OUTER, 90);
        assertSingleRide(route("충정로", "시청"), DirectionCode.INNER, 90);

        // 6호선 one-way loop: 연신내 -> 구산 -> 응암 is 80 + 120; 응암 -> 구산 must go round 응암 -> 역촌 -> 불광 -> 독바위 -> 연신내 -> 구산.
        assertSingleRide(route("연신내", "응암"), DirectionCode.LOOP, 200);
        RoutePath round = route("응암", "구산");
        assertSingleRide(round, DirectionCode.LOOP, 470); // 100 + 80 + 80 + 130 + 80
        assertThat(round.stops()).extracting(PathStop::stationName).containsExactly("응암", "역촌", "불광", "독바위", "연신내", "구산");

        // Starting at the junction itself is boarding, not a branch transfer.
        assertNoTransfer(route("성수", "용답"), 180);
        assertNoTransfer(route("용답", "성수"), 180);
        assertNoTransfer(route("신도림", "까치산"), 570); // 90 + 150 + 180 + 150
        // Through a junction onto the branch: 15044419 same-line rows 19 s / 68 s / 16 s.
        assertBranchTransfer(route("건대입구", "용답"), 90 + 19 + 180, "성수", 19, 23);
        assertBranchTransfer(route("대림", "도림천"), 150 + 68 + 90, "신도림", 68, 81);
        // 마천 -> 강동 (610: 90+80+70+80+80+100+110) + 16 + 강동 -> 하남검단산 (1060: 90+70+70+100+100+110+110+150+110+150)
        assertBranchTransfer(route("마천", "하남검단산"), 610 + 16 + 1060, "강동", 16, 19);
        assertBranchTransfer(route("둔촌동", "길동"), 110 + 16 + 90, "강동", 16, 19);
        // 방화 side <-> 마천 runs through.
        assertNoTransfer(route("천호", "마천"), 680); // 70 + 610

        // Values quoted in docs/subway-graph-69.md.
        // 응암 -> 역촌 -> 불광 (100 + 80) + 불광 6->3 transfer (83) + 불광 -> 연신내 (3호선 90) = 353 beats the loop only (100+80+80+130 = 390).
        RoutePath eungam = route("응암", "연신내");
        assertThat(eungam.totalSeconds()).isEqualTo(353);
        assertThat(eungam.transferCount()).isEqualTo(1);
        // 강남 -> 홍대입구: via 3호선 (2 transfers) 1717 s beats 2호선 only (1740 s).
        RoutePath gangnam = route("강남", "홍대입구");
        assertThat(gangnam.totalSeconds()).isEqualTo(1717);
        assertThat(gangnam.transferCount()).isEqualTo(2);
    }

    /** Reported, not asserted: graph load and one shortestPathsFrom per origin. */
    @Test void measureLoadAndTreeTimes() {
        long loadStart = System.nanoTime();
        SubwayGraph loaded = SubwayGraph.of(new SubwayGraphLoader(named).load());
        double loadMs = (System.nanoTime() - loadStart) / 1e6;
        DijkstraSubwayRouteFinder measured = new DijkstraSubwayRouteFinder(loaded, properties);
        List<Long> stations = jdbc.queryForList("select id from stations order by id", Long.class);
        stations.forEach(measured::shortestPathsFrom); // warm-up
        double total = 0;
        double max = 0;
        for (long origin : stations) {
            long start = System.nanoTime();
            measured.shortestPathsFrom(origin);
            double ms = (System.nanoTime() - start) / 1e6;
            total += ms;
            max = Math.max(max, ms);
        }
        System.out.printf("SUBWAY_GRAPH_PERF load=%.1fms trees=%d avg=%.3fms max=%.3fms%n", loadMs, stations.size(), total / stations.size(), max);
    }

    private RoutePath route(String from, String to) {
        return finder.shortestPath(stationId(from), stationId(to));
    }

    private long stationId(String name) {
        return jdbc.queryForObject("select id from stations where name = ?", Long.class, name);
    }

    private static void assertSingleRide(RoutePath path, DirectionCode direction, int seconds) {
        assertThat(path.totalSeconds()).isEqualTo(seconds);
        assertThat(path.steps()).singleElement().satisfies(step -> {
            assertThat(step.type()).isEqualTo(StepType.RIDE);
            assertThat(step.direction()).isEqualTo(direction);
        });
    }

    private static void assertNoTransfer(RoutePath path, int seconds) {
        assertThat(path.totalSeconds()).isEqualTo(seconds);
        assertThat(path.transferCount()).isZero();
    }

    private static void assertBranchTransfer(RoutePath path, int seconds, String junction, int transferSeconds, int meters) {
        assertThat(path.totalSeconds()).isEqualTo(seconds);
        assertThat(path.transferCount()).isEqualTo(1);
        PathStep transfer = path.steps().stream().filter(step -> step.type() == StepType.TRANSFER).findFirst().orElseThrow();
        assertThat(transfer.fromStationName()).isEqualTo(junction);
        assertThat(transfer.toStationName()).isEqualTo(junction);
        assertThat(transfer.durationSec()).isEqualTo(transferSeconds);
        assertThat(transfer.distanceM()).isEqualTo(meters);
        assertThat(path.rideDistanceM()).isEqualTo(path.steps().stream()
                .filter(step -> step.type() == StepType.RIDE).mapToInt(PathStep::distanceM).sum());
    }
}
