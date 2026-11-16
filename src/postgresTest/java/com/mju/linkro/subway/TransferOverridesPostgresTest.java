package com.mju.linkro.subway;

import com.mju.linkro.subway.api.PathStep;
import com.mju.linkro.subway.api.RoutePath;
import com.mju.linkro.subway.api.StepType;
import com.mju.linkro.subway.api.SubwayRouteFinder;
import com.mju.linkro.subway.api.TimedLeg;
import com.mju.linkro.subway.api.TimedRoute;
import com.mju.linkro.subway.api.TimetableRouteFinder;
import com.mju.linkro.subway.timetable.TimetableProperties;
import com.mju.linkro.subway.timetable.TimetableProperties.TransferOverride;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import static org.assertj.core.api.Assertions.*;

/**
 * Transfer overrides of the timetable routes (#151) on the migrated database and the bundled timetable.
 *
 * <p>The guard: the default subway-transfer-overrides.properties names every TRANSFER pair of the seed exactly once
 * (39 pairs, 78 directed rows), by stored station name and line codes, and never shortens a seed transfer. A wrong entry
 * would stop the server at startup (TimetableRouter checks it), so this fails first.
 *
 * <p>The regression: 창동 -> 건대입구 on Monday 2026-11-16 with the default station access time (60 s) gives the trains
 * 카카오맵 showed for 22:29 and 22:44, and never the 470 s change at 노원 (4198 -> 7399, K4330 -> 7403).
 */
@Testcontainers
@SpringBootTest(properties = {
        "spring.config.import=", "spring.jpa.hibernate.ddl-auto=validate", "spring.jpa.open-in-view=false",
        "spring.jpa.show-sql=false", "auth.kakao.app-id=1234", "auth.jwt.access-token-ttl=15m",
        "auth.jwt.secret=dGVzdC1vbmx5LXNlY3JldC0wMTIzNDU2Nzg5YWJjZGVmZ2hpams=",
        "linkro.subway.module=graph", "linkro.ai.module=mock"})
@Timeout(300)
class TransferOverridesPostgresTest {
    static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    /** A weekday without a holiday: the day of the 카카오맵 measurements. */
    static final LocalDate MONDAY = LocalDate.of(2026, 11, 16);

    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired TimetableProperties properties;
    @Autowired TimetableRouteFinder timetable;
    @Autowired SubwayRouteFinder graph;
    @Autowired JdbcTemplate jdbc;

    long station(String name) {
        return jdbc.queryForObject("select id from stations where name = ?", Long.class, name);
    }

    static Instant at(String time) {
        return LocalDateTime.of(MONDAY, LocalTime.parse(time)).atZone(SEOUL).toInstant();
    }

    static String hms(Instant instant) {
        return LocalTime.ofInstant(instant, SEOUL).toString();
    }

    static String pair(String station, String a, String b) {
        return station + " " + (a.compareTo(b) < 0 ? a + "-" + b : b + "-" + a);
    }

    static List<String> trains(TimedRoute route) {
        return route.legs().stream().map(TimedLeg::trainCode).toList();
    }

    @Test
    void theDefaultFileCoversEveryTransferOfTheSeedOnceAndNeverShortensIt() {
        // pair -> seed seconds of each direction, read with plain SQL.
        Map<String, List<Integer>> seed = new TreeMap<>();
        jdbc.query("""
                select s.name, fl.line_code as from_line, tl.line_code as to_line, c.travel_seconds
                from station_connections c
                join station_lines f on f.id = c.from_station_line_id
                join station_lines t on t.id = c.to_station_line_id
                join stations s on s.id = f.station_id
                join subway_lines fl on fl.id = f.line_id
                join subway_lines tl on tl.id = t.line_id
                where c.connection_type = 'TRANSFER'
                """, row -> {
            seed.computeIfAbsent(pair(row.getString("name"), row.getString("from_line"), row.getString("to_line")),
                    key -> new ArrayList<>()).add(row.getInt("travel_seconds"));
        });
        assertThat(seed.values().stream().mapToInt(List::size).sum()).isEqualTo(78);
        assertThat(seed).hasSize(39).allSatisfy((key, directions) -> assertThat(directions).as(key).hasSize(2));

        Map<String, Integer> overrides = new TreeMap<>();
        for (TransferOverride entry : properties.getTransferOverrides()) {
            Integer earlier = overrides.put(pair(entry.getStation(), entry.getFromLine(), entry.getToLine()), entry.getSeconds());
            assertThat(earlier).as("repeated %s", entry).isNull();
        }
        assertThat(overrides.keySet()).as("pairs of the file vs TRANSFER pairs of the database").isEqualTo(seed.keySet());
        overrides.forEach((key, seconds) -> assertThat(seconds).as(key).isGreaterThanOrEqualTo(Collections.max(seed.get(key))));
    }

