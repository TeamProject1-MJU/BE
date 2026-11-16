package com.mju.linkro.subway;

import com.mju.linkro.subway.api.TimedLeg;
import com.mju.linkro.subway.api.TimedRoute;
import com.mju.linkro.subway.congestion.HolidayCalendar;
import com.mju.linkro.subway.graph.SubwayGraph;
import com.mju.linkro.subway.graph.SubwayGraphProperties;
import com.mju.linkro.subway.graph.TimetableRouter;
import com.mju.linkro.subway.timetable.TimetableClock;
import com.mju.linkro.subway.timetable.TrainTimetable;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import static org.assertj.core.api.Assertions.*;

/**
 * Station access time of 60 s (#149) on the real seed and the bundled timetable: the first train boarded at 창동 for a
 * departure time set on a weekday is the one 카카오맵 picked in the measurements (it skips a train leaving 30 s after the
 * set minute and takes one leaving 60 s after it). Only the first train is checked: the rest of the route follows the
 * router's own route choice.
 */
@Testcontainers
@SpringBootTest(properties = {
        "spring.config.import=", "spring.jpa.hibernate.ddl-auto=validate", "spring.jpa.open-in-view=false",
        "spring.jpa.show-sql=false", "auth.kakao.app-id=1234", "auth.jwt.access-token-ttl=15m",
        "auth.jwt.secret=dGVzdC1vbmx5LXNlY3JldC0wMTIzNDU2Nzg5YWJjZGVmZ2hpams=",
        "linkro.subway.module=graph", "linkro.ai.module=mock"})
@Timeout(300)
class StationAccessPostgresTest {
    static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    /** A weekday without a holiday: the day of the 카카오맵 measurements. */
    static final LocalDate TUESDAY = LocalDate.of(2026, 11, 17);
    static final int ACCESS_SECONDS = 60;

    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired SubwayGraph graph;
    @Autowired TrainTimetable timetable;
    @Autowired SubwayGraphProperties costs;
    @Autowired JdbcTemplate jdbc;

    TimetableRouter router;
    long changdong;
    long nowon;

    @BeforeEach
    void setUp() {
        router = new TimetableRouter(graph, timetable, new TimetableClock(new HolidayCalendar("")), costs, ACCESS_SECONDS,
                Duration.ofMinutes(60));
        changdong = jdbc.queryForObject("select id from stations where name = ?", Long.class, "창동");
        nowon = jdbc.queryForObject("select id from stations where name = ?", Long.class, "노원");
    }

    static Instant at(String time) {
        return LocalDateTime.of(TUESDAY, LocalTime.parse(time)).atZone(SEOUL).toInstant();
    }

    static String hms(Instant instant) {
        return LocalTime.ofInstant(instant, SEOUL).toString();
    }

    /** "train boardAt" of the first train from 창동, leaving at {@code set}; leaving is boarding minus the access time. */
    String firstTrain(String set) {
        TimedRoute route = router.departingAt(changdong, at(set)).routeTo(nowon).orElseThrow();
        TimedLeg first = route.legs().get(0);
        assertThat(first.fromStationName()).isEqualTo("창동");
        assertThat(route.boardAt()).isEqualTo(first.boardAt());
        assertThat(route.leaveAt()).isEqualTo(route.boardAt().minusSeconds(ACCESS_SECONDS));
        return first.trainCode() + " " + hms(first.boardAt());
    }

    @Test
    void theFirstTrainIsTheOneKakaoMapPicked() {
        // 22:29:00 (K4330) leaves at the set minute and is skipped.
        assertThat(firstTrain("22:29")).isEqualTo("4202 22:33:30");
        // 22:44:30 (4204) leaves 30 s after the set minute and is skipped.
        assertThat(firstTrain("22:44")).isEqualTo("K4692 22:51:30");
        assertThat(firstTrain("19:52")).isEqualTo("K4656 19:53");
        // 19:53:00 (K4656) leaves at the set minute and is skipped.
        assertThat(firstTrain("19:53")).isEqualTo("4164 19:56:30");
    }

    @Test
    void aTrainLeavingExactlyTheAccessTimeLaterIsBoarded() {
        // K4656 leaves 창동 at 19:53:00: 60 s after 19:52:00, 59 s after 19:52:01.
        assertThat(firstTrain("19:52:00")).isEqualTo("K4656 19:53");
        assertThat(firstTrain("19:52:01")).isEqualTo("4164 19:56:30");
        // 4202 leaves at 22:33:30: 60 s after 22:32:30, 59 s after 22:32:31.
        assertThat(firstTrain("22:32:30")).isEqualTo("4202 22:33:30");
        assertThat(firstTrain("22:32:31")).isEqualTo("4690 22:38:30");
    }
}
