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
import java.util.stream.Collectors;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
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
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Destination rules of GET /stations/{id}/arrivals (#174) on the migrated seed with the Seoul API stubbed. Rows are
 * hand-made (destination and direction only); the cache is off so every request reads its own rows.
 */
@Testcontainers
@SpringBootTest(properties = {
        "spring.config.import=", "spring.jpa.hibernate.ddl-auto=validate", "spring.jpa.open-in-view=false",
        "spring.jpa.show-sql=false", "auth.kakao.app-id=1234", "auth.jwt.access-token-ttl=15m",
        "auth.jwt.secret=dGVzdC1vbmx5LXNlY3JldC0wMTIzNDU2Nzg5YWJjZGVmZ2hpams=",
        "linkro.arrival.cache-ttl=0s", "linkro.arrival.failure-cache-ttl=0s"})
@Timeout(60)
class ArrivalDestinationPostgresTest {
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
    final JsonMapper mapper = JsonMapper.builder().build();
    MockMvc mvc;
    UUID user;

    @BeforeEach void setup() {
        jdbc.update("delete from users");
        user = users.saveAndFlush(new User("destination-user", "행선지")).getId();
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        SERVER.reset();
    }

    long station(String name) {
        return jdbc.queryForObject("select id from stations where name = ?", Long.class, name);
    }

    long line(String code) {
        return jdbc.queryForObject("select id from subway_lines where line_code = ?", Long.class, code);
    }

    /** Rows of one line in one direction, one per destination, 60 s apart, received now. */
    static String rows(String line, String updn, String... destinations) {
        String now = LocalDateTime.now(ZoneId.of("Asia/Seoul")).format(RECEIVED);
        StringJoiner list = new StringJoiner(",");
        for (int i = 0; i < destinations.length; i++) {
            list.add("""
                    {"subwayId":"100%s","updnLine":"%s","trainLineNm":"%s행","bstatnNm":"%s","statnTid":"","barvlDt":"%d","arvlCd":"99",\
                    "arvlMsg2":"","recptnDt":"%s","lstcarAt":"0","btrainSttus":"일반","btrainNo":"%d"}""".formatted(
                    line, updn, destinations[i], destinations[i], 60 * (i + 1), now, 9000 + i));
        }
        return "{\"errorMessage\":{\"status\":200,\"code\":\"INFO-000\",\"message\":\"\",\"total\":" + destinations.length
                + "},\"realtimeArrivalList\":[" + list + "]}";
    }

    /** Destinations kept for boarding → next with the extra parameters (name → value, values are station names or raw text). */
    List<String> kept(String body, String line, String board, String next, Map<String, String> params) throws Exception {
        SERVER.reset();
        SERVER.expect(ExpectedCount.once(), requestTo(startsWith(BASE + "/api/subway/"))).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
        MockHttpServletRequestBuilder request = get("/api/v1/stations/" + station(board) + "/arrivals")
                .param("lineId", String.valueOf(line(line))).param("nextStationId", String.valueOf(station(next))).param("limit", "10");
        params.forEach(request::param);
        JsonNode data = ok(request);
        SERVER.verify();
        List<String> names = new ArrayList<>();
        data.get("trains").forEach(train -> names.add(train.get("destinationName").asString()));
        return names;
    }

    JsonNode ok(MockHttpServletRequestBuilder request) throws Exception {
        String body = mvc.perform(request.header("Authorization", "Bearer " + jwt.createAccessToken(user)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return mapper.readTree(body).get("data");
    }

    JsonNode badRequest(MockHttpServletRequestBuilder request) throws Exception {
        String body = mvc.perform(request.header("Authorization", "Bearer " + jwt.createAccessToken(user)))
                .andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return mapper.readTree(body).get("error");
    }

    Map<String, String> alight(String station) {
        return Map.of("alightStationId", String.valueOf(station(station)));
    }

    static final String[] LINE5_DOWN = {"마천", "하남검단산", "상일동"};

    // ---- branch rule (alightStationId): 5호선 강동 ----

    @Test void beforeOrAtTheJunctionNothingIsDropped() throws Exception {
        assertThat(kept(rows("5", "하행", LINE5_DOWN), "5", "천호", "강동", alight("강동"))).containsExactly(LINE5_DOWN);
        assertThat(kept(rows("5", "하행", LINE5_DOWN), "5", "광나루", "천호", alight("천호"))).containsExactly(LINE5_DOWN);
    }

    @Test void towardTheMacheonBranchOnlyMacheonTrainsStay() throws Exception {
        assertThat(kept(rows("5", "하행", LINE5_DOWN), "5", "천호", "강동", alight("올림픽공원"))).containsExactly("마천");
        assertThat(kept(rows("5", "하행", LINE5_DOWN), "5", "강동", "둔촌동", alight("둔촌동"))).containsExactly("마천");
    }

    @Test void towardTheHanamSideMacheonTrainsGo() throws Exception {
        assertThat(kept(rows("5", "하행", LINE5_DOWN), "5", "천호", "강동", alight("굽은다리"))).containsExactly("하남검단산", "상일동");
        // 미사 is past 상일동: the branch rule alone keeps the 상일동 train (short turns are another rule).
        assertThat(kept(rows("5", "하행", LINE5_DOWN), "5", "천호", "강동", alight("미사"))).containsExactly("하남검단산", "상일동");
        assertThat(kept(rows("5", "하행", LINE5_DOWN), "5", "강동", "길동", alight("길동"))).containsExactly("하남검단산", "상일동");
    }

    @Test void unknownDestinationsStayAndNamesAreNormalized() throws Exception {
        // "올림픽공원(한국체대)" is 올림픽공원 on the 마천 branch; "어딘가" is no 5호선 station and stays.
        String body = rows("5", "하행", "하남검단산", "올림픽공원(한국체대)", "어딘가", " 마천 ");
        assertThat(kept(body, "5", "천호", "강동", alight("굽은다리"))).containsExactly("하남검단산", "어딘가");
        assertThat(kept(body, "5", "천호", "강동", alight("마천"))).containsExactly("올림픽공원(한국체대)", "어딘가", "마천");
    }

    @Test void withoutTheParameterEverythingStays() throws Exception {
        assertThat(kept(rows("5", "하행", LINE5_DOWN), "5", "천호", "강동", Map.of())).containsExactly(LINE5_DOWN);
    }

    @Test void stationsThatAreNotAheadAreIgnored() throws Exception {
        // Not on 5호선, behind the riding direction, the boarding station itself: no filter.
        assertThat(kept(rows("5", "하행", LINE5_DOWN), "5", "천호", "강동", alight("잠실"))).containsExactly(LINE5_DOWN);
        assertThat(kept(rows("5", "하행", LINE5_DOWN), "5", "천호", "강동", alight("광나루"))).containsExactly(LINE5_DOWN);
        assertThat(kept(rows("5", "하행", LINE5_DOWN), "5", "천호", "강동", alight("천호"))).containsExactly(LINE5_DOWN);
        assertThat(kept(rows("5", "하행", LINE5_DOWN), "5", "천호", "강동", Map.of("alightStationId", "999999"))).containsExactly(LINE5_DOWN);
    }

    @Test void otherLinesKeepEveryTrainWithAlightStationId() throws Exception {
        // The branch rule is 5호선 only: 4호선 사당행 stays even toward 남태령.
        assertThat(kept(rows("4", "하행", "오이도", "사당"), "4", "동작", "총신대입구(이수)", alight("남태령"))).containsExactly("오이도", "사당");
    }

    @Test void malformedIdsAreValidationErrors() throws Exception {
        for (String value : new String[]{"abc", "0", "-3"}) {
            JsonNode error = badRequest(get("/api/v1/stations/" + station("천호") + "/arrivals").param("lineId", String.valueOf(line("5")))
                    .param("nextStationId", String.valueOf(station("강동"))).param("alightStationId", value));
            assertThat(error.get("code").asString()).isEqualTo("VALIDATION_ERROR");
            assertThat(error.get("details").get("alightStationId").asString()).isEqualTo("alightStationId는 1 이상의 정수여야 합니다.");
        }
    }
}
