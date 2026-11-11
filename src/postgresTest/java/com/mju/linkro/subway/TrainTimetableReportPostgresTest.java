package com.mju.linkro.subway;

import com.mju.linkro.subway.api.DayType;
import com.mju.linkro.subway.api.StepType;
import com.mju.linkro.subway.graph.SubwayGraphData;
import com.mju.linkro.subway.graph.SubwayGraphData.*;
import com.mju.linkro.subway.graph.SubwayGraphLoader;
import com.mju.linkro.subway.timetable.TrainTimetable;
import com.mju.linkro.subway.timetable.TrainTimetable.Departure;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import static org.assertj.core.api.Assertions.*;

/**
 * Validation report of the bundled train timetable on the real V1~V14 seed (#94). Writes
 * build/reports/timetable-94.md and prints it; the assertions pin what the report showed.
 *
 * <p>Timetable ride time of an edge = B arrival - A departure; compared with station_connections.travel_seconds
 * (the graph adds dwell-seconds on top when routing). The per-edge value is the median over all trains and day types.
 */
@Testcontainers
@SpringBootTest(properties = {
        "spring.config.import=", "spring.jpa.hibernate.ddl-auto=validate", "spring.jpa.open-in-view=false",
        "spring.jpa.show-sql=false", "auth.kakao.app-id=1234", "auth.jwt.access-token-ttl=15m",
        "auth.jwt.secret=dGVzdC1vbmx5LXNlY3JldC0wMTIzNDU2Nzg5YWJjZGVmZ2hpams=",
        "linkro.subway.module=graph"})
