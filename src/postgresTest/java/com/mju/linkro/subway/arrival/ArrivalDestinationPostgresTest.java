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
        return rowsTo(line, updn, "", destinations);
    }

    /** As {@link #rows} with every row's statnTid (2호선 compares it with the next station's id). */
    static String rowsTo(String line, String updn, String statnTid, String... destinations) {
        String now = LocalDateTime.now(ZoneId.of("Asia/Seoul")).format(RECEIVED);
        StringJoiner list = new StringJoiner(",");
        for (int i = 0; i < destinations.length; i++) {
            list.add("""
                    {"subwayId":"100%s","updnLine":"%s","trainLineNm":"%s행","bstatnNm":"%s","statnTid":"%s","barvlDt":"%d","arvlCd":"99",\
                    "arvlMsg2":"","recptnDt":"%s","lstcarAt":"0","btrainSttus":"일반","btrainNo":"%d"}""".formatted(
                    line, updn, destinations[i], destinations[i], statnTid, 60 * (i + 1), now, 9000 + i));
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

    // ---- short-turn rule (trainAlightStationId, 3·4·5·6·7호선) ----

    Map<String, String> train(String station) {
        return Map.of("trainAlightStationId", String.valueOf(station(station)));
    }

    @Test void line5ShortTurnsAndBranches() throws Exception {
        // 천호 -> 미사: 상일동 ends before 미사, 마천 is the other branch.
        assertThat(kept(rows("5", "하행", LINE5_DOWN), "5", "천호", "강동", train("미사"))).containsExactly("하남검단산");
        // 길동 -> 천호 (상행): the 강동 train ends before 천호; toward 강동 itself it stays.
        assertThat(kept(rows("5", "상행", "방화", "강동"), "5", "길동", "강동", train("천호"))).containsExactly("방화");
        assertThat(kept(rows("5", "상행", "방화", "강동"), "5", "길동", "강동", train("강동"))).containsExactly("방화", "강동");
        // Boarding at 강동: a train ending here is no train to board.
        assertThat(kept(rows("5", "상행", "방화", "강동"), "5", "강동", "천호", train("광나루"))).containsExactly("방화");
    }

    @Test void line4SadangTrainsDoNotReachNamtaeryeong() throws Exception {
        String body = rows("4", "하행", "오이도", "사당");
        assertThat(kept(body, "4", "서울역", "숙대입구", train("남태령"))).containsExactly("오이도");
        assertThat(kept(body, "4", "서울역", "숙대입구", train("사당"))).containsExactly("오이도", "사당");
        assertThat(kept(body, "4", "서울역", "숙대입구", train("동작"))).containsExactly("오이도", "사당");
    }

    @Test void line7DobongsanTrainsDoNotReachJangam() throws Exception {
        String body = rows("7", "상행", "장암", "도봉산");
        assertThat(kept(body, "7", "노원", "마들", train("장암"))).containsExactly("장암");
        assertThat(kept(body, "7", "노원", "마들", train("도봉산"))).containsExactly("장암", "도봉산");
    }

    @Test void line6BonghwasanTrainsAndTheEungamLoop() throws Exception {
        String body = rows("6", "하행", "신내", "봉화산(서울의료원)");
        // 석계 -> 신내: 봉화산 (with an official suffix) ends before 신내.
        assertThat(kept(body, "6", "석계", "태릉입구", train("신내"))).containsExactly("신내");
        // Boarding at 응암 or inside the loop: no destination rule.
        assertThat(kept(body, "6", "응암", "새절", train("신내"))).containsExactly("신내", "봉화산(서울의료원)");
        assertThat(kept(body, "6", "구산", "응암", train("신내"))).containsExactly("신내", "봉화산(서울의료원)");
        assertThat(kept(rows("6", "하행", "독바위", "봉화산"), "6", "역촌", "불광", train("연신내"))).containsExactly("독바위", "봉화산");
    }

    @Test void line3GupabalAndSuseoTrains() throws Exception {
        assertThat(kept(rows("3", "상행", "대화", "구파발"), "3", "연신내", "구파발", train("지축"))).containsExactly("대화");
        String down = rows("3", "하행", "오금", "수서");
        for (String alight : new String[]{"가락시장", "경찰병원", "오금"}) {
            assertThat(kept(down, "3", "교대", "남부터미널", train(alight))).as(alight).containsExactly("오금");
        }
        assertThat(kept(down, "3", "교대", "남부터미널", train("수서"))).containsExactly("오금", "수서");
    }

    @Test void lines128NeverDropATrain() throws Exception {
        assertThat(kept(rows("1", "상행", "동묘앞", "광운대"), "1", "종각", "종로3가", train("청량리"))).containsExactly("동묘앞", "광운대");
        // 2호선 compares statnTid with the next station (신도림 234).
        assertThat(kept(rowsTo("2", "외선", "1002000234", "신도림", "성수"), "2", "대림", "신도림", train("문래"))).containsExactly("신도림", "성수");
        assertThat(kept(rows("8", "하행", "모란", "가락시장"), "8", "잠실", "석촌", train("모란"))).containsExactly("모란", "가락시장");
    }

    @Test void trainAlightStationIdWinsAndFallsBackToAlightStationId() throws Exception {
        // Both: the first train's station decides (미사: 상일동 and 마천 go).
        Map<String, String> both = Map.of("alightStationId", String.valueOf(station("올림픽공원")), "trainAlightStationId", String.valueOf(station("미사")));
        assertThat(kept(rows("5", "하행", LINE5_DOWN), "5", "천호", "강동", both)).containsExactly("하남검단산");
        // An ignored trainAlightStationId (another line's station) falls back to the branch rule of alightStationId.
        Map<String, String> fallback = Map.of("alightStationId", String.valueOf(station("올림픽공원")), "trainAlightStationId", String.valueOf(station("잠실")));
        assertThat(kept(rows("5", "하행", LINE5_DOWN), "5", "천호", "강동", fallback)).containsExactly("마천");
        // alightStationId alone never applies the short-turn rule.
        assertThat(kept(rows("4", "하행", "오이도", "사당"), "4", "서울역", "숙대입구", alight("남태령"))).containsExactly("오이도", "사당");
    }

    @Test void trainAlightStationsThatAreNotAheadAreIgnoredAndMalformedOnesAreErrors() throws Exception {
        assertThat(kept(rows("4", "하행", "오이도", "사당"), "4", "서울역", "숙대입구", train("회현"))).containsExactly("오이도", "사당");
        assertThat(kept(rows("4", "하행", "오이도", "사당"), "4", "서울역", "숙대입구", train("서울역"))).containsExactly("오이도", "사당");
        JsonNode error = badRequest(get("/api/v1/stations/" + station("서울역") + "/arrivals").param("lineId", String.valueOf(line("4")))
                .param("nextStationId", String.valueOf(station("숙대입구"))).param("trainAlightStationId", "x"));
        assertThat(error.get("details").get("trainAlightStationId").asString()).isEqualTo("trainAlightStationId는 1 이상의 정수여야 합니다.");
    }
}
