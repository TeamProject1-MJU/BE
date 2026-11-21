package com.mju.linkro.route.kakao;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plain search alternatives (#153) on the migrated database and the bundled timetable with the default settings (station
 * access 60 s, transfer overrides #151): 창동 -> 강남. r1 is the route the plain search always gave (4호선 -> 3호선 -> 2호선
 * changing at 충무로 and 교대); the others avoid the transfer stations of the routes before them, and a route with more
 * transfers than another and arriving more than the grace (6 min, #155) later is removed. Kakao is never called.
 */
@Testcontainers
@ExtendWith(OutputCaptureExtension.class)
@SpringBootTest(properties = {
        "spring.config.import=", "spring.jpa.hibernate.ddl-auto=validate", "spring.jpa.open-in-view=false",
        "spring.jpa.show-sql=false", "auth.kakao.app-id=1234", "auth.jwt.access-token-ttl=15m",
        "auth.jwt.secret=dGVzdC1vbmx5LXNlY3JldC0wMTIzNDU2Nzg5YWJjZGVmZ2hpams=", "linkro.subway.module=graph"})
@org.springframework.context.annotation.Import(RouteSearchPostgresSupport.StubKakao.class)
@Timeout(90)
class RouteSearchCandidatesPostgresTest extends RouteSearchPostgresSupport {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    /** "arrival transfers via-stations" of each route, e.g. "23:33:30 2 충무로,교대". */
    static List<String> routes(JsonNode data) {
        List<String> routes = new ArrayList<>();
        for (JsonNode route : data.get("routes")) {
            JsonNode summary = route.get("summary");
            List<String> via = new ArrayList<>();
            for (JsonNode step : route.get("steps")) {
                if ("WALKING".equals(step.get("type").asString()) && step.get("guidance").asString().endsWith("환승")) {
                    via.add(step.get("guidance").asString().split("역 ")[0]);
                }
            }
            routes.add(OffsetDateTime.parse(summary.get("arrivalAt").asString()).toLocalTime() + " "
                    + summary.get("transferCount").asInt() + " " + String.join(",", via));
        }
        return routes;
    }

    JsonNode search(String departAt) throws Exception {
        JsonNode data = ok(search(id("창동"), gangnam, departAt, null), member);
        assertThat(data.get("routeSource").asString()).isEqualTo("INTERNAL");
        for (int i = 0; i < data.get("routes").size(); i++) {
            assertThat(data.get("routes").get(i).get("routeId").asString()).isEqualTo("r" + (i + 1));
        }
        return data;
    }

    @Test void mondayNightGivesTheFirstRouteAndItsAlternatives(CapturedOutput output) throws Exception {
        JsonNode data = search("2026-11-16T22:30:00+09:00");
        // r1 is the recommendation's route: since #185 the same 4207 and 2495 changing at 사당 (1 transfer) instead of
        // 충무로 and 교대 (2 transfers, same times), which is now r2. Then by arrival and transfers. 동대문역사문화공원 (1 transfer) stays
        // although 사당 has as many and arrives earlier. 노원·건대입구 (2 transfers, 23:36) stays since #155: it arrives
        // with 동대문역사문화공원, within the 6 minute grace. Removed: 동대문·신설동·성수 (3, 23:46:30, 10 minutes after
        // 동대문역사문화공원). The search after it (서울역·시청, 00:00) is more than 15 minutes after r1 and is cut.
        assertThat(routes(data)).containsExactly(
                "23:33:30 1 사당",
                "23:33:30 2 충무로,교대",
                "23:36 1 동대문역사문화공원",
                "23:36 2 노원,건대입구");
        assertThat(data.get("routes")).extracting(route -> route.get("routeType").isNull() ? null : route.get("routeType").asString())
                .containsExactly("FASTEST", null, null, null);
        // Every route has its own wait and arrival from the request time.
        for (JsonNode route : data.get("routes")) {
            JsonNode summary = route.get("summary");
            assertThat(summary.get("waitKind").asString()).isEqualTo("TIMETABLE");
            assertThat(summary.get("totalWithWaitSec").asInt()).isEqualTo(summary.get("waitSec").asInt() + summary.get("totalTimeSec").asInt());
        }
        assertThat(output.getAll()).contains("4 timetable routes (0 repeated, a later one cut)")
                .doesNotContain("max-timetable-candidates");
        server.verify();
    }

    @Test void saturdayNightHasThreeRoutes() throws Exception {
        JsonNode data = search("2026-11-14T22:30:00+09:00");
        // r1 is 사당 since #185 (4173 and 2411, 1 transfer, the same times as 충무로·교대). 노원·건대입구 (2 transfers,
        // 23:44:30) arrives 6.5 minutes after 사당 (1 transfer, 23:38): more than the 6 minute grace, so it is removed
        // (with 7 minutes it stayed).
        assertThat(routes(data)).containsExactly(
                "23:38 1 사당",
                "23:38 2 충무로,교대",
                "23:44:30 1 동대문역사문화공원");
        server.verify();
    }

    @Test void earlyMorningCutsTheSlowRoute(CapturedOutput output) throws Exception {
        JsonNode data = search("2026-11-16T06:00:00+09:00");
        // 06:55:30 + 15 min = 07:10:30: the next route (동대문·신설동·성수, 07:19) is cut. Since #190 사당 (1 transfer,
        // 4.5 minutes later, within the 15 minute cap) goes first; the earliest arrival (충무로·교대, 2 transfers) follows.
        assertThat(routes(data)).containsExactly(
                "07:00 1 사당",
                "06:55:30 2 충무로,교대",
                "07:05:30 1 동대문역사문화공원");
        assertThat(data.get("routes")).extracting(route -> route.get("routeType").isNull() ? null : route.get("routeType").asString())
                .containsExactly("FEWER_TRANSFERS", "FASTEST", null);
        assertThat(output.getAll()).contains("3 timetable routes (0 repeated, a later one cut)");
        server.verify();
    }

    @Test void justBeforeMidnightOnlyTheFirstRouteRuns() throws Exception {
        JsonNode data = search("2026-11-16T23:50:00+09:00");
        // 2호선 has no train left from 사당 or 동대문역사문화공원: without 충무로 and 교대 there is no route.
        assertThat(routes(data)).containsExactly("00:54 2 충무로,교대");
        assertThat(data.get("routes").get(0).get("routeType").asString()).isEqualTo("FASTEST");
        assertThat(Duration.ofSeconds(data.get("routes").get(0).get("summary").get("waitSec").asInt())).isPositive();
        server.verify();
    }

    /** #190: r1 of a plain search as "departure arrival transfers", e.g. "10:05:20 11:12 0". */
    String first(String from, String to, String departAt) throws Exception {
        JsonNode data = ok(search(id(from), id(to), departAt, null), member);
        server.verify();
        JsonNode summary = data.get("routes").get(0).get("summary");
        return OffsetDateTime.parse(summary.get("departureAt").asString()).toLocalTime() + " "
                + OffsetDateTime.parse(summary.get("arrivalAt").asString()).toLocalTime() + " " + summary.get("transferCount").asInt();
    }

    @Test void aDirectTrainWithinTheCapIsTheFirstRoute() throws Exception {
        // #190, 월 11/23 까치산 -> 오금 10:05: the earliest arrival changes twice (2호선 신도림, 3호선 교대, 11:11:30); 5583
        // (5호선) runs straight through and arrives 30 s later, so it is r1.
        assertThat(first("까치산", "오금", "2026-11-23T10:05:00+09:00")).isEqualTo("10:05:20 11:12 0");
        // 연신내 -> 신내 16:30: 6209 from 연신내 (boarding 16:47:10) arrives with 3261K and 6209 from 약수 (1 transfer) and
        // takes 11.3 minutes longer, within the 15 minute cap.
        assertThat(first("연신내", "신내", "2026-11-23T16:30:00+09:00")).isEqualTo("16:46:10 17:53:30 0");
        // Late at night 5694 (#187) runs from the 마천 branch straight on: 10.7 and 11.3 minutes longer than the 2호선 change at 왕십리.
        assertThat(first("둔촌동", "동대문역사문화공원", "2026-11-23T23:48:00+09:00")).isEqualTo("00:02:20 00:28:40 0");
        assertThat(first("마천", "충정로", "2026-11-23T23:37:00+09:00")).isEqualTo("23:51 00:38:20 0");
    }

    @Test void onFridayTheDirect6209IsTheFirstRouteToo() throws Exception {
        // 금 11/20 연신내 -> 신내 16:30 has the same candidates as Monday: 6209 from 연신내 (16:47:10, no transfer, 11.3
        // minutes longer) goes first. The route 27 minutes earlier (6205 round the loop to 봉화산, TimetableDetourPostgresTest)
        // is not among the candidates.
        assertThat(first("연신내", "신내", "2026-11-20T16:30:00+09:00")).isEqualTo("16:46:10 17:53:30 0");
    }

    @Test void aRouteMoreThanTheCapLongerStaysBehind() throws Exception {
        // 월 11/23 방배 -> 을지로3가 16:40: 2호선 round 성수 (no transfer, 17:29) takes 44.5 minutes against 26.5 for 2호선
        // to 교대 and 3호선 (1 transfer, 17:17): 18 minutes more, over the cap, so the earliest arrival stays r1.
        assertThat(first("방배", "을지로3가", "2026-11-23T16:40:00+09:00")).isEqualTo("16:50:30 17:17 1");
    }
}