@Timeout(300)
class TrainTimetableReportPostgresTest {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }
    @Autowired TrainTimetable timetable;
    @Autowired NamedParameterJdbcTemplate jdbc;

    record EdgeStat(Edge edge, String line, String from, String to, String branch, Map<DayType, Integer> trains, Integer median,
                    Integer min, Integer max) {
        Integer diff() { return median == null ? null : median - edge.seconds(); }
        String name() { return line + "호선 " + from + "->" + to; }
    }

    @Test void report() throws IOException {
        SubwayGraphData data = new SubwayGraphLoader(jdbc).load();
        Map<Long, Line> lines = data.lines().stream().collect(Collectors.toMap(Line::id, l -> l));
        Map<Long, Station> stations = data.stations().stream().collect(Collectors.toMap(Station::id, s -> s));
        Map<Long, Node> nodes = data.nodes().stream().collect(Collectors.toMap(Node::id, n -> n));
        List<EdgeStat> stats = new ArrayList<>();
        for (Edge edge : data.edges()) {
            if (edge.type() != StepType.RIDE) continue;
            Node from = nodes.get(edge.fromNodeId());
            Node to = nodes.get(edge.toNodeId());
            Map<DayType, Integer> trains = new EnumMap<>(DayType.class);
            List<Integer> rides = new ArrayList<>();
            for (DayType day : DayType.values()) {
                List<Departure> departures = timetable.departures(edge.id(), day);
                trains.put(day, departures.size());
                departures.forEach(d -> rides.add(d.arriveSec() - d.departSec()));
            }
            Collections.sort(rides);
            String branch = "MAIN".equals(from.branchCode()) ? to.branchCode() : from.branchCode();
            stats.add(new EdgeStat(edge, lines.get(from.lineId()).code(), stations.get(from.stationId()).name(),
                    stations.get(to.stationId()).name(), branch, trains, rides.isEmpty() ? null : rides.get(rides.size() / 2),
                    rides.isEmpty() ? null : rides.get(0), rides.isEmpty() ? null : rides.get(rides.size() - 1)));
        }
        List<EdgeStat> mapped = stats.stream().filter(s -> s.median() != null).toList();
        List<EdgeStat> unmapped = stats.stream().filter(s -> s.median() == null).toList();
        TrainTimetable.LoadReport load = timetable.report();

        StringBuilder out = new StringBuilder("# Train timetable report (#94)\n\n");
        out.append("## Load\n\n");
        out.append("- rows ").append(load.rows()).append(", express skipped ").append(load.expressSkipped())
                .append(", unmapped rows ").append(load.unmappedRows()).append(' ').append(load.unmappedStations()).append('\n');
        out.append("- trains ").append(load.trains()).append(", rides ").append(load.rides()).append(", missing arrivals ")
                .append(load.missingArrivals()).append(", invalid rides ").append(load.invalidRides()).append(", ~")
                .append(load.approxBytes() / 1024).append(" KiB\n");
        out.append("- RIDE edges with trains ").append(mapped.size()).append(" / ").append(stats.size()).append("\n\n");

        out.append("## RIDE edges without trains (").append(unmapped.size()).append(")\n\n");
        unmapped.forEach(s -> out.append("- ").append(s.name()).append(" (edge ").append(s.edge().id()).append(", ")
                .append(s.edge().seconds()).append(" s)\n"));

        out.append("\n## Stop pairs not in the graph (").append(load.pairsNotInGraph().size()).append(")\n\n");
        out.append("| pair | rides | graph has a 2-edge path |\n| --- | --- | --- |\n");
        load.pairsNotInGraph().forEach((pair, count) -> out.append("| ").append(pair).append(" | ").append(count).append(" | ")
                .append(viaOneStation(pair, stats).orElse("-")).append(" |\n"));

        IntSummaryStatistics abs = mapped.stream().mapToInt(s -> Math.abs(s.diff())).summaryStatistics();
        double mean = mapped.stream().mapToInt(EdgeStat::diff).average().orElse(0);
        out.append("\n## Timetable ride time - travel_seconds (median per edge)\n\n");
        out.append(String.format(Locale.ROOT, "- edges %d, mean %.1f s, mean |diff| %.1f s, max |diff| %d s%n", mapped.size(), mean,
                abs.getAverage(), abs.getMax()));
        out.append("- |diff| buckets: ").append(buckets(mapped)).append("\n\n");
        out.append("Top 10 by |diff|\n\n");
        table(out, mapped.stream().sorted(Comparator.comparingInt((EdgeStat s) -> -Math.abs(s.diff())).thenComparing(EdgeStat::name))
                .limit(10).toList());

        section(out, "새 파일로 채워진 구간", stats, s -> (s.line().equals("1") && touches(s, "서울역"))
                || (s.line().equals("4") && (touches(s, "서울역") || touches(s, "총신대입구(이수)") || touches(s, "불암산")))
                || (s.line().equals("7") && (touches(s, "자양") || touches(s, "총신대입구(이수)")))
                || (s.line().equals("8") && touches(s, "암사역사공원")));
        section(out, "5호선 마천지선·강동·방화", stats, s -> s.line().equals("5")
                && (s.branch().equals("MACHEON") || touches(s, "강동") || touches(s, "방화")));
        section(out, "2호선 지선(성수·신정)", stats, s -> s.line().equals("2") && (s.branch().equals("SEONGSU") || s.branch().equals("SINJEONG")));
        section(out, "1호선", stats, s -> s.line().equals("1"));
        section(out, "6호선 응암순환", stats, s -> s.line().equals("6") && (s.branch().equals("EUNGAM_LOOP") || touches(s, "응암")));

        Path file = Path.of("build", "reports", "timetable-94.md");
        Files.createDirectories(file.getParent());
        Files.writeString(file, out, StandardCharsets.UTF_8);
        System.out.println(out);

        assertThat(load.rows()).isEqualTo(281_244);
        assertThat(load.unmappedRows()).as("every timetable station is one of ours").isZero();
        assertThat(load.expressSkipped()).isEqualTo(1403);
        assertThat(stats).hasSize(536);
        assertThat(unmapped).as("RIDE edges without trains").isEmpty();
        // Only 5호선 trains that skip stops of ours remain (see docs/timetable-94.md).
        assertThat(load.pairsNotInGraph()).hasSize(10).allSatisfy((pair, count) -> assertThat(pair).startsWith("5호선 "));
        // Every branch edge has trains on all three day types.
        assertThat(stats).filteredOn(s -> !s.branch().equals("MAIN"))
                .allSatisfy(s -> assertThat(s.trains().values()).as(s.name()).allMatch(n -> n > 0));
    }

    private static boolean touches(EdgeStat s, String station) {
        return s.from().equals(station) || s.to().equals(station);
    }

    /** "7호선 남성->내방" -> "남성->총신대입구(이수)->내방" when two RIDE edges of the line join them. */
    private static Optional<String> viaOneStation(String pair, List<EdgeStat> stats) {
        String line = pair.substring(0, pair.indexOf("호선"));
        String[] ends = pair.substring(pair.indexOf(' ') + 1).split("->");
        return stats.stream().filter(s -> s.line().equals(line) && s.from().equals(ends[0]))
                .flatMap(first -> stats.stream().filter(s -> s.line().equals(line) && s.from().equals(first.to()) && s.to().equals(ends[1]))
                        .map(second -> ends[0] + "->" + first.to() + "->" + ends[1]))
                .findFirst();
    }

    private static String buckets(List<EdgeStat> mapped) {
        int[] limits = {0, 10, 30, 60, 120};
        Map<String, Long> counts = new LinkedHashMap<>();
        for (int i = 0; i < limits.length; i++) {
            int low = i == 0 ? 0 : limits[i - 1] + 1;
            int high = limits[i];
            counts.put(low == high ? String.valueOf(high) : low + "~" + high,
                    mapped.stream().filter(s -> Math.abs(s.diff()) >= low && Math.abs(s.diff()) <= high).count());
        }
        counts.put(">" + limits[limits.length - 1], mapped.stream().filter(s -> Math.abs(s.diff()) > limits[limits.length - 1]).count());
        return counts.toString();
    }

    private static void section(StringBuilder out, String title, List<EdgeStat> stats, Predicate<EdgeStat> filter) {
        out.append("\n## ").append(title).append("\n\n");
        table(out, stats.stream().filter(filter).sorted(Comparator.comparing(EdgeStat::name)).toList());
    }

    private static void table(StringBuilder out, List<EdgeStat> rows) {
        out.append("| edge | branch | trains 평일/토/휴 | timetable median (min~max) | travel_seconds | diff |\n");
        out.append("| --- | --- | --- | --- | --- | --- |\n");
        for (EdgeStat s : rows) {
            out.append("| ").append(s.name()).append(" | ").append(s.branch()).append(" | ")
                    .append(s.trains().get(DayType.WEEKDAY)).append('/').append(s.trains().get(DayType.SATURDAY)).append('/')
                    .append(s.trains().get(DayType.SUNDAY)).append(" | ")
                    .append(s.median() == null ? "-" : s.median() + " (" + s.min() + "~" + s.max() + ")").append(" | ")
                    .append(s.edge().seconds()).append(" | ").append(s.diff() == null ? "-" : s.diff()).append(" |\n");
        }
    }
}
