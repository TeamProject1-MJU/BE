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
 * POST /routes/search on PostgreSQL (V1~V13 seed, graph module) with Kakao stubbed by MockRestServiceServer.
 * The stub bodies are hand-written in the shape of docs/kakao-route-analysis.md; no real Kakao response is used.
 */
@Testcontainers
@ExtendWith(OutputCaptureExtension.class)
@SpringBootTest(properties = {
        "spring.config.import=", "spring.jpa.hibernate.ddl-auto=validate", "spring.jpa.open-in-view=false",
        "spring.jpa.show-sql=false", "auth.kakao.app-id=1234", "auth.jwt.access-token-ttl=15m",
        "auth.jwt.secret=dGVzdC1vbmx5LXNlY3JldC0wMTIzNDU2Nzg5YWJjZGVmZ2hpams=", "linkro.subway.module=graph"})
@Timeout(90)
class RouteSearchPostgresTest {
    static final String KEY = "test-route-key-0123456789";
    static final String URL = "https://dapi.kakao.com/v2/routing/publictraffic";

    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    /** Lets each test pick the Kakao client (stubbed key, no key, short timeouts). */
    static class SwitchableKakaoRouteClient extends KakaoRouteClient {
        KakaoRouteClient target;

        SwitchableKakaoRouteClient() {
            super(RestClient.create(), "", Duration.ofSeconds(3), Duration.ofSeconds(5), Duration.ZERO, Duration.ofMinutes(5), Duration.ofSeconds(30), System::nanoTime, java.time.Clock.systemUTC());
        }

        @Override public boolean enabled() { return target.enabled(); }

        @Override public Result search(double startX, double startY, double endX, double endY) {
            return target.search(startX, startY, endX, endY);
        }
    }

    @TestConfiguration
    static class StubKakao {
        @Bean @Primary SwitchableKakaoRouteClient stubKakaoRouteClient() { return new SwitchableKakaoRouteClient(); }
    }

    @Autowired WebApplicationContext context;
    @Autowired JwtProvider jwt;
    @Autowired UserRepository users;
    @Autowired JdbcTemplate jdbc;
    @Autowired SwitchableKakaoRouteClient kakao;
    @Autowired com.mju.linkro.recommendation.service.RecommendationEngine engine;
    @Autowired com.mju.linkro.subway.api.StationFareLookup fareTable;
    private final JsonMapper mapper = JsonMapper.builder().build();
    MockMvc mvc;
    MockRestServiceServer server;
    UUID member;
    UUID stranger;
    long gangnam;
    long sadang;
    long line2;

    @BeforeEach void setup() {
        jdbc.update("delete from meeting_members");
        jdbc.update("delete from meeting_rooms");
        jdbc.update("delete from users");
        jdbc.update("delete from congestion_stats");
        member = users.saveAndFlush(new User("route-member", "경로")).getId();
        stranger = users.saveAndFlush(new User("route-stranger", "외부")).getId();
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        gangnam = jdbc.queryForObject("select id from stations where name = '강남'", Long.class);
        sadang = jdbc.queryForObject("select id from stations where name = '사당'", Long.class);
        line2 = jdbc.queryForObject("select id from subway_lines where line_code = '2'", Long.class);
        stat("강남", "2", "INNER", "08:00", "92.5", "NORMAL", "보통");
        stat("교대", "2", "INNER", "08:00", "34.95326485813263", "COMFORTABLE", "쾌적");
        useKakao(KEY, Duration.ofSeconds(3), Duration.ofSeconds(5));
    }

    private void useKakao(String key, Duration call, Duration total) {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://dapi.kakao.com");
        server = MockRestServiceServer.bindTo(builder).build();
        kakao.target = new KakaoRouteClient(builder.build(), key, call, total, Duration.ofMillis(10), Duration.ofMinutes(5), Duration.ofSeconds(30), System::nanoTime, java.time.Clock.systemUTC());
    }

    /** One BUS route, one 9호선 route and the 2호선 강남 -> 사당 route; only the last one can be used. */
    static final String KAKAO_OK = """
            {"status":"OK","properties":{"total":3,"bus":1,"subway":2,"busAndSubway":0},"routes":[
              {"properties":{"type":"BUS","totalDistance":7000,"totalTime":500,"transfers":0,"fare":{"min":1500,"max":1500}},
               "steps":[{"properties":{"type":"BUS","guidance":"bus","distance":7000,"time":500,"stops":[],"vehicles":[]}}]},
              {"properties":{"type":"SUBWAY","totalDistance":9000,"totalTime":550,"transfers":0,"fare":{"value":1650}},
               "steps":[{"properties":{"type":"SUBWAY","guidance":"BODY-MARKER","distance":9000,"time":550,
                 "stops":[{"name":"신논현"},{"name":"사평"}],"vehicles":[{"name":"9호선","type":"일반"}]}}]},
              {"properties":{"type":"SUBWAY","totalDistance":7300,"totalTime":600,"transfers":0,"fare":{"value":1550}},
               "steps":[{"properties":{"type":"SUBWAY","guidance":"2호선 (강남 > 사당)","distance":7300,"time":600,
                 "stops":[{"name":"강남"},{"name":"교대(법원.검찰청)"},{"name":"서초"},{"name":"방배"},{"name":"사당"}],
                 "vehicles":[{"name":"2호선","type":"일반"}]},
                 "path":{"points":[[127.0275,37.4979],[127.0137,37.4934],[126.9816,37.4765]]}}]}]}
            """;

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

