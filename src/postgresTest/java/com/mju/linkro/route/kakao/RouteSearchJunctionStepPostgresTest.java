package com.mju.linkro.route.kakao;

import com.mju.linkro.subway.api.DayType;
import com.mju.linkro.subway.timetable.TimetableClock;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * #179 on the migrated database and the bundled timetable (Wednesday 2026-11-18): a change at 5호선 강동 from a train that
 * goes on toward 하남 to a 마천 train is its own SUBWAY step; times and transfers stay; other junctions keep their steps.
 * #181: a 마천 train running through 강동 is taken instead of such a change, as 카카오맵 does; the change (and its step)
 * stays only when no such train exists or riding through would cost a transfer. #183: a change at 강동 still left gives way
 * to one train from the origin when there is one.
 */
@Testcontainers
@SpringBootTest(properties = {
        "spring.config.import=", "spring.jpa.hibernate.ddl-auto=validate", "spring.jpa.open-in-view=false",
        "spring.jpa.show-sql=false", "auth.kakao.app-id=1234", "auth.jwt.access-token-ttl=15m",
        "auth.jwt.secret=dGVzdC1vbmx5LXNlY3JldC0wMTIzNDU2Nzg5YWJjZGVmZ2hpams=", "linkro.subway.module=graph"})
@org.springframework.context.annotation.Import(RouteSearchPostgresSupport.StubKakao.class)
@Timeout(90)
class RouteSearchJunctionStepPostgresTest extends RouteSearchPostgresSupport {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired TimetableClock timetableClock;

    /** Steps as "S first->last/first train left at" (SUBWAY) or "W" (WALKING). */
    List<String> steps(JsonNode route) {
        List<String> result = new ArrayList<>();
        for (JsonNode step : route.get("steps")) {
            if (!"SUBWAY".equals(step.get("type").asString())) {
                result.add("W");
                continue;
            }
            JsonNode stops = step.get("stops");
            long alight = step.get("trainAlightStationId").asLong();
            result.add("S " + stops.get(0).get("name").asString() + "->" + stops.get(stops.size() - 1).get("name").asString() + "/"
                    + jdbc.queryForObject("select name from stations where id = ?", String.class, alight));
        }
        return result;
    }

    JsonNode first(String from, String to, String departAt) throws Exception {
        JsonNode data = ok(search(id(from), id(to), departAt, null), member);
        server.verify();
        return data.get("routes").get(0);
    }

    @Test void aMacheonTrainThroughIsTakenOverAHanamTrainAndAChange() throws Exception {
        // #181: 5649 (방화 -> 마천) 천호 17:32:30 -> 올림픽공원 17:38:20, one step, as 카카오맵 (17:32 -> 17:38). Before,
        // 5127 (하남검단산행) to 강동 and 5647 (강동-origin 마천행) on arrived at 17:33:30 in two steps (#179).
        JsonNode route = first("천호", "올림픽공원", "2026-11-18T17:20:00+09:00");
        assertThat(steps(route)).containsExactly("S 천호->올림픽공원/올림픽공원");
        JsonNode summary = route.get("summary");
        assertThat(summary.get("transferCount").asInt()).isZero();
        assertThat(summary.get("departureAt").asString()).isEqualTo("2026-11-18T17:31:30+09:00");
        assertThat(summary.get("arrivalAt").asString()).isEqualTo("2026-11-18T17:38:20+09:00");
        assertThat(summary.get("totalTimeSec").asInt()).isEqualTo(410);
    }

    @Test void aMacheonTrainThroughIsTakenOverASangildongTrainAndAChange() throws Exception {
        // #181: a 마천 train from 까치산 instead of 5037 (상일동행) to 강동 and 5553 (마천행) on (#179).
        JsonNode route = first("까치산", "마천", "2026-11-18T08:00:00+09:00");
        assertThat(steps(route)).containsExactly("S 까치산->마천/마천");
        assertThat(route.get("summary").get("transferCount").asInt()).isZero();
    }

