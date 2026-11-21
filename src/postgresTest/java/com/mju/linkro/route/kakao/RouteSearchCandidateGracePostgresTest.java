package com.mju.linkro.route.kakao;

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

import static com.mju.linkro.route.kakao.RouteSearchCandidatesPostgresTest.routes;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plain search alternatives with the default cut (15 min) and grace (6 min) of #155 on the migrated database and the
 * bundled timetable (station access 60 s, transfer overrides #151). Monday 2026-11-16. A route with more transfers stays
 * unless it arrives more than 6 minutes after one with fewer.
 */
@Testcontainers
@SpringBootTest(properties = {
        "spring.config.import=", "spring.jpa.hibernate.ddl-auto=validate", "spring.jpa.open-in-view=false",
        "spring.jpa.show-sql=false", "auth.kakao.app-id=1234", "auth.jwt.access-token-ttl=15m",
        "auth.jwt.secret=dGVzdC1vbmx5LXNlY3JldC0wMTIzNDU2Nzg5YWJjZGVmZ2hpams=", "linkro.subway.module=graph"})
@org.springframework.context.annotation.Import(RouteSearchPostgresSupport.StubKakao.class)
@Timeout(90)
class RouteSearchCandidateGracePostgresTest extends RouteSearchPostgresSupport {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    List<String> search(String from, String to, String departAt) throws Exception {
        JsonNode data = ok(search(id(from), id(to), departAt, null), member);
        assertThat(data.get("routeSource").asString()).isEqualTo("INTERNAL");
        server.verify();
        return routes(data);
    }

    @Test void changdongToJamsilKeepsTheLine7RouteArrivingTogether() throws Exception {
        // 노원·건대입구 (2 transfers) arrives with r1 동대문역사문화공원 (1): kept. 충무로·을지로3가 and 동대문·신설동·성수
        // (13:52, 11 minutes later) are removed; 사당 (14:03:30, +22.5) is cut.
        assertThat(search("창동", "잠실", "2026-11-16T12:50:00+09:00")).containsExactly(
                "13:41 1 동대문역사문화공원",
                "13:41 2 노원,건대입구");
    }

    @Test void changdongToGangnamMondayNight() throws Exception {
        assertThat(search("창동", "강남", "2026-11-16T22:30:00+09:00")).containsExactly(
                "23:33:30 1 사당",
                "23:33:30 2 충무로,교대",
                "23:36 1 동대문역사문화공원",
                "23:36 2 노원,건대입구");
    }

    @Test void nowonToJongnoKeepsTheRouteFiveMinutesLater() throws Exception {
        // 태릉입구·동묘앞 (2 transfers, 13:41) is 5 minutes after 동대문역사문화공원 (1 transfer): within the grace.
        assertThat(search("노원", "종로3가", "2026-11-16T13:00:00+09:00")).containsExactly(
                "13:36 1 동대문역사문화공원",
                "13:37 1 동대문",
                "13:38 1 충무로",
                "13:41 2 태릉입구,동묘앞",
                "13:49 1 군자");
    }

    @Test void gangnamToJongnoStaysOneRoute() throws Exception {
        // The first alternative (을지로4가, 13:49) is 17.5 minutes after r1: cut.
        assertThat(search("강남", "종로3가", "2026-11-16T13:00:00+09:00")).containsExactly("13:31:30 1 교대");
    }

    @Test void justBeforeMidnightStaysOneRoute() throws Exception {
        assertThat(search("창동", "강남", "2026-11-16T23:50:00+09:00")).containsExactly("00:54 2 충무로,교대");
    }

    @Test void aDirectRouteStaysOneRoute() throws Exception {
        assertThat(search("창동", "동대문", "2026-11-16T13:00:00+09:00")).containsExactly("13:25:30 0 ");
    }
}
