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

    @Test void aHanamTrainThenAMacheonTrainAre2Steps() throws Exception {
        // 5127 (하남검단산행) 천호 17:26 -> 강동, 5647 (마천행) 강동 -> 올림픽공원 17:33:30.
        JsonNode route = first("천호", "올림픽공원", "2026-11-18T17:20:00+09:00");
        assertThat(steps(route)).containsExactly("S 천호->강동/강동", "S 강동->올림픽공원/올림픽공원");
        JsonNode summary = route.get("summary");
        assertThat(summary.get("transferCount").asInt()).isZero();
        assertThat(summary.get("departureAt").asString()).isEqualTo("2026-11-18T17:25:00+09:00");
        assertThat(summary.get("arrivalAt").asString()).isEqualTo("2026-11-18T17:33:30+09:00");
        assertThat(summary.get("totalTimeSec").asInt()).isEqualTo(510);
    }

    @Test void aSangildongTrainThenAMacheonTrainAre2Steps() throws Exception {
        // 5037 (상일동행) 까치산 -> 강동, 5553 (마천행) 강동 -> 마천.
        JsonNode route = first("까치산", "마천", "2026-11-18T08:00:00+09:00");
        assertThat(steps(route)).containsExactly("S 까치산->강동/강동", "S 강동->마천/마천");
        assertThat(route.get("summary").get("transferCount").asInt()).isZero();
    }

    @Test void aThroughMacheonTrainIs1Step() throws Exception {
        assertThat(steps(first("천호", "마천", "2026-11-18T14:00:00+09:00"))).containsExactly("S 천호->마천/마천");
    }

    @Test void aBranchChangeAt강동KeepsItsTransfer() throws Exception {
        assertThat(steps(first("길동", "둔촌동", "2026-11-18T08:00:00+09:00"))).containsExactly("S 길동->강동/강동", "W", "S 강동->둔촌동/둔촌동");
    }

    @Test void otherJunctionsKeepTheirSteps() throws Exception {
        // 2호선 through 성수: the timetable run ends at 성수 and a new one starts; no new step.
        assertThat(steps(first("시청", "건대입구", "2026-11-18T14:00:00+09:00"))).containsExactly("S 시청->건대입구/성수");
        // 6호선 응암 loop: unchanged (구산 -> 역촌 changes trains at 응암 within the loop; 구산 -> 신내 turns DOWN there).
        assertThat(steps(first("구산", "역촌", "2026-11-18T14:00:00+09:00"))).containsExactly("S 구산->역촌/응암");
        assertThat(steps(first("구산", "신내", "2026-11-18T14:00:00+09:00"))).containsExactly("S 구산->응암/신내", "S 응암->신내/신내");
    }

    @Test void arrivalModeSplitsTheSameWay() throws Exception {
        // The latest departure arriving at 마천 by 17:30 on a Wednesday: 5125 (하남검단산행) to 강동, 5643 (마천행) on.
        // arriveAt must be after now, so a coming Wednesday that the timetable treats as a weekday (holidays run the
        // Sunday timetable, e.g. 2027-05-05) is used.
        java.time.LocalDate wednesday = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Seoul")).plusDays(1)
                .with(java.time.temporal.TemporalAdjusters.nextOrSame(java.time.DayOfWeek.WEDNESDAY));
        while (timetableClock.dayTypeOf(wednesday) != DayType.WEEKDAY) wednesday = wednesday.plusWeeks(1);
        String body = "{\"originStationId\":" + id("천호") + ",\"destinationStationId\":" + id("마천") + ",\"arriveAt\":\"" + wednesday + "T17:30:00+09:00\"}";
        JsonNode data = ok(post("/api/v1/routes/search").contentType(MediaType.APPLICATION_JSON).content(body), member);
        server.verify();
        JsonNode route = data.get("routes").get(0);
        assertThat(steps(route)).containsExactly("S 천호->강동/강동", "S 강동->마천/마천");
        assertThat(route.get("summary").get("transferCount").asInt()).isZero();
        assertThat(route.get("summary").get("arrivalAt").asString()).isEqualTo(wednesday + "T17:29:40+09:00");
    }
}