    @Test void ridingThroughNeverCostsATransfer() throws Exception {
        // #181 guard: riding a 마천 train through from 왕십리 made the route 2호선 and 3호선 (one transfer, 17:16:00), so the
        // 5호선 trains changed at 강동 (none, 17:10:40) were kept. #183: that change gives way to the one 마천 train from
        // 영등포구청 (5641, 16:19:50 -> 17:16:00, as 카카오맵), still with no transfer.
        JsonNode route = first("영등포구청", "오금", "2026-11-18T16:10:00+09:00");
        assertThat(steps(route)).containsExactly("S 영등포구청->오금/오금");
        assertThat(route.get("summary").get("transferCount").asInt()).isZero();
        assertThat(route.get("summary").get("departureAt").asString()).isEqualTo("2026-11-18T16:18:50+09:00");
        assertThat(route.get("summary").get("arrivalAt").asString()).isEqualTo("2026-11-18T17:16:00+09:00");
    }

    @Test void aChangeAt강동LeftByTheFewerTransfersSearchGivesWayToTheDirectTrain() throws Exception {
        // #183: the earliest arrival is 2호선 and 3호선; the fewer-transfers search (#107) turns it into 5133 to 강동 and
        // 5657 on (18:15:20), and the direct 마천 train arriving later was never a candidate. 카카오맵 shows the direct
        // 5659 first (17:11 -> 18:19); so does the route now.
        JsonNode route = first("까치산", "개롱", "2026-11-18T17:00:00+09:00");
        assertThat(steps(route)).containsExactly("S 까치산->개롱/개롱");
        assertThat(route.get("summary").get("transferCount").asInt()).isZero();
        assertThat(route.get("summary").get("arrivalAt").asString()).isEqualTo("2026-11-18T18:19:40+09:00");
        JsonNode other = first("영등포구청", "개롱", "2026-11-18T17:10:00+09:00");
        assertThat(steps(other)).containsExactly("S 영등포구청->개롱/개롱");
        assertThat(other.get("summary").get("arrivalAt").asString()).isEqualTo("2026-11-18T18:19:40+09:00");
    }

    @Test void aRouteWithoutAChangeAt강동KeepsItsTrains() throws Exception {
        // #183 replaces only a change at 강동: the router keeps 2호선, 2호선 and 3호선 (two transfers, 11:11:30) although a
        // 마천 train from 까치산 arrives at 11:12:00. Since #190 that direct train is r1 (no transfer, 30 s later, within
        // the 15 minute cap) and the router's route is r2.
        JsonNode data = ok(search(id("까치산"), id("오금"), "2026-11-18T10:00:00+09:00", null), member);
        server.verify();
        JsonNode direct = data.get("routes").get(0);
        assertThat(direct.get("summary").get("transferCount").asInt()).isZero();
        assertThat(direct.get("summary").get("arrivalAt").asString()).isEqualTo("2026-11-18T11:12:00+09:00");
        JsonNode router = data.get("routes").get(1);
        assertThat(router.get("summary").get("transferCount").asInt()).isEqualTo(2);
        assertThat(router.get("summary").get("arrivalAt").asString()).isEqualTo("2026-11-18T11:11:30+09:00");
    }

    @Test void aThroughMacheonTrainIs1Step() throws Exception {
        assertThat(steps(first("천호", "마천", "2026-11-18T14:00:00+09:00"))).containsExactly("S 천호->마천/마천");
    }

    @Test void aBranchChangeAt강동KeepsItsTransfer() throws Exception {
        assertThat(steps(first("길동", "둔촌동", "2026-11-18T08:00:00+09:00"))).containsExactly("S 길동->강동/강동", "W", "S 강동->둔촌동/둔촌동");
    }

