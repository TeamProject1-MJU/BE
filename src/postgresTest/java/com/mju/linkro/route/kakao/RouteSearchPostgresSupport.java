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
 * Shared set-up of the POST /routes/search PostgreSQL tests (V1~V13 seed, graph module) with Kakao stubbed by
 * MockRestServiceServer: {@link RouteSearchPostgresTest} with the train timetable (room appointment and solo modes) and
 * {@link RouteSearchKakaoPostgresTest} without it (Kakao and fixed-cost routes). Each subclass declares its own
 * container and imports {@link StubKakao}. The stub bodies are hand-written in the shape of
 * docs/kakao-route-analysis.md; no real Kakao response is used.
 */
abstract class RouteSearchPostgresSupport {
    static final String KEY = "test-route-key-0123456789";
    static final String URL = "https://dapi.kakao.com/v2/routing/publictraffic";

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
    /** Null when the timetable is off (RouteSearchKakaoPostgresTest). */
    @Autowired(required = false) com.mju.linkro.subway.api.TimetableRouteFinder timetableRoutes;
    final JsonMapper mapper = JsonMapper.builder().build();
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

    void useKakao(String key, Duration call, Duration total) {
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

    long id(String name) {
        return jdbc.queryForObject("select id from stations where name = ?", Long.class, name);
    }

    static java.time.Instant instant(JsonNode offsetTime) {
        return OffsetDateTime.parse(offsetTime.asString()).toInstant();
    }

    static List<Long> responseStations(JsonNode route) {
        List<Long> ids = new ArrayList<>();
        route.get("steps").forEach(step -> step.get("stops").forEach(stop -> {
            long id = stop.get("id").asLong();
            if (ids.isEmpty() || ids.get(ids.size() - 1) != id) ids.add(id);
        }));
        return ids;
    }

    static List<Long> pathStations(com.mju.linkro.subway.api.RoutePath path) {
        List<Long> ids = new ArrayList<>();
        path.stops().forEach(stop -> { if (ids.isEmpty() || ids.get(ids.size() - 1) != stop.stationId()) ids.add(stop.stationId()); });
        return ids;
    }

    // ---- helpers ----

    void assertInternal(JsonNode data) {
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

    static void assertCongestion(JsonNode congestion, int pct, String level, String kind) {
        assertThat(congestion.get("supported").asBoolean()).isTrue();
        assertThat(congestion.get("pct").isIntegralNumber()).isTrue();
        assertThat(congestion.get("pct").asInt()).isEqualTo(pct);
        assertThat(congestion.get("level").asString()).isEqualTo(level);
        assertThat(congestion.get("kind").asString()).isEqualTo(kind);
    }

    static List<String> names(JsonNode stops) {
        List<String> names = new ArrayList<>();
        stops.forEach(stop -> names.add(stop.get("name").asString()));
        return names;
    }

    MockHttpServletRequestBuilder search(long origin, long destination, String departAt, String roomId) {
        StringBuilder body = new StringBuilder("{\"originStationId\":" + origin + ",\"destinationStationId\":" + destination);
        if (departAt != null) body.append(",\"departAt\":\"").append(departAt).append('"');
        if (roomId != null) body.append(",\"roomId\":\"").append(roomId).append('"');
        return post("/api/v1/routes/search").contentType(MediaType.APPLICATION_JSON).content(body.append('}').toString());
    }

    JsonNode ok(MockHttpServletRequestBuilder request, UUID user) throws Exception {
        String body = mvc.perform(auth(request, user)).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        JsonNode root = mapper.readTree(body);
        assertThat(root.get("success").asBoolean()).isTrue();
        return root.get("data");
    }

    void failure(MockHttpServletRequestBuilder request, UUID user, int status, String code) throws Exception {
        String body = mvc.perform(auth(request, user)).andExpect(status().is(status)).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        assertThat(mapper.readTree(body).get("error").get("code").asString()).as(body).isEqualTo(code);
    }

    MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder request, UUID user) {
        return request.header("Authorization", "Bearer " + jwt.createAccessToken(user));
    }

    String createRoom(UUID owner, String appointmentAt) throws Exception {
        String body = mvc.perform(auth(post("/api/v1/rooms").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"경로 테스트\",\"appointmentAt\":\"" + appointmentAt + "\"}"), owner))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return mapper.readTree(body).get("data").get("id").asString();
    }

    void stat(String station, String line, String direction, String slot, String pct, String level, String label) {
        jdbc.update("""
                insert into congestion_stats(station_line_id, direction, day_type, slot_start, congestion_pct, congestion_level,
                                             congestion_level_label, source, model_version)
                select sl.id, ?, 'WEEKDAY', ?::time, ?::numeric, ?, ?, 'PREDICTED', 'HGB_TEST'
                from station_lines sl join stations s on s.id = sl.station_id join subway_lines l on l.id = sl.line_id
                where s.name = ? and l.line_code = ?
                """, direction, slot, pct, level, label, station, line);
    }
}
