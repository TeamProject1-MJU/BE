package com.mju.linkro.route.service;

import com.mju.linkro.subway.api.TimedLeg;
import com.mju.linkro.subway.api.TimedRoute;
import com.mju.linkro.subway.api.TimetableRouteFinder;
import com.mju.linkro.subway.timetable.TrainTimetable;
import java.time.Instant;
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
 * #187 on the migrated database and the bundled timetable: weekday 5694 (마천 23:52 -> 여의도) is listed without 강동, so
 * 둔촌동 -> 천호 was no RIDE edge and the train could not be ridden from the 마천 branch. With 강동 put back it runs
 * straight through as on 카카오맵 (둔촌동 00:03:20 -> 동대문역사문화공원 00:28:40). Two-stop 59xx trains stay unmapped.
 * Monday 2026-11-23; candidates as the route search makes them (cut 15 min, grace 6 min, at most 10).
 */
@Testcontainers
@SpringBootTest(properties = {
        "spring.config.import=", "spring.jpa.hibernate.ddl-auto=validate", "spring.jpa.open-in-view=false",
        "spring.jpa.show-sql=false", "auth.kakao.app-id=1234", "auth.jwt.access-token-ttl=15m",
        "auth.jwt.secret=dGVzdC1vbmx5LXNlY3JldC0wMTIzNDU2Nzg5YWJjZGVmZ2hpams=",
        "linkro.subway.module=graph", "linkro.ai.module=mock"})
@Timeout(300)
class TimetableSkippedStopPostgresTest {
    static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired TimetableRouteFinder timetable;
    @Autowired TrainTimetable trains;
    @Autowired JdbcTemplate jdbc;

    long station(String name) {
        return jdbc.queryForObject("select id from stations where name = ?", Long.class, name);
    }

    static Instant at(String dateTime) {
        return LocalDateTime.parse(dateTime).atZone(SEOUL).toInstant();
    }

    /** "board->arrive transfers trains", e.g. "00:03:20->00:28:40 0 5694". */
    static String describe(TimedRoute route) {
        return LocalTime.ofInstant(route.boardAt(), SEOUL) + "->" + LocalTime.ofInstant(route.arriveAt(), SEOUL) + " "
                + route.path().transferCount() + " " + String.join(",", route.legs().stream().map(TimedLeg::trainCode).toList());
    }

    List<String> candidates(String from, String to, String leaveAt) {
        long o = station(from), d = station(to);
        Instant t = at(leaveAt);
        TimedRoute first = timetable.departingAt(o, t).routeTo(d).orElseThrow();
        return TimetableCandidates.of(first, avoid -> timetable.departingAt(o, t, avoid).routeTo(d), 10).routes().stream()
                .map(TimetableSkippedStopPostgresTest::describe).toList();
    }

    String arriveBy(String from, String to, String by) {
        long o = station(from), d = station(to);
        return describe(timetable.latestDepartures(d, at(by), Set.of(o), 0).get(o));
    }

    @Test
    void loadingPutsBackOnlyTheStopsOfTrainsWithThreeOrMore() {
        assertThat(trains.report().pairsSplit()).containsOnlyKeys("5호선 둔촌동->천호", "5호선 상일동->하남풍산");
        // The 59xx pairs (two stops each) stay unmapped.
        assertThat(trains.report().pairsDropped()).hasSize(8).containsEntry("5호선 마천->둔촌동", 16).containsEntry("5호선 상일동->강동", 14);
    }

    @Test
    void fromDunchondongTheDirect5694IsACandidateAndTheArriveByRoute() {
        // r1 stays the 2호선 change at 왕십리 (arrives 10 minutes earlier); before #187 the 0-transfer candidate was 5692
        // to 왕십리 and 5694 from there, boarding 14 minutes earlier.
        assertThat(candidates("둔촌동", "동대문역사문화공원", "2026-11-23T23:48"))
                .containsExactly("23:49:20->00:18 1 5692,2521", "00:03:20->00:28:40 0 5694");
        // Before #187: 23:49:20 -> 00:48:20 (5692, 33 minutes at 왕십리 for 5218).
        assertThat(arriveBy("둔촌동", "동대문역사문화공원", "2026-11-24T01:00")).isEqualTo("00:03:20->00:28:40 0 5694");
    }

    @Test
    void fromMacheonTheDirect5694IsACandidateAndTheArriveByRoute() {
        assertThat(candidates("마천", "충정로", "2026-11-23T23:37"))
                .containsExactly("23:38->00:27:30 1 5692,2521", "23:52->00:38:20 0 5694");
        // Before #187: 23:38 -> 00:59 (5692, then 5218 at 왕십리).
        assertThat(arriveBy("마천", "충정로", "2026-11-24T01:00")).isEqualTo("23:52->00:38:20 0 5694");
    }

    @Test
    void aTwoStop59xxTrainIsStillNotRidden() {
        // 5902 leaves 상일동 at 05:23 for 강동 with no stop between: still unmapped, so the first train is 5012.
        assertThat(candidates("상일동", "강동", "2026-11-23T05:20")).first().isEqualTo("05:30->05:39 0 5012");
        assertThat(candidates("상일동", "강동", "2026-11-23T05:20")).noneMatch(route -> route.contains("5902"));
    }
}
