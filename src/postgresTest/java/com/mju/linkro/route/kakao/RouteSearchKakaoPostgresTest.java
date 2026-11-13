package com.mju.linkro.route.kakao;

import com.mju.linkro.auth.jwt.JwtProvider;
import com.mju.linkro.user.domain.User;
import com.mju.linkro.user.repository.UserRepository;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.client.RestClient;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;


/**
 * POST /routes/search with the train timetable off ({@code linkro.subway.timetable.enabled=false}): no timetable router,
 * so solo searches (#130) keep the Kakao-first flow with the fixed-cost INTERNAL fallback. Kakao is stubbed.
 */
@Testcontainers
@ExtendWith(OutputCaptureExtension.class)
@SpringBootTest(properties = {
        "spring.config.import=", "spring.jpa.hibernate.ddl-auto=validate", "spring.jpa.open-in-view=false",
        "spring.jpa.show-sql=false", "auth.kakao.app-id=1234", "auth.jwt.access-token-ttl=15m",
        "auth.jwt.secret=dGVzdC1vbmx5LXNlY3JldC0wMTIzNDU2Nzg5YWJjZGVmZ2hpams=", "linkro.subway.module=graph", "linkro.subway.timetable.enabled=false"})
@org.springframework.context.annotation.Import(RouteSearchPostgresSupport.StubKakao.class)
@Timeout(90)
class RouteSearchKakaoPostgresTest extends RouteSearchPostgresSupport {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Test void theTimetableRouterIsOff() {
        assertThat(timetableRoutes).isNull();
        assertThat(context.getBeanProvider(com.mju.linkro.subway.api.TimetableRouteFinder.class).getIfAvailable()).isNull();
    }

    @Test void kakaoSubwayRouteWithOurCongestion(CapturedOutput output) throws Exception {
        server.expect(ExpectedCount.once(), requestTo(startsWith(URL))).andRespond(withSuccess(KAKAO_OK, MediaType.APPLICATION_JSON));

        JsonNode data = ok(search(gangnam, sadang, "2026-11-06T08:00:00+09:00", null), member);

        server.verify();
        assertThat(data.get("routeSource").asString()).isEqualTo("KAKAO");
        JsonNode route = data.get("routes").get(0);
        assertThat(data.get("routes").size()).isEqualTo(1);
        assertThat(route.get("routeId").asString()).isEqualTo("r1");
        assertThat(route.get("routeType").asString()).isEqualTo("FASTEST");
        JsonNode summary = route.get("summary");
        assertThat(summary.get("totalTimeSec").asInt()).isEqualTo(600);
        assertThat(summary.get("arrivalAt").asString()).isEqualTo("2026-11-06T08:10:00+09:00");
        assertThat(summary.get("fare").asInt()).isEqualTo(1550);
        assertThat(summary.get("fareSource").asString()).isEqualTo("KAKAO");
        assertThat(summary.get("transferCount").asInt()).isZero();
        assertThat(summary.get("walkingMinutes").asInt()).isZero();
        // Summary: the stop with the highest raw value (강남 92.5 -> 93).
        assertCongestion(summary.get("congestion"), 93, "NORMAL", "PREDICTED");

        JsonNode step = route.get("steps").get(0);
        assertThat(step.get("type").asString()).isEqualTo("SUBWAY");
        assertThat(step.get("guidance").asString()).isEqualTo("2호선 탑승, 4개 역 이동 후 사당 하차");
        assertThat(step.get("line").get("id").asLong()).isEqualTo(line2);
        assertThat(step.get("line").get("color").asString()).isEqualTo("#00A84D");
        assertThat(step.get("direction").isNull()).isTrue();
        assertThat(step.get("directionCode").asString()).isEqualTo("INNER");
        assertThat(names(step.get("stops"))).containsExactly("강남", "교대", "서초", "방배", "사당");
        assertThat(step.get("stops").get(4).get("order").asInt()).isEqualTo(5);
        assertThat(step.get("path").size()).isEqualTo(3);
        JsonNode stations = step.get("stations");
        assertThat(stations.size()).isEqualTo(5);
        assertThat(stations.get(0).get("slotStart").asString()).isEqualTo("08:00");
        assertThat(stations.get(0).get("slotDate").asString()).isEqualTo("2026-11-06");
        assertCongestion(stations.get(0).get("congestion"), 93, "NORMAL", "PREDICTED");
        // 34.95326485813263 COMFORTABLE is shown as 34 by CongestionResolver.display.
        assertCongestion(stations.get(1).get("congestion"), 34, "COMFORTABLE", "PREDICTED");
        assertThat(stations.get(2).get("congestion").get("supported").asBoolean()).isFalse();
        assertThat(stations.get(2).get("congestion").get("pct").isNull()).isTrue();

        assertThat(output.getAll()).contains("attempt 1: 200").doesNotContain(KEY).doesNotContain("BODY-MARKER");
    }

