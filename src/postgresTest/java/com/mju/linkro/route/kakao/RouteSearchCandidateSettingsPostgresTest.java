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
 * The #155 settings change the plain search alternatives when set as Spring properties: cut 25 minutes (default 15) and
 * grace 2 minutes (default 6). The defaults are RouteSearchCandidateGracePostgresTest and RouteSearchCandidatesPostgresTest.
 */
@Testcontainers
@SpringBootTest(properties = {
        "spring.config.import=", "spring.jpa.hibernate.ddl-auto=validate", "spring.jpa.open-in-view=false",
        "spring.jpa.show-sql=false", "auth.kakao.app-id=1234", "auth.jwt.access-token-ttl=15m",
        "auth.jwt.secret=dGVzdC1vbmx5LXNlY3JldC0wMTIzNDU2Nzg5YWJjZGVmZ2hpams=", "linkro.subway.module=graph",
        "linkro.route.timetable-candidate-cut-minutes=25", "linkro.route.timetable-candidate-grace-minutes=2"})
@org.springframework.context.annotation.Import(RouteSearchPostgresSupport.StubKakao.class)
@Timeout(90)
class RouteSearchCandidateSettingsPostgresTest extends RouteSearchPostgresSupport {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    List<String> search(String from, String to, String departAt) throws Exception {
        JsonNode data = ok(search(id(from), id(to), departAt, null), member);
        server.verify();
        return routes(data);
    }

    @Test void aLongerCutLetsTheSadangRouteIn() throws Exception {
        // 사당 (14:03:30) is 22.5 minutes after r1: cut with 15 minutes, kept with 25 (카카오맵 shows it).
        assertThat(search("창동", "잠실", "2026-11-16T12:50:00+09:00")).containsExactly(
                "13:41 1 동대문역사문화공원",
                "13:41 2 노원,건대입구",
                "14:03:30 1 사당");
    }

    @Test void aShorterGraceRemovesTheRouteTwoAndAHalfMinutesLater() throws Exception {
        // 노원·건대입구 (2 transfers, 23:36) is 2.5 minutes after 사당 (1 transfer, r1 since #185): kept with 6 minutes,
        // removed with 2.
        assertThat(search("창동", "강남", "2026-11-16T22:30:00+09:00")).containsExactly(
                "23:33:30 1 사당",
                "23:33:30 2 충무로,교대",
                "23:36 1 동대문역사문화공원");
    }
}
