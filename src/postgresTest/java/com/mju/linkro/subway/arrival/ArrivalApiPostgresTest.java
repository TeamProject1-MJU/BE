package com.mju.linkro.subway.arrival;

import com.mju.linkro.auth.jwt.JwtProvider;
import com.mju.linkro.user.domain.User;
import com.mju.linkro.user.repository.UserRepository;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
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
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * GET /stations/{id}/arrivals on PostgreSQL (V1~V13 seed) with the Seoul API stubbed by MockRestServiceServer and a
 * fake key. Also pins the seed facts the rules rely on (names, 2호선 codes, 7호선 edge direction).
 */
@Testcontainers
@ExtendWith(OutputCaptureExtension.class)
@SpringBootTest(properties = {
        "spring.config.import=", "spring.jpa.hibernate.ddl-auto=validate", "spring.jpa.open-in-view=false",
        "spring.jpa.show-sql=false", "auth.kakao.app-id=1234", "auth.jwt.access-token-ttl=15m",
        "auth.jwt.secret=dGVzdC1vbmx5LXNlY3JldC0wMTIzNDU2Nzg5YWJjZGVmZ2hpams="})
@Timeout(60)
class ArrivalApiPostgresTest {
    static final String KEY = "FAKE+KEY/test=";
    static final String BASE = "http://swopenAPI.seoul.go.kr";
    static final DateTimeFormatter RECEIVED = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    static final RestClient.Builder BUILDER = RestClient.builder();
    static final MockRestServiceServer SERVER = MockRestServiceServer.bindTo(BUILDER).ignoreExpectOrder(true).build();

    @TestConfiguration
    static class StubSeoul {
        @Bean @Primary SeoulArrivalClient stubSeoulArrivalClient() {
            return new SeoulArrivalClient(BUILDER.build(), BASE, KEY, Clock.systemUTC());
        }
    }

    @Autowired WebApplicationContext context;
    @Autowired JwtProvider jwt;
    @Autowired UserRepository users;
    @Autowired JdbcTemplate jdbc;
    private final JsonMapper mapper = JsonMapper.builder().build();
    MockMvc mvc;
    UUID user;

    @BeforeEach void setup() {
        jdbc.update("delete from users");
        user = users.saveAndFlush(new User("arrival-user", "도착")).getId();
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        SERVER.reset();
    }

    private long station(String name) {
        return jdbc.queryForObject("select id from stations where name = ?", Long.class, name);
    }

    private long line(String code) {
        return jdbc.queryForObject("select id from subway_lines where line_code = ?", Long.class, code);
    }

    @Test void seedFactsTheRulesRelyOn() {
        // Our names are not the official query names, so all five exceptions are needed.
        assertThat(jdbc.queryForList("select name from stations where name in ('응암','새절','증산','월드컵경기장','어린이대공원') ", String.class))
                .containsExactlyInAnyOrder("새절", "어린이대공원", "월드컵경기장", "응암", "증산");
        assertThat(jdbc.queryForObject("select count(*) from stations where name like '%(%' and name <> '총신대입구(이수)'", Long.class)).isZero();
        // 2호선 codes are 3 digits (external id 100 2 000 code); 5~8호선 codes are 4 digits.
        assertThat(jdbc.queryForObject("select count(*) from station_lines sl join subway_lines l on l.id = sl.line_id "
                + "where l.line_code = '2' and sl.station_code !~ '^[0-9]{3}$'", Long.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from station_lines sl join subway_lines l on l.id = sl.line_id "
                + "where l.line_code in ('5','6','7','8') and sl.station_code !~ '^[0-9]{4}$'", Long.class)).isZero();
        // 7호선 건대입구 -> 어린이대공원 is UP (towards 장암), used when the external id cannot be built.
        assertThat(jdbc.queryForObject("""
                select c.direction from station_connections c
                join station_lines a on a.id = c.from_station_line_id join stations sa on sa.id = a.station_id
                join station_lines b on b.id = c.to_station_line_id join stations sb on sb.id = b.station_id
                join subway_lines l on l.id = a.line_id
                where c.connection_type = 'RIDE' and l.line_code = '7' and sa.name = '건대입구' and sb.name = '어린이대공원'
                """, String.class)).isEqualTo("UP");
    }

    /** Two 7호선 rows at 건대입구 received just now: 상행 towards 어린이대공원 and 하행. */
    private static String konkuk7() {
        String now = LocalDateTime.now(ZoneId.of("Asia/Seoul")).format(RECEIVED);
        return """
                {"errorMessage":{"status":200,"code":"INFO-000","message":"정상 처리되었습니다.","total":3},"realtimeArrivalList":[
                 {"subwayId":"1007","updnLine":"상행","trainLineNm":"장암행 - 어린이대공원(세종대)방면","bstatnNm":"장암","statnId":"1007000727",
                  "statnFid":"1007000728","statnTid":"1007000726","barvlDt":"170","arvlCd":"99","arvlMsg2":"2분 50초 후","arvlMsg3":"자양",
                  "recptnDt":"%1$s","lstcarAt":"0","btrainSttus":"일반","btrainNo":"7001"},
                 {"subwayId":"1007","updnLine":"하행","trainLineNm":"석남행 - 자양(뚝섬한강공원)방면","bstatnNm":"석남","statnId":"1007000727",
                  "statnFid":"1007000726","statnTid":"1007000728","barvlDt":"60","arvlCd":"99","arvlMsg2":"1분 후","arvlMsg3":"어린이대공원(세종대)",
                  "recptnDt":"%1$s","lstcarAt":"0","btrainSttus":"일반","btrainNo":"7002"},
                 {"subwayId":"1002","updnLine":"내선","trainLineNm":"성수행 - 성수방면","bstatnNm":"성수","statnId":"1002000212",
                  "statnFid":"1002000213","statnTid":"1002000211","barvlDt":"30","arvlCd":"99","arvlMsg2":"30초 후","arvlMsg3":"구의",
                  "recptnDt":"%1$s","lstcarAt":"0","btrainSttus":"일반","btrainNo":"2001"}]}
                """.formatted(now);
    }