    /** 1호선 since V14 (#89): 청량리 방면 is UP (Seoul 상행) and 서울역 방면 DOWN, with the matching congestion. */
    @Test void line1DirectionCodeFollowsSeoul() throws Exception {
        stat("종각", "1", "UP", "08:00", "38.412", "RELAXED", "여유");    // 상선 = 청량리 방면
        stat("종각", "1", "DOWN", "08:00", "48.448", "RELAXED", "여유");  // 하선 = 서울역 방면
        long jonggak = jdbc.queryForObject("select id from stations where name = '종각'", Long.class);
        long cityHall = jdbc.queryForObject("select id from stations where name = '시청'", Long.class);
        long jongno3 = jdbc.queryForObject("select id from stations where name = '종로3가'", Long.class);
        useKakao("", Duration.ofSeconds(3), Duration.ofSeconds(5));

        JsonNode toSeoul = ok(search(jonggak, cityHall, "2026-11-06T08:00:00+09:00", null), member).get("routes").get(0).get("steps").get(0);
        assertThat(toSeoul.get("directionCode").asString()).isEqualTo("DOWN");
        assertCongestion(toSeoul.get("stations").get(0).get("congestion"), 48, "RELAXED", "PREDICTED");

        JsonNode toCheongnyangni = ok(search(jonggak, jongno3, "2026-11-06T08:00:00+09:00", null), member).get("routes").get(0).get("steps").get(0);
        assertThat(toCheongnyangni.get("directionCode").asString()).isEqualTo("UP");
        assertCongestion(toCheongnyangni.get("stations").get(0).get("congestion"), 38, "RELAXED", "PREDICTED");

        // Kakao routes take the direction from the same edges.
        useKakao(KEY, Duration.ofSeconds(3), Duration.ofSeconds(5));
        server.expect(ExpectedCount.once(), requestTo(startsWith(URL))).andRespond(withSuccess("""
                {"status":"OK","routes":[{"properties":{"type":"SUBWAY","totalDistance":1000,"totalTime":120,"transfers":0,"fare":{"value":1550}},
                 "steps":[{"properties":{"type":"SUBWAY","distance":1000,"time":120,"stops":[{"name":"종각"},{"name":"시청"}],
                 "vehicles":[{"name":"1호선","type":"일반"}]}}]}]}
                """, MediaType.APPLICATION_JSON));
        JsonNode kakao = ok(search(jonggak, cityHall, "2026-11-06T08:00:00+09:00", null), member);
        assertThat(kakao.get("routeSource").asString()).isEqualTo("KAKAO");
        assertThat(kakao.get("routes").get(0).get("steps").get(0).get("directionCode").asString()).isEqualTo("DOWN");
        server.verify();
    }

    @Test void timeoutFallsBackToInternal() throws Exception {
        useKakao(KEY, Duration.ofMillis(200), Duration.ofMillis(450));
        server.expect(ExpectedCount.twice(), requestTo(startsWith(URL))).andRespond(request -> {
            try {
                Thread.sleep(1_000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            return withSuccess(KAKAO_OK, MediaType.APPLICATION_JSON).createResponse(request);
        });

        assertInternal(ok(search(gangnam, sadang, "2026-11-06T08:00:00+09:00", null), member));
    }

    @Test void clientErrorFallsBackWithoutRetry() throws Exception {
        server.expect(ExpectedCount.once(), requestTo(startsWith(URL))).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
        assertInternal(ok(search(gangnam, sadang, "2026-11-06T08:00:00+09:00", null), member));
        server.verify();
    }

    @Test void keyErrorStartsACooldownWithoutFurtherCalls(CapturedOutput output) throws Exception {
        server.expect(ExpectedCount.once(), requestTo(startsWith(URL))).andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        assertInternal(ok(search(gangnam, sadang, "2026-11-06T08:00:00+09:00", null), member));
        // The next requests fall back at once: the stub has no further expectation, so any call would fail.
        assertInternal(ok(search(gangnam, sadang, "2026-11-06T08:00:00+09:00", null), member));
        assertInternal(ok(search(gangnam, sadang, "2026-11-06T08:00:00+09:00", null), member));
        server.verify();
        assertThat(output.getAll().lines().filter(line -> line.contains(" ERROR ") && line.contains("Kakao rejected the key"))).hasSize(1);
        assertThat(output.getAll().lines().filter(line -> line.contains("uses INTERNAL: COOLDOWN"))).hasSize(2)
                .allSatisfy(line -> assertThat(line).contains(" INFO "));
        assertThat(output.getAll()).doesNotContain(KEY);
    }

    @Test void emptyResultFallsBack() throws Exception {
        server.expect(ExpectedCount.once(), requestTo(startsWith(URL)))
                .andRespond(withSuccess("{\"status\":\"OK\",\"routes\":[]}", MediaType.APPLICATION_JSON));
        assertInternal(ok(search(gangnam, sadang, "2026-11-06T08:00:00+09:00", null), member));
        server.verify();
    }

    @Test void withoutKeyKakaoIsNeverCalled() throws Exception {
        useKakao("", Duration.ofSeconds(3), Duration.ofSeconds(5));
        assertInternal(ok(search(gangnam, sadang, "2026-11-06T08:00:00+09:00", null), member));
        server.verify(); // no expectations: any request would have failed
    }

}
