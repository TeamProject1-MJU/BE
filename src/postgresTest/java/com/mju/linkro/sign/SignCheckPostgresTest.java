package com.mju.linkro.sign;

import com.mju.linkro.auth.jwt.JwtProvider;
import com.mju.linkro.user.domain.User;
import com.mju.linkro.user.repository.UserRepository;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** POST /ai/direction-check on PostgreSQL with the real migrations: stationId/lineId become names from the seed data; the mock AI answers. */
@Testcontainers
@SpringBootTest(properties = {
        "spring.config.import=", "spring.jpa.hibernate.ddl-auto=validate", "spring.jpa.open-in-view=false",
        "spring.jpa.show-sql=false", "auth.kakao.app-id=1234", "auth.jwt.access-token-ttl=15m",
        "auth.jwt.secret=dGVzdC1vbmx5LXNlY3JldC0wMTIzNDU2Nzg5YWJjZGVmZ2hpams="})
@Timeout(45)
class SignCheckPostgresTest {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    /** A real 8x8 PNG (the sign service decodes the upload before calling the AI). */
    static final String PNG = "iVBORw0KGgoAAAANSUhEUgAAAAgAAAAICAIAAABLbSncAAAAbElEQVR4XhXNQRUAUQhCUZOYhCQmeUlIQhISzR+XXA7ODDtouIHBQ4YOM8suWm5h8ZKl+0CskDiBsIioHhx76LiDw0eO3oN/4FVf+J8h0PduzBqZ8x/bxNQPwgaFy192SGgelC0q13/CJaXlA+odUoED3fDeAAAAAElFTkSuQmCC";

    @Autowired WebApplicationContext context;
    @Autowired JwtProvider jwt;
    @Autowired UserRepository users;
    @Autowired JdbcTemplate jdbc;
    MockMvc mvc;
    UUID user;

    @BeforeEach void setup() {
        jdbc.update("delete from users");
        user = users.saveAndFlush(new User("sign-user", "표지판")).getId();
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    private org.springframework.test.web.servlet.ResultActions post(String route) throws Exception {
        return mvc.perform(multipart("/api/v1/ai/direction-check")
                .file(new MockMultipartFile("image", "sign.png", "image/png", java.util.Base64.getDecoder().decode(PNG)))
                .file(new MockMultipartFile("route", "", "text/plain", route.getBytes(StandardCharsets.UTF_8)))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwt.createAccessToken(user)));
    }

    @Test void seededStationAndLineBecomeTheExpectedNames() throws Exception {
        long gangnam = jdbc.queryForObject("select id from stations where name = '강남' limit 1", Long.class);
        long line2 = jdbc.queryForObject("select id from subway_lines where line_code = '2'", Long.class);

        post("{\"stationId\":" + gangnam + ",\"lineId\":" + line2 + ",\"direction\":\"INNER\",\"headsign\":\"사당 방면\"}")
                .andExpect(status().isOk()).andExpect(content().json("""
                        {"success":true,"data":{"result":"CORRECT","reason":null,
                         "expected":{"stationName":"강남","lineName":"2호선","headsign":"사당 방면"}}}""", JsonCompareMode.LENIENT));
    }

    @Test void unknownStationIs404AndUnknownLineIs400() throws Exception {
        long line2 = jdbc.queryForObject("select id from subway_lines where line_code = '2'", Long.class);
        long gangnam = jdbc.queryForObject("select id from stations where name = '강남' limit 1", Long.class);

        post("{\"stationId\":999999,\"lineId\":" + line2 + ",\"headsign\":\"사당 방면\"}")
                .andExpect(status().isNotFound()).andExpect(content().json("{\"error\":{\"code\":\"STATION_NOT_FOUND\"}}", JsonCompareMode.LENIENT));
        post("{\"stationId\":" + gangnam + ",\"lineId\":999999,\"headsign\":\"사당 방면\"}")
                .andExpect(status().isBadRequest()).andExpect(content().json("{\"error\":{\"code\":\"VALIDATION_ERROR\"}}", JsonCompareMode.LENIENT));
    }
}