    @Test void nextTrainsOfOneLineAndDirection(CapturedOutput output) throws Exception {
        SERVER.expect(ExpectedCount.once(), requestTo(containsString("/0/30/%EA%B1%B4%EB%8C%80%EC%9E%85%EA%B5%AC")))
                .andRespond(withSuccess(konkuk7(), MediaType.APPLICATION_JSON));

        JsonNode data = ok(get("/api/v1/stations/" + station("건대입구") + "/arrivals")
                .param("lineId", String.valueOf(line("7"))).param("nextStationId", String.valueOf(station("어린이대공원"))));

        SERVER.verify();
        assertThat(data.get("available").asBoolean()).isTrue();
        assertThat(data.get("reason").isNull()).isTrue();
        assertThat(data.get("receivedAt").asString()).endsWith("+09:00");
        assertThat(data.get("trains").size()).isEqualTo(1);
        JsonNode train = data.get("trains").get(0);
        assertThat(train.get("destinationName").asString()).isEqualTo("장암");
        assertThat(train.get("directionCode").asString()).isEqualTo("UP");
        assertThat(train.get("secondsUntil").asInt()).isBetween(160, 170);
        assertThat(train.get("message").asString()).isEqualTo("2분 50초 후");
        assertThat(train.get("lastTrain").asBoolean()).isFalse();
        List<String> fields = new ArrayList<>();
        train.propertyNames().forEach(fields::add);
        assertThat(fields).containsExactly("destinationName", "directionCode", "secondsUntil", "stopsAway", "message", "lastTrain", "express");
        assertThat(train.get("stopsAway").isNull()).isTrue();
        assertThat(train.get("express").asBoolean()).isFalse();
        assertThat(output.getAll()).doesNotContain("FAKE").doesNotContain("/api/subway/");
    }

    @Test void unsupportedLinesAnswer200() throws Exception {
        // 999 is no line; 8호선 does not stop at 건대입구.
        for (String lineId : new String[] {"999", String.valueOf(line("8"))}) {
            JsonNode data = ok(get("/api/v1/stations/" + station("건대입구") + "/arrivals").param("lineId", lineId));
            assertThat(data.get("available").asBoolean()).isFalse();
            assertThat(data.get("reason").asString()).isEqualTo("UNSUPPORTED_STATION");
            assertThat(data.get("trains").size()).isZero();
        }
        SERVER.verify();
    }

    @Test void badRequestsAndUnknownStations() throws Exception {
        long konkuk = station("건대입구");
        failure(get("/api/v1/stations/" + konkuk + "/arrivals"), 400, "VALIDATION_ERROR", "lineId");
        failure(get("/api/v1/stations/" + konkuk + "/arrivals").param("lineId", "2").param("limit", "11"), 400, "VALIDATION_ERROR", "limit");
        failure(get("/api/v1/stations/" + konkuk + "/arrivals").param("lineId", "2").param("directionCode", "EAST"), 400, "VALIDATION_ERROR", "directionCode");
        failure(get("/api/v1/stations/999999/arrivals").param("lineId", "2"), 404, "STATION_NOT_FOUND", null);
        String body = mvc.perform(get("/api/v1/stations/" + konkuk + "/arrivals").param("lineId", "2"))
                .andExpect(status().isUnauthorized()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(mapper.readTree(body).get("error").get("code").asString()).isEqualTo("AUTH_REQUIRED");
        SERVER.verify();
    }

    @Test void openApiDocumentsTheEndpoint() throws Exception {
        JsonNode root = mapper.readTree(mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        JsonNode operation = root.get("paths").get("/api/v1/stations/{stationId}/arrivals").get("get");
        assertThat(operation.get("security").get(0).has("bearerAuth")).isTrue();
        List<String> codes = new ArrayList<>();
        operation.get("responses").propertyNames().forEach(codes::add);
        assertThat(codes).contains("200", "400", "401", "404");
        JsonNode schemas = root.get("components").get("schemas");
        assertThat(schemas.get("ArrivalData").toString()).contains("UNSUPPORTED_STATION", "NO_DATA", "UPSTREAM_ERROR", "DISABLED");
        assertThat(schemas.get("ArrivalTrainData").get("properties").get("secondsUntil").toString()).contains("integer");
    }

    private JsonNode ok(MockHttpServletRequestBuilder request) throws Exception {
        String body = mvc.perform(auth(request)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        JsonNode root = mapper.readTree(body);
        assertThat(root.get("success").asBoolean()).isTrue();
        return root.get("data");
    }

    private void failure(MockHttpServletRequestBuilder request, int status, String code, String detail) throws Exception {
        String body = mvc.perform(auth(request)).andExpect(status().is(status)).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        JsonNode error = mapper.readTree(body).get("error");
        assertThat(error.get("code").asString()).as(body).isEqualTo(code);
        if (detail != null) assertThat(error.get("details").has(detail)).as(body).isTrue();
    }

    private MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder request) {
        return request.header("Authorization", "Bearer " + jwt.createAccessToken(user));
    }
}
