package com.mju.linkro.subway.congestion;

import com.mju.linkro.auth.jwt.JwtProvider;
import com.mju.linkro.subway.api.*;
import com.mju.linkro.user.domain.User;
import com.mju.linkro.user.repository.UserRepository;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * GET /congestion and the DB congestionOf on PostgreSQL (V1~V13, V11 seed) with a small fixed congestion_stats set.
 * 2026-11-06 is a Friday; 2026-12-25 (Friday) is a holiday.
 */
@Testcontainers
@SpringBootTest(properties = {
        "spring.config.import=", "spring.jpa.hibernate.ddl-auto=validate", "spring.jpa.open-in-view=false",
        "spring.jpa.show-sql=false", "auth.kakao.app-id=1234", "auth.jwt.access-token-ttl=15m",
        "auth.jwt.secret=dGVzdC1vbmx5LXNlY3JldC0wMTIzNDU2Nzg5YWJjZGVmZ2hpams=", "linkro.subway.congestion=db"})
@Timeout(60)
class CongestionApiPostgresTest {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }
    @Autowired WebApplicationContext context;
    @Autowired JwtProvider jwt;
    @Autowired UserRepository users;
    @Autowired JdbcTemplate jdbc;
    @Autowired CongestionLookup lookup;
    private final JsonMapper mapper = JsonMapper.builder().build();
    MockMvc mvc;
    UUID user;

    /** 강남 2호선: a full service day with a 12:30 gap. */
    long gangnam;
    long gangnamStation;
    /** 사당 2호선: last WEEKDAY slot 23:00; SATURDAY only 05:30. 사당 4호선 for the transfer stop. */
    long sadang2;
    long sadang4;
    long sadangStation;
    long line2;

    @BeforeEach void setup() {
        jdbc.update("delete from users");
        jdbc.update("delete from congestion_stats");
        user = users.saveAndFlush(new User("congestion-user", "혼잡도")).getId();
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        gangnam = node("강남", "2");
        sadang2 = node("사당", "2");
        sadang4 = node("사당", "4");
        gangnamStation = jdbc.queryForObject("select id from stations where name = '강남'", Long.class);
        sadangStation = jdbc.queryForObject("select id from stations where name = '사당'", Long.class);
        line2 = jdbc.queryForObject("select id from subway_lines where line_code = '2'", Long.class);

        for (String[] row : new String[][] {
                {"05:30", "11"}, {"06:00", "12"}, {"07:30", "40"}, {"08:00", "34.95326485813263"}, {"08:30", "99.96055952795814"},
                {"12:00", "40"}, {"13:00", "41"}, {"23:30", "30"}, {"00:00", "20"}, {"00:30", "21"}}) {
            stat(gangnam, "INNER", "WEEKDAY", row[0], row[1], "PREDICTED");
        }
        stat(gangnam, "INNER", "SATURDAY", "05:30", "15", "PREDICTED");
        stat(gangnam, "INNER", "SATURDAY", "00:00", "25", "PREDICTED");
        stat(gangnam, "INNER", "SUNDAY", "05:30", "16", "PREDICTED");
        stat(gangnam, "INNER", "SUNDAY", "08:00", "17", "PREDICTED");
        stat(gangnam, "INNER", "SUNDAY", "00:00", "26", "PREDICTED");
        // Source priority at WEEKDAY 09:00 (all three), 09:30 (no PREDICTED) and 10:00 (MOCK only).
        stat(gangnam, "INNER", "WEEKDAY", "09:00", "60", "PREDICTED");
        stat(gangnam, "INNER", "WEEKDAY", "09:00", "70", "ESTIMATED");
        stat(gangnam, "INNER", "WEEKDAY", "09:00", "80", "MOCK");
        stat(gangnam, "INNER", "WEEKDAY", "09:30", "71", "ESTIMATED");
        stat(gangnam, "INNER", "WEEKDAY", "09:30", "81", "MOCK");
        stat(gangnam, "INNER", "WEEKDAY", "10:00", "82", "MOCK");

        stat(sadang2, "INNER", "WEEKDAY", "05:30", "50", "PREDICTED");
        stat(sadang2, "INNER", "WEEKDAY", "08:00", "120.5", "PREDICTED");
        stat(sadang2, "INNER", "WEEKDAY", "23:00", "51", "PREDICTED");
        stat(sadang2, "INNER", "SATURDAY", "05:30", "52", "PREDICTED");
        stat(sadang4, "DOWN", "WEEKDAY", "08:00", "60", "PREDICTED");
    }

    // ---- GET /congestion with date: rule A slot boundaries ----

    @ParameterizedTest(name = "{0} {1} -> {3} {4} {2}")
    @CsvSource({
            "2026-11-06, 05:29, 2026-11-06, 05:30, true,  WEEKDAY, 11",
            "2026-11-06, 05:30, 2026-11-06, 05:30, false, WEEKDAY, 11",
            "2026-11-06, 23:59, 2026-11-06, 23:30, false, WEEKDAY, 30",
            "2026-11-06, 00:00, 2026-11-05, 00:00, false, WEEKDAY, 20",
            "2026-11-06, 00:29, 2026-11-05, 00:00, false, WEEKDAY, 20",
            "2026-11-06, 00:30, 2026-11-05, 00:30, false, WEEKDAY, 21",
            "2026-11-06, 00:59, 2026-11-05, 00:30, false, WEEKDAY, 21",
            "2026-11-06, 01:00, 2026-11-06, 05:30, true,  WEEKDAY, 11"})
    void slotBoundaries(String date, String time, String slotDate, String slot, boolean firstTrain, String dayType, String pct) throws Exception {
        JsonNode data = ok(query(gangnamStation, line2, "INNER", null, time, date));
        assertThat(data.get("slotDate").asString()).isEqualTo(slotDate);
        assertThat(data.get("slotStart").asString()).isEqualTo(slot);
        assertThat(data.get("firstTrain").asBoolean()).isEqualTo(firstTrain);
        assertThat(data.get("dayType").asString()).isEqualTo(dayType);
        assertThat(data.get("supported").asBoolean()).isTrue();
        assertThat(data.get("pct").decimalValue()).isEqualByComparingTo(pct);
    }

    @Test void afterMidnightBelongsToThePreviousServiceDay() throws Exception {
        // Calendar Saturday 00:10 is Friday night: WEEKDAY 00:00 of Friday.
        JsonNode saturday = ok(query(gangnamStation, line2, "INNER", null, "00:10", "2026-11-07"));
        assertThat(List.of(saturday.get("dayType").asString(), saturday.get("slotDate").asString(), saturday.get("pct").asString()))
                .containsExactly("WEEKDAY", "2026-11-06", "20");
        // Calendar Sunday 00:10 is Saturday night: SATURDAY.
        JsonNode sunday = ok(query(gangnamStation, line2, "INNER", null, "00:10", "2026-11-08"));
        assertThat(List.of(sunday.get("dayType").asString(), sunday.get("slotDate").asString(), sunday.get("pct").asString()))
                .containsExactly("SATURDAY", "2026-11-07", "25");
    }

    @Test void holidaysAreSundayByServiceDay() throws Exception {
        // 12-25 00:10 is still the night of 12-24 (Thursday, WEEKDAY); 12-25 08:00 is the holiday itself (SUNDAY).
        assertThat(ok(query(gangnamStation, line2, "INNER", null, "00:10", "2026-12-25")).get("dayType").asString()).isEqualTo("WEEKDAY");
        JsonNode holiday = ok(query(gangnamStation, line2, "INNER", null, "08:00", "2026-12-25"));
        assertThat(List.of(holiday.get("dayType").asString(), holiday.get("pct").asString())).containsExactly("SUNDAY", "17");
        // 12-26 00:10 is the holiday's night: SUNDAY 00:00 of 12-25.
        JsonNode holidayNight = ok(query(gangnamStation, line2, "INNER", null, "00:10", "2026-12-26"));
        assertThat(List.of(holidayNight.get("dayType").asString(), holidayNight.get("slotDate").asString(), holidayNight.get("pct").asString()))
                .containsExactly("SUNDAY", "2026-12-25", "26");
    }

    // ---- rule C ----

    @Test void afterTheLastTrainUsesTheNextServiceDaysFirstTrain() throws Exception {
        // 사당 has no slot after 23:00 on Friday: next day (Saturday) 05:30.
        JsonNode next = ok(query(sadangStation, line2, "INNER", null, "23:30", "2026-11-06"));
        assertThat(next.get("supported").asBoolean()).isTrue();
        assertThat(List.of(next.get("slotDate").asString(), next.get("slotStart").asString(), next.get("dayType").asString(),
                next.get("pct").asString())).containsExactly("2026-11-07", "05:30", "SATURDAY", "52");
        assertThat(next.get("firstTrain").asBoolean()).isTrue();
        // Saturday 23:30: Sunday has no 05:30 row, so gray; the slot fields stay those of the request.
        JsonNode gray = ok(query(sadangStation, line2, "INNER", null, "23:30", "2026-11-07"));
        assertGray(gray);
        assertThat(List.of(gray.get("slotDate").asString(), gray.get("slotStart").asString(), gray.get("dayType").asString()))
                .containsExactly("2026-11-07", "23:30", "SATURDAY");
        assertThat(gray.get("firstTrain").asBoolean()).isFalse();
    }

    @Test void aGapInsideTheServiceDayStaysGray() throws Exception {
        assertGray(ok(query(gangnamStation, line2, "INNER", null, "12:40", "2026-11-06")));
        assertGray(ok(query(gangnamStation, line2, "INNER", null, "12:40", null).queryParam("dayType", "WEEKDAY")));
    }

    // ---- rules D, E ----

    @Test void sourcePriorityIsPredictedThenEstimatedThenMock() throws Exception {
        assertThat(ok(query(gangnamStation, line2, "INNER", "WEEKDAY", "09:10", null)).get("source").asString()).isEqualTo("PREDICTED");
        JsonNode estimated = ok(query(gangnamStation, line2, "INNER", "WEEKDAY", "09:40", null));
        assertThat(List.of(estimated.get("source").asString(), estimated.get("pct").asString())).containsExactly("ESTIMATED", "71");
        assertThat(ok(query(gangnamStation, line2, "INNER", "WEEKDAY", "10:00", null)).get("source").asString()).isEqualTo("MOCK");
    }

    @Test void pctIsAnIntegerCappedByTheStoredLevel() throws Exception {
        JsonNode comfortable = ok(query(gangnamStation, line2, "INNER", "WEEKDAY", "08:00", null));
        assertThat(comfortable.get("pct").isIntegralNumber()).isTrue();
        assertThat(comfortable.get("pct").asInt()).isEqualTo(34);
        assertThat(List.of(comfortable.get("level").asString(), comfortable.get("levelLabel").asString())).containsExactly("COMFORTABLE", "쾌적");
        JsonNode normal = ok(query(gangnamStation, line2, "INNER", "WEEKDAY", "08:45", null));
        assertThat(normal.get("pct").isIntegralNumber()).isTrue();
        assertThat(normal.get("pct").asInt()).isEqualTo(99);
        assertThat(normal.get("level").asString()).isEqualTo("NORMAL");
    }

    // ---- GET without date ----

    @Test void withoutDateTheDayTypeIsTheServiceDays() throws Exception {
        JsonNode midnight = ok(query(gangnamStation, line2, "INNER", "SATURDAY", "00:10", null));
        assertThat(List.of(midnight.get("dayType").asString(), midnight.get("slotStart").asString(), midnight.get("pct").asString()))
                .containsExactly("SATURDAY", "00:00", "25");
        assertThat(midnight.get("slotDate").isNull()).isTrue();
        JsonNode early = ok(query(gangnamStation, line2, "INNER", "WEEKDAY", "03:00", null));
        assertThat(List.of(early.get("slotStart").asString(), early.get("pct").asString())).containsExactly("05:30", "11");
        assertThat(early.get("firstTrain").asBoolean()).isTrue();
        assertThat(early.get("slotDate").isNull()).isTrue();
        // After the last train the next day is unknown without a date: gray.
        assertGray(ok(query(sadangStation, line2, "INNER", "WEEKDAY", "23:30", null)));
    }

    // ---- controller ----

    @Test void responseHasEverySpecFieldAndGrayHasNullValues() throws Exception {
        JsonNode data = ok(query(gangnamStation, line2, "INNER", "WEEKDAY", "18:40", null));
        assertThat(fieldNames(data)).containsExactly("stationId", "lineId", "direction", "dayType", "slotStart", "slotDate",
                "firstTrain", "supported", "pct", "level", "levelLabel", "source");
        assertGray(data);
        assertThat(data.get("stationId").asLong()).isEqualTo(gangnamStation);
        assertThat(data.get("slotStart").asString()).isEqualTo("18:30");
    }

    @Test void badParametersAreValidationErrors() throws Exception {
        failure(get("/api/v1/congestion"), 400, "VALIDATION_ERROR", "stationId", "lineId", "direction", "dayType", "time");
        failure(query(gangnamStation, line2, "EAST", "HOLIDAY", "8:00", "2026-13-01"), 400, "VALIDATION_ERROR",
                "direction", "dayType", "time", "date");
        failure(query(gangnamStation, line2, "INNER", "WEEKDAY", "24:00", null), 400, "VALIDATION_ERROR", "time");
        failure(query(0, line2, "INNER", "WEEKDAY", "08:00", null), 400, "VALIDATION_ERROR", "stationId");
    }

    @Test void unknownStationOrLineIsNotFound() throws Exception {
        failure(query(999_999, line2, "INNER", "WEEKDAY", "08:00", null), 404, "STATION_NOT_FOUND");
        failure(query(gangnamStation, 999_999, "INNER", "WEEKDAY", "08:00", null), 404, "NOT_FOUND", "lineId");
    }

    @Test void authenticationIsRequired() throws Exception {
        String body = mvc.perform(get("/api/v1/congestion").param("stationId", "1")).andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(mapper.readTree(body).get("error").get("code").asString()).isEqualTo("AUTH_REQUIRED");
    }

    @Test void openApiDocumentsEstimatedAndTheEnvelope() throws Exception {
        JsonNode root = mapper.readTree(mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        JsonNode operation = root.get("paths").get("/api/v1/congestion").get("get");
        assertThat(operation.get("security").get(0).has("bearerAuth")).isTrue();
        assertThat(fieldNames(operation.get("responses"))).contains("200", "400", "401", "404");
        JsonNode source = root.get("components").get("schemas").get("CongestionData").get("properties").get("source");
        assertThat(source.toString()).contains("PREDICTED", "ESTIMATED", "MOCK");
        JsonNode pct = root.get("components").get("schemas").get("CongestionData").get("properties").get("pct");
        assertThat(pct.toString()).contains("integer").doesNotContain("number");
        assertThat(operation.get("responses").get("200").toString()).contains("ESTIMATED", "\"firstTrain\":true", "\"supported\":false");
    }

    // ---- congestionOf ----

    @Test void routeStopsUseTheirOwnPassTimeAndTheMaxSkipsGrayStops() {
        long gangnamLine = lineOf(gangnam);
        long yeokchon = node("역촌", "6");
        long yongdap = node("용답", "2");
        stat(yeokchon, "LOOP", "WEEKDAY", "08:00", "30", "PREDICTED");
        stat(yongdap, "UP", "WEEKDAY", "05:30", "9", "PREDICTED");
        stat(yongdap, "UP", "WEEKDAY", "08:00", "45", "PREDICTED");
        RoutePath path = path(
                stop(gangnam, DirectionCode.INNER, 0),        // 07:50 -> 07:30 slot, 40
                stop(sadang2, DirectionCode.INNER, 600),      // 08:00, 120.5 shown as 121
                stop(sadang4, DirectionCode.DOWN, 900),       // same station, 4호선 node: 08:05 -> 08:00, 60
                stop(yeokchon, DirectionCode.LOOP, 1200),     // 6호선 응암순환 LOOP node: 08:10 -> 08:00, 30
                stop(yongdap, DirectionCode.UP, 1500),        // 2호선 성수지선 UP node: 08:15 -> 08:00, 45
                stop(gangnam, DirectionCode.INNER, 2400));    // 08:30 -> 99.96 NORMAL shown as 99

        CongestionResult result = lookup.congestionOf(path, at("2026-11-06T07:50"));

        assertThat(result).extracting(CongestionResult::supported, CongestionResult::pct, CongestionResult::level,
                CongestionResult::source).containsExactly(true, 121.0, "CROWDED", CongestionSource.PREDICTED);
        assertThat(result.stops()).extracting(s -> s.slotStart().toString() + " " + s.congestion().pct())
                .containsExactly("07:30 40.0", "08:00 121.0", "08:00 60.0", "08:00 30.0", "08:00 45.0", "08:30 99.0");
        assertThat(result.stops()).extracting(StopCongestion::stationLineId).containsExactly(gangnam, sadang2, sadang4, yeokchon, yongdap, gangnam);
        assertThat(result.stops().get(1).stationId()).isEqualTo(result.stops().get(2).stationId());
        assertThat(result.stops()).allSatisfy(s -> {
            assertThat(s.slotDate()).isEqualTo(LocalDate.of(2026, 11, 6));
            assertThat(s.firstTrain()).isFalse();
        });
        assertThat(result.stops().get(0).lineId()).isEqualTo(gangnamLine);
    }

    @Test void grayStopsAreLeftOutAndAllGrayIsUnsupported() {
        // 사당 2 at 12:00 has later slots (23:00): a gap, gray. 강남 at 12:10 has 40.
        RoutePath mixed = path(stop(sadang2, DirectionCode.INNER, 0), stop(gangnam, DirectionCode.INNER, 600));
        CongestionResult result = lookup.congestionOf(mixed, at("2026-11-06T12:00"));
        assertThat(result.pct()).isEqualTo(40.0);
        assertThat(result.stops().get(0).congestion().supported()).isFalse();

        RoutePath allGray = path(stop(sadang2, DirectionCode.INNER, 0), stop(sadang4, DirectionCode.DOWN, 300));
        CongestionResult gray = lookup.congestionOf(allGray, at("2026-11-06T12:00"));
        assertThat(gray.supported()).isFalse();
        assertThat(gray.pct()).isNull();
        assertThat(gray.stops()).hasSize(2).allSatisfy(s -> assertThat(s.congestion().supported()).isFalse());
    }

    @Test void routeAcrossMidnightAndAfterTheLastTrain() {
        // Friday 23:50: 강남 23:30 (30); 사당 2 at 00:05 has no slot after 23:00 -> Saturday 05:30 (52);
        // 강남 again at 00:10 -> Friday night 00:00 (20).
        RoutePath path = path(stop(gangnam, DirectionCode.INNER, 0), stop(sadang2, DirectionCode.INNER, 900),
                stop(gangnam, DirectionCode.INNER, 1200));
        CongestionResult result = lookup.congestionOf(path, at("2026-11-06T23:50"));
        assertThat(result.stops()).extracting(s -> s.slotDate() + " " + s.slotStart() + " " + s.firstTrain() + " " + s.congestion().pct())
                .containsExactly("2026-11-06 23:30 false 30.0", "2026-11-07 05:30 true 52.0", "2026-11-06 00:00 false 20.0");
        assertThat(result.pct()).isEqualTo(52.0);
    }

    // ---- helpers ----

    private MockHttpServletRequestBuilder query(long station, long line, String direction, String dayType, String time, String date) {
        MockHttpServletRequestBuilder request = get("/api/v1/congestion").param("stationId", String.valueOf(station))
                .param("lineId", String.valueOf(line)).param("direction", direction).param("time", time);
        if (dayType != null) request.param("dayType", dayType);
        if (date != null) request.param("date", date);
        return request;
    }

    private JsonNode ok(MockHttpServletRequestBuilder request) throws Exception {
        String body = mvc.perform(auth(request)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        JsonNode root = mapper.readTree(body);
        assertThat(root.get("success").asBoolean()).isTrue();
        return root.get("data");
    }

    private void failure(MockHttpServletRequestBuilder request, int status, String code, String... detailKeys) throws Exception {
        String body = mvc.perform(auth(request)).andExpect(status().is(status)).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        JsonNode error = mapper.readTree(body).get("error");
        assertThat(error.get("code").asString()).as(body).isEqualTo(code);
        assertThat(fieldNames(error.get("details"))).as(body).contains(detailKeys);
    }

    private MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder request) {
        return request.header("Authorization", "Bearer " + jwt.createAccessToken(user));
    }

    private static void assertGray(JsonNode data) {
        assertThat(data.get("supported").asBoolean()).isFalse();
        for (String field : List.of("pct", "level", "levelLabel", "source")) {
            assertThat(data.get(field).isNull()).as(field).isTrue();
        }
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.propertyNames().forEach(names::add);
        return names;
    }

    private long node(String station, String line) {
        return jdbc.queryForObject("select sl.id from station_lines sl join stations s on s.id = sl.station_id "
                + "join subway_lines l on l.id = sl.line_id where s.name = ? and l.line_code = ?", Long.class, station, line);
    }

    private long lineOf(long node) {
        return jdbc.queryForObject("select line_id from station_lines where id = ?", Long.class, node);
    }

    private PathStop stop(long node, DirectionCode direction, int offsetSec) {
        Map<String, Object> row = jdbc.queryForMap("select station_id, line_id from station_lines where id = ?", node);
        long station = ((Number) row.get("station_id")).longValue();
        return new PathStop(station, "역" + station, 37.5, 127.0, node, ((Number) row.get("line_id")).longValue(), direction, offsetSec);
    }

    private static RoutePath path(PathStop... stops) {
        return new RoutePath(List.of(), List.of(stops), stops[stops.length - 1].offsetSec(), 0, 0, stops[0].stationId(),
                stops[stops.length - 1].stationId());
    }

    private static Instant at(String seoulLocal) {
        return ZonedDateTime.of(java.time.LocalDateTime.parse(seoulLocal), ServiceSlots.ZONE).toInstant();
    }

    private void stat(long node, String direction, String dayType, String slot, String pct, String source) {
        BigDecimal value = new BigDecimal(pct);
        String level = value.compareTo(BigDecimal.valueOf(35)) < 0 ? "COMFORTABLE" : value.compareTo(BigDecimal.valueOf(50)) < 0 ? "RELAXED"
                : value.compareTo(BigDecimal.valueOf(100)) < 0 ? "NORMAL" : value.compareTo(BigDecimal.valueOf(150)) < 0 ? "CROWDED" : "VERY_CROWDED";
        String label = Map.of("COMFORTABLE", "쾌적", "RELAXED", "여유", "NORMAL", "보통", "CROWDED", "혼잡", "VERY_CROWDED", "매우 혼잡").get(level);
        jdbc.update("""
                insert into congestion_stats(station_line_id, direction, day_type, slot_start, congestion_pct, congestion_level,
                                             congestion_level_label, source, model_version)
                values (?, ?, ?, ?::time, ?, ?, ?, ?, ?)
                """, node, direction, dayType, slot, value, level, label, source, "PREDICTED".equals(source) ? "HGB_TEST" : null);
    }

}