    @Test void 성수KeepsItsStepAnd응암IsATransfer() throws Exception {
        // 2호선 through 성수: the timetable run ends at 성수 and a new one starts; no new step.
        assertThat(steps(first("시청", "건대입구", "2026-11-18T14:00:00+09:00"))).containsExactly("S 시청->건대입구/성수");
        // 6호선 응암 loop: 구산 -> 역촌 leaves a train going on toward 새절 at 응암 for one going round again. #181 split the
        // step there; #183 makes it a transfer (a WALKING step between). 구산 -> 신내 turns DOWN there, unchanged.
        JsonNode loop = first("구산", "역촌", "2026-11-18T14:00:00+09:00");
        assertThat(steps(loop)).containsExactly("S 구산->응암/응암", "W", "S 응암->역촌/역촌");
        assertThat(loop.get("summary").get("transferCount").asInt()).isEqualTo(1);
        assertThat(steps(first("구산", "신내", "2026-11-18T14:00:00+09:00"))).containsExactly("S 구산->응암/신내", "S 응암->신내/신내");
    }

    /** The first route arriving at {@code to} by {@code time} on a coming weekday Wednesday. */
    JsonNode arrivingBy(String from, String to, java.time.LocalDate wednesday, String time) throws Exception {
        String body = "{\"originStationId\":" + id(from) + ",\"destinationStationId\":" + id(to) + ",\"arriveAt\":\"" + wednesday + "T"
                + time + "+09:00\"}";
        JsonNode data = ok(post("/api/v1/routes/search").contentType(MediaType.APPLICATION_JSON).content(body), member);
        server.verify();
        return data.get("routes").get(0);
    }

    /**
     * arriveAt must be after now, so a coming Wednesday that the timetable treats as a weekday (holidays run the Sunday
     * timetable, e.g. 2027-05-05) is used.
     */
    java.time.LocalDate comingWeekdayWednesday() {
        java.time.LocalDate wednesday = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Seoul")).plusDays(1)
                .with(java.time.temporal.TemporalAdjusters.nextOrSame(java.time.DayOfWeek.WEDNESDAY));
        while (timetableClock.dayTypeOf(wednesday) != DayType.WEEKDAY) wednesday = wednesday.plusWeeks(1);
        return wednesday;
    }

    @Test void arrivalModeRidesTheThroughTrain() throws Exception {
        // #181: the latest 마천 train through 강동 arriving at 마천 by 17:30 is 5641 (천호 17:06:30 -> 마천 17:21:30), taken
        // over 5125 (하남검단산행) to 강동 and 5643 (마천행) on, arriving at 17:29:40 (#179).
        java.time.LocalDate wednesday = comingWeekdayWednesday();
        JsonNode route = arrivingBy("천호", "마천", wednesday, "17:30:00");
        assertThat(steps(route)).containsExactly("S 천호->마천/마천");
        assertThat(route.get("summary").get("transferCount").asInt()).isZero();
        assertThat(route.get("summary").get("arrivalAt").asString()).isEqualTo(wednesday + "T17:21:30+09:00");
    }

    @Test void arrivalModeChangesAt강동WhenNoThroughTrainArrivesInTime() throws Exception {
        // By 06:00 at 올림픽공원 no 마천 train runs through 강동 yet (the first from 천호 leaves at 06:13:30): 5001 (하남검단산행)
        // to 강동 and 5505 (강동-origin 마천행) on, arriving at 05:52:00. #179 split the step; #183 makes it a transfer.
        java.time.LocalDate wednesday = comingWeekdayWednesday();
        JsonNode route = arrivingBy("천호", "올림픽공원", wednesday, "06:00:00");
        assertThat(steps(route)).containsExactly("S 천호->강동/강동", "W", "S 강동->올림픽공원/올림픽공원");
        assertThat(route.get("summary").get("transferCount").asInt()).isEqualTo(1);
        assertThat(route.get("summary").get("arrivalAt").asString()).isEqualTo(wednesday + "T05:52:00+09:00");
    }
}