    @Test void internalFaresAreTheFareTableFaresRecommendationsUse() throws Exception {
        // #109: /routes/search (INTERNAL, no Kakao key) and recommendations charge the same fare-table fare for a pair.
        useKakao("", Duration.ofSeconds(3), Duration.ofSeconds(5));
        long bulgwang = id("불광");
        long hapjeong = id("합정");
        long nowon = id("노원");
        long sadang = id("사당");
        var members = List.of(new com.mju.linkro.recommendation.service.Candidate.Member(UUID.randomUUID(), "a", bulgwang),
                new com.mju.linkro.recommendation.service.Candidate.Member(UUID.randomUUID(), "b", nowon));
        var evaluation = engine.evaluate(members, com.mju.linkro.recommendation.dto.RecommendationResponse.AppliedConstraints.none(),
                java.time.Instant.parse("2026-11-12T04:00:00Z"), java.time.Instant.parse("2026-11-12T03:00:00Z"));
        int recommendedToHapjeong = evaluation.candidates().get(hapjeong).trips().get(0).fareWon();
        int recommendedToSadang = evaluation.candidates().get(sadang).trips().get(1).fareWon();
        JsonNode toHapjeong = ok(search(bulgwang, hapjeong, "2026-11-12T13:00:00+09:00", null), member);
        JsonNode toSadang = ok(search(nowon, sadang, "2026-11-12T13:00:00+09:00", null), member);
        assertThat(toHapjeong.get("routeSource").asString()).isEqualTo("INTERNAL");
        assertThat(toSadang.get("routeSource").asString()).isEqualTo("INTERNAL");
        assertThat(toHapjeong.get("routes").get(0).get("summary").get("fare").asInt()).isEqualTo(recommendedToHapjeong).isEqualTo(1550);
        assertThat(toSadang.get("routes").get(0).get("summary").get("fare").asInt()).isEqualTo(recommendedToSadang).isEqualTo(1850);
        assertThat(fareTable.fareBetween(bulgwang, hapjeong)).hasValue(1550);
        server.verify();
    }

    private long id(String name) {
        return jdbc.queryForObject("select id from stations where name = ?", Long.class, name);
    }

    @Test void roomMembersUseTheAppointmentAndOthersAreRejected() throws Exception {
        // 2027-01-05 (Tuesday) 08:30 in Seoul.
        String room = createRoom(member, "2027-01-04T23:30:00Z");
        server.expect(ExpectedCount.once(), requestTo(startsWith(URL))).andRespond(withSuccess(KAKAO_OK, MediaType.APPLICATION_JSON));

        JsonNode data = ok(search(gangnam, sadang, null, room), member);

        // Departure = 08:30 - 600 s = 08:20, so every stop is in the 08:00 slot of that Tuesday; arrival is the appointment.
        JsonNode route = data.get("routes").get(0);
        assertThat(route.get("summary").get("arrivalAt").asString()).isEqualTo("2027-01-05T08:30:00+09:00");
        JsonNode first = route.get("steps").get(0).get("stations").get(0);
        assertThat(first.get("slotDate").asString()).isEqualTo("2027-01-05");
        assertThat(first.get("slotStart").asString()).isEqualTo("08:00");
        assertCongestion(first.get("congestion"), 93, "NORMAL", "PREDICTED");

        failure(search(gangnam, sadang, null, room), stranger, 403, "NOT_ROOM_MEMBER");
        failure(search(gangnam, sadang, null, UUID.randomUUID().toString()), member, 404, "ROOM_NOT_FOUND");
        failure(search(gangnam, sadang, null, "not-a-uuid"), member, 400, "VALIDATION_ERROR");
    }

    @Test void stationsAreCheckedBeforeKakao() throws Exception {
        failure(search(999_999, sadang, null, null), member, 404, "STATION_NOT_FOUND");
        jdbc.update("update stations set is_supported = false where id = ?", sadang);
        try {
            failure(search(gangnam, sadang, null, null), member, 422, "STATION_UNSUPPORTED");
        } finally {
            jdbc.update("update stations set is_supported = true where id = ?", sadang);
        }
        failure(post("/api/v1/routes/search").contentType(MediaType.APPLICATION_JSON).content("{}"), member, 400, "VALIDATION_ERROR");
        server.verify(); // none of these reached Kakao
    }