    @Test
    void changdongToKonkukTakesTheTrainsKakaoMapShowed() {
        long changdong = station("창동");
        long konkuk = station("건대입구");

        // 22:29: K4330 (22:29:00) is skipped by the access time; 4202 reaches 노원 at 22:35 and the 480 s change makes 7405.
        TimedRoute at2229 = timetable.departingAt(changdong, at("22:29")).routeTo(konkuk).orElseThrow();
        assertThat(trains(at2229)).containsExactly("4202", "7405");
        assertThat(hms(at2229.boardAt())).isEqualTo("22:33:30");
        assertThat(hms(at2229.arriveAt())).isEqualTo("23:11");
        assertThat(at2229.legs().get(1).fromStationName()).isEqualTo("노원");
        assertThat(at2229.legs().get(1).walkSec()).isEqualTo(480);

        // 22:44: 4204 (22:44:30) is skipped by the access time; K4692 -> 7409.
        TimedRoute at2244 = timetable.departingAt(changdong, at("22:44")).routeTo(konkuk).orElseThrow();
        assertThat(trains(at2244)).containsExactly("K4692", "7409");
        assertThat(hms(at2244.boardAt())).isEqualTo("22:51:30");
        assertThat(hms(at2244.arriveAt())).isEqualTo("23:31");

        // arriveAt 22:44: 7399 (노원 22:16:50) is still the latest train in time, but 4198 (노원 22:09:00, 470 s before it)
        // no longer makes it: K4684 (노원 22:03:30) does, so the member leaves at 22:01 instead of 22:06:30.
        TimedRoute by2244 = timetable.latestDepartures(konkuk, at("22:44"), Set.of(changdong), 0).get(changdong);
        assertThat(trains(by2244)).containsExactly("K4684", "7399");
        assertThat(hms(by2244.leaveAt())).isEqualTo("22:01");
        assertThat(hms(by2244.arriveAt())).isEqualTo("22:41:30");

        // 22:03: known mismatch, not asserted against 카카오맵 (4198 -> 7401, 22:51:30). The router now takes K4707 down to
        // 동대문역사문화공원 and 2호선 (22:48:30), 3 minutes earlier than 카카오맵's 노원 route; the route choice rules are
        // outside #151. Only the 470 s change at 노원 is checked here.
        TimedRoute at2203 = timetable.departingAt(changdong, at("22:03")).routeTo(konkuk).orElseThrow();
        assertThat(trains(at2203)).doesNotContain("7399");
        assertThat(trains(at2229)).doesNotContain("K4330", "7403");
    }

    @Test
    void theFixedCostGraphKeepsTheSeedTransfer() {
        // Dijkstra: 창동 -> 노원 (4호선), 노원 4 -> 7 transfer 232 s + transfer-wait-seconds 120, 노원 -> 중계 (7호선).
        RoutePath path = graph.shortestPath(station("창동"), station("중계"));
        PathStep transfer = path.steps().stream().filter(step -> step.type() == StepType.TRANSFER).findFirst().orElseThrow();
        assertThat(transfer.fromStationName()).isEqualTo("노원");
        assertThat(transfer.durationSec()).isEqualTo(232 + 120);
        assertThat(transfer.distanceM()).isEqualTo(278);
    }
}
