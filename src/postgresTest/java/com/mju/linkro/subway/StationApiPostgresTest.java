package com.mju.linkro.subway;

import com.mju.linkro.auth.jwt.JwtProvider;
import com.mju.linkro.common.exception.BusinessException;
import com.mju.linkro.common.exception.ErrorCode;
import com.mju.linkro.subway.api.StationInfo;
import com.mju.linkro.subway.api.StationLookup;
import com.mju.linkro.user.domain.User;
import com.mju.linkro.user.repository.UserRepository;
import java.util.*;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Lines/stations API and StationLookup on PostgreSQL with the real V1~V11 migrations (V11 seed data). */
@Testcontainers
@SpringBootTest(properties = {
        "spring.config.import=", "spring.jpa.hibernate.ddl-auto=validate", "spring.jpa.open-in-view=false",
        "spring.jpa.show-sql=false", "auth.kakao.app-id=1234", "auth.jwt.access-token-ttl=15m",
        "auth.jwt.secret=dGVzdC1vbmx5LXNlY3JldC0wMTIzNDU2Nzg5YWJjZGVmZ2hpams="})
@Timeout(45)
class StationApiPostgresTest {
    private static final String SEOUL = "126.7,37.4,127.3,37.8";

    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }
    @Autowired WebApplicationContext context;
    @Autowired JwtProvider jwt;
    @Autowired UserRepository users;
    @Autowired StationLookup lookup;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager manager;
    private final JsonMapper mapper = JsonMapper.builder().build();
    MockMvc mvc;
    UUID user;

    @BeforeEach void setup() {
        jdbc.update("delete from users");
        user = users.saveAndFlush(new User("station-user", "역조회")).getId();
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test void linesAreAllEightInSortOrder() throws Exception {
        JsonNode data = ok(get("/api/v1/lines"));
        assertThat(data.size()).isEqualTo(8);
        assertThat(values(data, "lineCode")).containsExactly("1", "2", "3", "4", "5", "6", "7", "8");
        assertThat(data.get(1).get("name").asString()).isEqualTo("2호선");
        assertThat(data.get(1).get("color").asString()).isEqualTo("#00A84D");
        assertThat(data.get(0).size()).isEqualTo(4);
    }

    @Test void keywordFindsGangnamOnceWithItsLinesGroupedAndExactMatchFirst() throws Exception {
        JsonNode page = ok(get("/api/v1/stations").param("keyword", "강남"));
        JsonNode items = page.get("items");
        assertThat(items.get(0).get("name").asString()).isEqualTo("강남");
        assertThat(values(items, "name")).filteredOn("강남"::equals).hasSize(1);
        // Exact match first, then names starting with the keyword (e.g. 강남구청).
        assertThat(items.get(1).get("name").asString()).startsWith("강남");
        JsonNode gangnam = items.get(0);
        assertThat(gangnam.get("isSupported").asBoolean()).isTrue();
        assertThat(gangnam.get("lines")).singleElement().satisfies(line -> {
            assertThat(line.get("lineName").asString()).isEqualTo("2호선");
            assertThat(line.get("stationCode").asString()).isEqualTo("222");
            assertThat(line.size()).isEqualTo(4);
        });
        assertThat(page.get("page").asInt()).isZero();
        assertThat(page.get("size").asInt()).isEqualTo(20);
        assertThat(page.get("totalElements").asLong()).isEqualTo(items.size());
    }

    @Test void transferStationIsOneItemWithAllItsLines() throws Exception {
        JsonNode jamsil = ok(get("/api/v1/stations").param("keyword", "잠실")).get("items").get(0);
        assertThat(jamsil.get("name").asString()).isEqualTo("잠실");
        assertThat(values(jamsil.get("lines"), "lineName")).containsExactly("2호선", "8호선");
    }

    @Test void stationSuffixKeywordsFindBothSeoulStationAndGangnam() throws Exception {
        JsonNode seoul = ok(get("/api/v1/stations").param("keyword", "서울역")).get("items");
        assertThat(seoul.get(0).get("name").asString()).isEqualTo("서울역");
        assertThat(values(seoul.get(0).get("lines"), "lineName")).containsExactly("1호선", "4호선");
        assertThat(ok(get("/api/v1/stations").param("keyword", "강남역")).get("items").get(0).get("name").asString())
                .isEqualTo("강남");
        assertThat(ok(get("/api/v1/stations").param("keyword", "  강남역 ")).get("items").get(0).get("name").asString())
                .isEqualTo("강남");
    }

    @Test void likeWildcardsMatchLiterally() throws Exception {
        for (String keyword : List.of("%", "_", "\\", "강%")) {
            JsonNode page = ok(get("/api/v1/stations").param("keyword", keyword));
            assertThat(page.get("totalElements").asLong()).as(keyword).isZero();
        }
    }

    @Test void lineFilterKeepsOnlyThatLineButListsEveryLineOfEachStation() throws Exception {
        long line2 = jdbc.queryForObject("select id from subway_lines where line_code = '2'", Long.class);
        long line2Stations = jdbc.queryForObject("select count(*) from station_lines where line_id = ?", Long.class, line2);
        JsonNode page = ok(get("/api/v1/stations").param("bbox", SEOUL).param("lineId", String.valueOf(line2)).param("size", "500"));
        JsonNode items = page.get("items");
        assertThat(page.get("totalElements").asLong()).isEqualTo(line2Stations).isEqualTo(items.size());
        for (JsonNode item : items) {
            assertThat(values(item.get("lines"), "lineName")).contains("2호선");
        }
        JsonNode jamsil = find(items, "잠실");
        assertThat(values(jamsil.get("lines"), "lineName")).containsExactly("2호선", "8호선");
        // bbox only: id order.
        assertThat(values(items, "id").stream().map(Long::parseLong).toList()).isSorted();

        JsonNode missing = ok(get("/api/v1/stations").param("keyword", "강남").param("lineId", "999999"));
        assertThat(missing.get("items").size()).isZero();
        assertThat(missing.get("totalElements").asLong()).isZero();
    }

    @Test void bboxAloneAndCombinedWithKeyword() throws Exception {
        String aroundGangnam = "127.02,37.49,127.04,37.51";
        JsonNode near = ok(get("/api/v1/stations").param("bbox", aroundGangnam)).get("items");
        assertThat(values(near, "name")).contains("강남");
        for (JsonNode item : near) {
            assertThat(item.get("longitude").asDouble()).isBetween(127.02, 127.04);
            assertThat(item.get("latitude").asDouble()).isBetween(37.49, 37.51);
        }
        assertThat(values(ok(get("/api/v1/stations").param("keyword", "강남").param("bbox", aroundGangnam)).get("items"), "name"))
                .contains("강남").allMatch(name -> name.contains("강남"));
        String aroundSeoulStation = "126.96,37.54,126.98,37.56";
        assertThat(ok(get("/api/v1/stations").param("keyword", "강남").param("bbox", aroundSeoulStation)).get("totalElements").asLong())
                .isZero();
        JsonNode paged = ok(get("/api/v1/stations").param("bbox", SEOUL).param("page", "1").param("size", "100"));
        assertThat(paged.get("items").size()).isEqualTo(100);
        assertThat(paged.get("totalElements").asLong()).isEqualTo(240);
    }

    @Test void invalidQueriesAreValidationErrors() throws Exception {
        failure(get("/api/v1/stations"), 400, "VALIDATION_ERROR", "keyword");
        failure(get("/api/v1/stations").param("keyword", "  ").param("lineId", "2"), 400, "VALIDATION_ERROR", "keyword");
        failure(get("/api/v1/stations").param("bbox", "127.1,37.4,126.9,37.6"), 400, "VALIDATION_ERROR", "bbox");
        failure(get("/api/v1/stations").param("bbox", "126.9,37.4,127.1"), 400, "VALIDATION_ERROR", "bbox");
        failure(get("/api/v1/stations").param("keyword", "강남").param("size", "51"), 400, "VALIDATION_ERROR", "size");
        failure(get("/api/v1/stations").param("bbox", SEOUL).param("size", "501"), 400, "VALIDATION_ERROR", "size");
        failure(get("/api/v1/stations").param("keyword", "강남").param("page", "-1"), 400, "VALIDATION_ERROR", "page");
        failure(get("/api/v1/stations").param("keyword", "가".repeat(51)), 400, "VALIDATION_ERROR", "keyword");
        failure(get("/api/v1/stations").param("keyword", "강남").param("size", "abc"), 400, "VALIDATION_ERROR", null);
        failure(get("/api/v1/stations/abc"), 400, "VALIDATION_ERROR", null);
    }

    @Test void stationDetailMatchesSearchItemAndMissingIsNotFound() throws Exception {
        JsonNode item = ok(get("/api/v1/stations").param("keyword", "잠실")).get("items").get(0);
        assertThat(ok(get("/api/v1/stations/" + item.get("id").asLong()))).isEqualTo(item);
        failure(get("/api/v1/stations/999999"), 404, "STATION_NOT_FOUND", null);
    }

    @Test void everyEndpointNeedsAToken() throws Exception {
        for (var request : List.of(get("/api/v1/lines"), get("/api/v1/stations").param("keyword", "강남"), get("/api/v1/stations/1"))) {
            String body = mvc.perform(request).andExpect(status().isUnauthorized()).andReturn().getResponse().getContentAsString();
            assertThat(mapper.readTree(body).get("error").get("code").asString()).isEqualTo("AUTH_REQUIRED");
        }
    }

    @Test void stationLookupChecksExistenceAndSupportInsideTheCallersTransaction() {
        long gangnam = jdbc.queryForObject("select id from stations where name = '강남'", Long.class);
        StationInfo info = lookup.requireSupported(gangnam);
        assertThat(info).isEqualTo(new StationInfo(gangnam, "강남", info.latitude(), info.longitude(), true));
        assertThat(lookup.require(gangnam)).isEqualTo(info);
        assertError(() -> lookup.require(999_999), ErrorCode.STATION_NOT_FOUND);
        assertError(() -> lookup.requireSupported(999_999), ErrorCode.STATION_NOT_FOUND);

        // The V11 seed has no unsupported station, so insert one in a transaction that is rolled back.
        // Seeing the uncommitted row also shows the lookup uses the caller's transaction.
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            Long unsupported = jdbc.queryForObject(
                    "insert into stations(name, latitude, longitude, is_supported) values ('미지원테스트', 37.5, 127.0, false) returning id",
                    Long.class);
            assertThat(lookup.require(unsupported).supported()).isFalse();
            assertError(() -> lookup.requireSupported(unsupported), ErrorCode.STATION_UNSUPPORTED);
            status.setRollbackOnly();
        });
        assertThat(jdbc.queryForObject("select count(*) from stations where name = '미지원테스트'", Long.class)).isZero();
    }

    @Test void openApiDocumentsTheThreeOperations() throws Exception {
        JsonNode root = mapper.readTree(mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        for (String path : List.of("/api/v1/lines", "/api/v1/stations", "/api/v1/stations/{id}")) {
            JsonNode operation = root.get("paths").get(path).get("get");
            assertThat(operation.get("security").get(0).has("bearerAuth")).as(path).isTrue();
            assertThat(operation.get("responses").has("401")).as(path).isTrue();
        }
        assertThat(root.get("paths").get("/api/v1/stations/{id}").get("get").get("responses").has("404")).isTrue();
        assertThat(root.get("components").get("schemas").propertyNames())
                .contains("SubwayLineData", "StationLineData", "StationData", "StationPageData",
                        "SubwayLineListResponse", "StationPageResponse", "StationDetailResponse");
    }

    private JsonNode ok(MockHttpServletRequestBuilder request) throws Exception {
        String body = mvc.perform(auth(request)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        JsonNode root = mapper.readTree(body);
        assertThat(root.get("success").asBoolean()).isTrue();
        return root.get("data");
    }

    private void failure(MockHttpServletRequestBuilder request, int status, String code, String detailKey) throws Exception {
        String body = mvc.perform(auth(request)).andExpect(status().is(status))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        JsonNode error = mapper.readTree(body).get("error");
        assertThat(error.get("code").asString()).isEqualTo(code);
        if (detailKey != null) assertThat(error.get("details").has(detailKey)).as(body).isTrue();
    }

    private MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder request) {
        return request.header("Authorization", "Bearer " + jwt.createAccessToken(user));
    }

    private static List<String> values(JsonNode array, String field) {
        List<String> values = new ArrayList<>();
        array.forEach(node -> values.add(node.get(field).asString()));
        return values;
    }

    private static JsonNode find(JsonNode items, String name) {
        for (JsonNode item : items) if (item.get("name").asString().equals(name)) return item;
        throw new AssertionError("no " + name);
    }

    private static void assertError(ThrowingCallable call, ErrorCode code) {
        assertThatThrownBy(call).isInstanceOfSatisfying(BusinessException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(code));
    }
}
