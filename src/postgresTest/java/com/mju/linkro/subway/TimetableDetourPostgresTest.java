package com.mju.linkro.subway;

import com.mju.linkro.subway.api.TimedLeg;
import com.mju.linkro.subway.api.TimedRoute;
import com.mju.linkro.subway.api.TimetableRouteFinder;
import com.mju.linkro.subway.graph.SubwayGraph;
import com.mju.linkro.subway.graph.SubwayGraphProperties;
import com.mju.linkro.subway.graph.TimetableRouter;
import com.mju.linkro.subway.timetable.TimetableClock;
import com.mju.linkro.subway.timetable.TimetableProperties;
import com.mju.linkro.subway.timetable.TrainTimetable;
import java.time.Duration;
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
 * The detour cap of #185 ({@code linkro.subway.graph.timetable-detour-cap-seconds}, default 900 s) on the migrated
 * database and the bundled timetable. Weekday 2026-11-20. Only 6호선 and 3호선 trains whose every stop pair is a RIDE edge
 * of the graph (no pair of the train timetable's pairsNotInGraph report, all on 5호선), so a fix of those pairs keeps these
 * routes: 6112, 6121, 6127, 6205, 6209, 3148, 3261K.
 */
@Testcontainers
@SpringBootTest(properties = {
        "spring.config.import=", "spring.jpa.hibernate.ddl-auto=validate", "spring.jpa.open-in-view=false",
        "spring.jpa.show-sql=false", "auth.kakao.app-id=1234", "auth.jwt.access-token-ttl=15m",
        "auth.jwt.secret=dGVzdC1vbmx5LXNlY3JldC0wMTIzNDU2Nzg5YWJjZGVmZ2hpams=",
        "linkro.subway.module=graph", "linkro.ai.module=mock"})
@Timeout(300)
class TimetableDetourPostgresTest {
    static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    static final LocalDate FRIDAY = LocalDate.of(2026, 11, 20);

    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired TimetableRouteFinder timetable;
    @Autowired SubwayGraph graph;
    @Autowired TrainTimetable trains;
    @Autowired TimetableClock clock;
    @Autowired SubwayGraphProperties costs;
    @Autowired TimetableProperties properties;
    @Autowired JdbcTemplate jdbc;

    long station(String name) {
        return jdbc.queryForObject("select id from stations where name = ?", Long.class, name);
    }

    static Instant at(String time) {
        return LocalDateTime.of(FRIDAY, LocalTime.parse(time)).atZone(SEOUL).toInstant();
    }

    static String hms(Instant instant) {
        return LocalTime.ofInstant(instant, SEOUL).toString();
    }

    static List<String> trainCodes(TimedRoute route) {
        return route.legs().stream().map(TimedLeg::trainCode).toList();
    }

    /** The application's router with another detour cap; everything else as configured. */
    TimetableRouteFinder withCap(int seconds) {
        SubwayGraphProperties copy = new SubwayGraphProperties();
        copy.setDwellSeconds(costs.getDwellSeconds());
        copy.setTransferWaitSeconds(costs.getTransferWaitSeconds());
        copy.setBranchTransferWaitSeconds(costs.getBranchTransferWaitSeconds());
        copy.setDefaultBranchTransferSeconds(costs.getDefaultBranchTransferSeconds());
        copy.setBranchTransfers(costs.getBranchTransfers());
        copy.setTimetableChanges(costs.getTimetableChanges());
        copy.setTimetableDetourCapSeconds(seconds);
        return new TimetableRouter(graph, trains, clock, copy, properties.getStationAccessSeconds(),
                Duration.ofMinutes(properties.getNoServiceWindowMinutes()), properties.getTransferOverrides());
    }

    TimedRoute arriveBy(TimetableRouteFinder router, String from, String to, String by) {
        return router.latestDepartures(station(to), at(by), Set.of(station(from)), 0).get(station(from));
    }

    @Test
    void theDefaultCapIs15Minutes() {
        assertThat(costs.getTimetableDetourCapSeconds()).isEqualTo(900);
    }

    @Test
    void arriveByTakesTheQuickerRouteWhenFewerTransfersArriveMoreThanTheCapLater() {
        // Leaving 신내 on 6112 at 09:54 either way: staying on it to 응암 for 6127 round the loop (no transfer) arrives at
        // 11:22:30, 24 minutes after changing to 3호선 at 약수 and back to 6호선 at 연신내 (10:58:30).
        TimedRoute route = arriveBy(timetable, "신내", "구산", "11:30");
        assertThat(trainCodes(route)).containsExactly("6112", "3148", "6121");
        assertThat(hms(route.boardAt())).isEqualTo("09:54");
        assertThat(hms(route.arriveAt())).isEqualTo("10:58:30");
        assertThat(route.path().transferCount()).isEqualTo(2);
    }

    @Test
    void arriveByKeepsFewerTransfersWithinALargerCap() {
        TimedRoute route = arriveBy(withCap(1800), "신내", "구산", "11:30");
        assertThat(trainCodes(route)).containsExactly("6112", "6127");
        assertThat(hms(route.boardAt())).isEqualTo("09:54");
        assertThat(hms(route.arriveAt())).isEqualTo("11:22:30");
        assertThat(route.path().transferCount()).isZero();
    }

    @Test
    void leavingTimeTakesTheLaterLeavingRouteWhenFewerTransfersLeaveMoreThanTheCapEarlier() {
        // Both reach 신내 at 17:53:30 on 6209: 6205 round the loop to 봉화산 (no transfer) leaves 연신내 at 16:31:10,
        // 27 minutes before 3261K to 약수 (16:58:30, one transfer).
        TimedRoute route = timetable.departingAt(station("연신내"), at("16:30")).routeTo(station("신내")).orElseThrow();
        assertThat(trainCodes(route)).containsExactly("3261K", "6209");
        assertThat(hms(route.boardAt())).isEqualTo("16:58:30");
        assertThat(hms(route.arriveAt())).isEqualTo("17:53:30");
        assertThat(route.path().transferCount()).isEqualTo(1);
    }

    @Test
    void leavingTimeKeepsFewerTransfersWithinALargerCap() {
        TimedRoute route = withCap(1800).departingAt(station("연신내"), at("16:30")).routeTo(station("신내")).orElseThrow();
        assertThat(trainCodes(route)).containsExactly("6205", "6209");
        assertThat(hms(route.boardAt())).isEqualTo("16:31:10");
        assertThat(hms(route.arriveAt())).isEqualTo("17:53:30");
        assertThat(route.path().transferCount()).isZero();
    }
}
