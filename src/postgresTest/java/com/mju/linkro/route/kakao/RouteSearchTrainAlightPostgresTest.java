package com.mju.linkro.route.kakao;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * steps[].trainAlightStationId of timetable routes (#174) on the migrated database and the bundled timetable: the station the
 * first train of a SUBWAY step is left at, which is not the step's last stop when the route changes trains on the same line.
 */
@Testcontainers
@SpringBootTest(properties = {
        "spring.config.import=", "spring.jpa.hibernate.ddl-auto=validate", "spring.jpa.open-in-view=false",
        "spring.jpa.show-sql=false", "auth.kakao.app-id=1234", "auth.jwt.access-token-ttl=15m",
        "auth.jwt.secret=dGVzdC1vbmx5LXNlY3JldC0wMTIzNDU2Nzg5YWJjZGVmZ2hpams=", "linkro.subway.module=graph"})
@org.springframework.context.annotation.Import(RouteSearchPostgresSupport.StubKakao.class)
@Timeout(90)
class RouteSearchTrainAlightPostgresTest extends RouteSearchPostgresSupport {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    /** SUBWAY steps as "first stop -> last stop / first train left at" with station names. */
    List<String> subwaySteps(JsonNode route) {
        List<String> result = new ArrayList<>();
        for (JsonNode step : route.get("steps")) {
            if (!"SUBWAY".equals(step.get("type").asString())) {
                assertThat(step.get("trainAlightStationId").isNull()).isTrue();
                continue;
            }
            JsonNode stops = step.get("stops");
            JsonNode alight = step.get("trainAlightStationId");
            String alightName = alight.isNull() ? "null" : jdbc.queryForObject("select name from stations where id = ?", String.class, alight.asLong());
            result.add(stops.get(0).get("name").asString() + "->" + stops.get(stops.size() - 1).get("name").asString() + "/" + alightName);
        }
        return result;
    }

    @Test void aChangeOfTrainOnTheSameLineGivesTheFirstTrainsStation() {
        // Wednesday 23:50 응암 -> 잠실: via 신당 the 2호선 step 신당 -> 잠실 rides 2502 to 성수 and 2520 on to 잠실.
        JsonNode data = okSearch("응암", "잠실", "2026-11-18T23:50:00+09:00");
        List<List<String>> routes = new ArrayList<>();
        data.get("routes").forEach(route -> routes.add(subwaySteps(route)));
        assertThat(routes).anySatisfy(steps -> assertThat(steps).contains("신당->잠실/성수"));
        server.verify();
    }

    @Test void a5호선ChangeOfBranchClosesTheStepAt강동() {
        // 길동 -> 올림픽공원: 하남 side up to 강동, then a 마천 train. The first step ends at the junction.
        JsonNode data = okSearch("길동", "올림픽공원", "2026-11-18T08:00:00+09:00");
        assertThat(subwaySteps(data.get("routes").get(0))).containsExactly("길동->강동/강동", "강동->올림픽공원/올림픽공원");
        server.verify();
    }

    @Test void aSingleTrainStepGivesItsLastStopInPlainAndArrivalMode() throws Exception {
        assertThat(subwaySteps(okSearch("강남", "사당", "2026-11-18T13:00:00+09:00").get("routes").get(0))).containsExactly("강남->사당/사당");
        // Arrival mode (the next Tuesday 19:00, inside the 365-day limit) goes through the same timetable route shape.
        java.time.LocalDate tuesday = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Seoul")).plusDays(1)
                .with(java.time.temporal.TemporalAdjusters.nextOrSame(java.time.DayOfWeek.TUESDAY));
        String body = "{\"originStationId\":" + gangnam + ",\"destinationStationId\":" + sadang + ",\"arriveAt\":\"" + tuesday + "T19:00:00+09:00\"}";
        JsonNode arrival = ok(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/routes/search")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content(body), member);
        assertThat(subwaySteps(arrival.get("routes").get(0))).containsExactly("강남->사당/사당");
        server.verify();
    }

    JsonNode okSearch(String from, String to, String departAt) {
        try {
            return ok(search(id(from), id(to), departAt, null), member);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