    @Test void authenticationIsRequired() throws Exception {
        String body = mvc.perform(search(gangnam, sadang, null, null)).andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(mapper.readTree(body).get("error").get("code").asString()).isEqualTo("AUTH_REQUIRED");
    }

    @Test void openApiDocumentsTheEndpoint() throws Exception {
        JsonNode root = mapper.readTree(mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/v3/api-docs"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        JsonNode operation = root.get("paths").get("/api/v1/routes/search").get("post");
        assertThat(operation.get("security").get(0).has("bearerAuth")).isTrue();
        List<String> codes = new ArrayList<>();
        operation.get("responses").propertyNames().forEach(codes::add);
        assertThat(codes).contains("200", "400", "401", "403", "404", "422");
        assertThat(root.get("components").get("schemas").get("RouteSearchData").toString()).contains("KAKAO", "INTERNAL");
        assertThat(root.get("components").get("schemas").get("RouteSearchData").toString()).contains("appointmentPassed", "notice");
        assertThat(root.get("components").get("schemas").get("RouteSummaryData").toString())
                .contains("departureAt", "noService", "serviceNotice");
    }

    // ---- #120: no service on the real timetable (2026-11-12 Thursday, 2026-11-14 Saturday) ----

    /** 강남 -> 사당 on 2호선 (Kakao fixture and INTERNAL): first trains from 강남 are 05:36:00 weekdays, 05:35:30 Saturdays. */
    @Test void noServiceTimesMoveToTheFirstTrainOnBothRouteSources() throws Exception {
        server.expect(ExpectedCount.manyTimes(), requestTo(startsWith(URL))).andRespond(withSuccess(KAKAO_OK, MediaType.APPLICATION_JSON));
        String[][] cases = {
                {"2026-11-12T03:00:00+09:00", "2026-11-12T05:36:00+09:00", "11월 12일 05:36"},
                {"2026-11-12T05:00:00+09:00", "2026-11-12T05:36:00+09:00", "11월 12일 05:36"},
                // Saturday 01:30 is after Friday's last train: Saturday's first train.
                {"2026-11-14T01:30:00+09:00", "2026-11-14T05:35:30+09:00", "11월 14일 05:35"}};
        for (String[] c : cases) {
            JsonNode data = ok(search(gangnam, sadang, c[0], null), member);
            assertThat(data.get("routeSource").asString()).isEqualTo("KAKAO");
            assertNoService(data, c[1], c[2]);
        }
        useKakao("", Duration.ofSeconds(3), Duration.ofSeconds(5));
        for (String[] c : cases) {
            JsonNode data = ok(search(gangnam, sadang, c[0], null), member);
            assertThat(data.get("routeSource").asString()).isEqualTo("INTERNAL");
            assertNoService(data, c[1], c[2]);
        }
    }

    private static void assertNoService(JsonNode data, String departureAt, String firstTrain) {
        JsonNode summary = data.get("routes").get(0).get("summary");
        assertThat(summary.get("noService").asBoolean()).as(departureAt).isTrue();
        assertThat(summary.get("departureAt").asString()).isEqualTo(departureAt);
        assertThat(OffsetDateTime.parse(summary.get("arrivalAt").asString()))
                .isEqualTo(OffsetDateTime.parse(departureAt).plusSeconds(summary.get("totalTimeSec").asInt()));
        assertThat(summary.get("serviceNotice").asString()).contains("첫차(" + firstTrain + " 출발)");
        assertThat(data.get("appointmentPassed").asBoolean()).isFalse();
        assertThat(data.get("notice").isNull()).isTrue();
    }

    @Test void runningHoursKeepTheRequestedDeparture() throws Exception {
        server.expect(ExpectedCount.manyTimes(), requestTo(startsWith(URL))).andRespond(withSuccess(KAKAO_OK, MediaType.APPLICATION_JSON));
        for (boolean internal : new boolean[] {false, true}) {
            if (internal) useKakao("", Duration.ofSeconds(3), Duration.ofSeconds(5));
            for (String departAt : List.of("2026-11-12T14:00:00+09:00", "2026-11-12T23:30:00+09:00")) {
                JsonNode data = ok(search(gangnam, sadang, departAt, null), member);
                JsonNode summary = data.get("routes").get(0).get("summary");
                assertThat(summary.get("noService").asBoolean()).as(departAt).isFalse();
                assertThat(summary.get("serviceNotice").isNull()).isTrue();
                assertThat(summary.get("departureAt").asString()).isEqualTo(departAt);
                assertThat(OffsetDateTime.parse(summary.get("arrivalAt").asString()))
                        .isEqualTo(OffsetDateTime.parse(departAt).plusSeconds(summary.get("totalTimeSec").asInt()));
                assertThat(data.get("appointmentPassed").asBoolean()).isFalse();
                assertThat(data.get("notice").isNull()).isTrue();
            }
        }
    }

    @Test void aPassedAppointmentIsReportedAndDepartAtIgnored() throws Exception {
        String room = createRoom(member, "2020-01-01T00:00:00Z");
        server.expect(ExpectedCount.once(), requestTo(startsWith(URL))).andRespond(withSuccess(KAKAO_OK, MediaType.APPLICATION_JSON));

        JsonNode data = ok(search(gangnam, sadang, "2026-11-12T14:00:00+09:00", room), member);

        assertThat(data.get("appointmentPassed").asBoolean()).isTrue();
        assertThat(data.get("notice").asString()).isEqualTo("약속 시각이 이미 지나 지금 출발 기준으로 안내합니다.");
        assertThat(data.get("routes").get(0).get("summary").get("departureAt").asString()).isNotEqualTo("2026-11-12T14:00:00+09:00");
    }

    // ---- helpers ----

    private void assertInternal(JsonNode data) {
        assertThat(data.get("routeSource").asString()).isEqualTo("INTERNAL");
        assertThat(data.get("routes").size()).isEqualTo(1);
        JsonNode route = data.get("routes").get(0);
        assertThat(route.get("summary").get("fareSource").asString()).isEqualTo("ESTIMATED");
        assertThat(route.get("summary").get("fare").asInt()).isPositive();
        JsonNode step = route.get("steps").get(0);
        assertThat(step.get("type").asString()).isEqualTo("SUBWAY");
        assertThat(step.get("directionCode").asString()).isEqualTo("INNER");
        assertThat(names(step.get("stops"))).startsWith("강남").endsWith("사당");
        assertCongestion(step.get("stations").get(0).get("congestion"), 93, "NORMAL", "PREDICTED");
    }

    private static void assertCongestion(JsonNode congestion, int pct, String level, String kind) {
        assertThat(congestion.get("supported").asBoolean()).isTrue();
        assertThat(congestion.get("pct").isIntegralNumber()).isTrue();
        assertThat(congestion.get("pct").asInt()).isEqualTo(pct);
        assertThat(congestion.get("level").asString()).isEqualTo(level);
        assertThat(congestion.get("kind").asString()).isEqualTo(kind);
    }

    private static List<String> names(JsonNode stops) {
        List<String> names = new ArrayList<>();
        stops.forEach(stop -> names.add(stop.get("name").asString()));
        return names;
    }

    private MockHttpServletRequestBuilder search(long origin, long destination, String departAt, String roomId) {
        StringBuilder body = new StringBuilder("{\"originStationId\":" + origin + ",\"destinationStationId\":" + destination);
        if (departAt != null) body.append(",\"departAt\":\"").append(departAt).append('"');
        if (roomId != null) body.append(",\"roomId\":\"").append(roomId).append('"');
        return post("/api/v1/routes/search").contentType(MediaType.APPLICATION_JSON).content(body.append('}').toString());
    }

    private JsonNode ok(MockHttpServletRequestBuilder request, UUID user) throws Exception {
        String body = mvc.perform(auth(request, user)).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        JsonNode root = mapper.readTree(body);
        assertThat(root.get("success").asBoolean()).isTrue();
        return root.get("data");
    }

    private void failure(MockHttpServletRequestBuilder request, UUID user, int status, String code) throws Exception {
        String body = mvc.perform(auth(request, user)).andExpect(status().is(status)).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        assertThat(mapper.readTree(body).get("error").get("code").asString()).as(body).isEqualTo(code);
    }

    private MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder request, UUID user) {
        return request.header("Authorization", "Bearer " + jwt.createAccessToken(user));
    }

    private String createRoom(UUID owner, String appointmentAt) throws Exception {
        String body = mvc.perform(auth(post("/api/v1/rooms").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"경로 테스트\",\"appointmentAt\":\"" + appointmentAt + "\"}"), owner))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return mapper.readTree(body).get("data").get("id").asString();
    }

    private void stat(String station, String line, String direction, String slot, String pct, String level, String label) {
        jdbc.update("""
                insert into congestion_stats(station_line_id, direction, day_type, slot_start, congestion_pct, congestion_level,
                                             congestion_level_label, source, model_version)
                select sl.id, ?, 'WEEKDAY', ?::time, ?::numeric, ?, ?, 'PREDICTED', 'HGB_TEST'
                from station_lines sl join stations s on s.id = sl.station_id join subway_lines l on l.id = sl.line_id
                where s.name = ? and l.line_code = ?
                """, direction, slot, pct, level, label, station, line);
    }
}
