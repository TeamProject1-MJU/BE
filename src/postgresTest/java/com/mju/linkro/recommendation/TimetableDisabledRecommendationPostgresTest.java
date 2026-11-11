package com.mju.linkro.recommendation;

import com.mju.linkro.recommendation.dto.RecommendationResponse;
import com.mju.linkro.recommendation.dto.RecommendationResponse.ParticipantTime;
import com.mju.linkro.recommendation.dto.RecommendationResponse.Result;
import com.mju.linkro.recommendation.service.RecommendationService;
import com.mju.linkro.subway.api.SubwayRouteFinder;
import com.mju.linkro.subway.api.TimetableRouteFinder;
import com.mju.linkro.subway.timetable.TrainTimetable;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import static org.assertj.core.api.Assertions.*;

/**
 * {@code linkro.subway.timetable.enabled=false} (#96 emergency switch): nothing of the timetable is loaded and
 * recommendations are the fixed-cost ones of #74: minutes = shortest-path seconds rounded up, departure =
 * appointment − minutes, every member ESTIMATE.
 */
@Testcontainers
@SpringBootTest(properties = {
        "spring.config.import=", "spring.jpa.hibernate.ddl-auto=validate", "spring.jpa.open-in-view=false",
        "spring.jpa.show-sql=false", "auth.kakao.app-id=1234", "auth.jwt.access-token-ttl=15m",
        "auth.jwt.secret=dGVzdC1vbmx5LXNlY3JldC0wMTIzNDU2Nzg5YWJjZGVmZ2hpams=",
        "linkro.subway.module=graph", "linkro.ai.module=mock", "linkro.subway.timetable.enabled=false"})
@Timeout(120)
class TimetableDisabledRecommendationPostgresTest {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired RecommendationService service;
    @Autowired SubwayRouteFinder finder;
    @Autowired ObjectProvider<TimetableRouteFinder> timetableRouter;
    @Autowired ObjectProvider<TrainTimetable> timetable;
    @Autowired JdbcTemplate jdbc;

    long station(String name) {
        return jdbc.queryForObject("select id from stations where name = ?", Long.class, name);
    }

    @Test
    void recommendationsUseFixedCostsAsBefore() {
        assertThat(timetableRouter.getIfAvailable()).isNull();
        assertThat(timetable.getIfAvailable()).isNull();

        UUID owner = UUID.randomUUID();
        UUID member = UUID.randomUUID();
        UUID room = UUID.randomUUID();
        Instant appointment = Instant.parse("2026-11-12T10:00:00Z");
        for (UUID user : new UUID[]{owner, member}) {
            jdbc.update("insert into users (id, kakao_id, nickname) values (?, ?, ?)", user, user.toString(), "u" + user.toString().substring(0, 4));
        }
        jdbc.update("insert into meeting_rooms (id, room_code, name, created_by, appointment_at) values (?, 'OFF001', '약속', ?, ?::timestamptz)",
                room, owner, appointment.toString());
        jdbc.update("insert into meeting_members (id, room_id, user_id, role, departure_station_id) values (?, ?, ?, 'OWNER', ?)",
                UUID.randomUUID(), room, owner, station("강남"));
        jdbc.update("insert into meeting_members (id, room_id, user_id, role, departure_station_id) values (?, ?, ?, 'MEMBER', ?)",
                UUID.randomUUID(), room, member, station("사당"));

        RecommendationResponse response = service.create(owner, room, null);
        assertThat(response.results()).isNotEmpty();
        for (Result result : response.results()) {
            for (ParticipantTime trip : result.participantTimes()) {
                assertThat(trip.source()).isEqualTo("ESTIMATE");
                assertThat(trip.boardingTrain()).isNull();
                assertThat(trip.legs()).isEmpty();
                int seconds = finder.shortestPath(trip.departureStation().id(), result.recommendedStation().id()).totalSeconds();
                assertThat(trip.travelMinutes()).isEqualTo((seconds + 59) / 60);
                assertThat(OffsetDateTime.parse(trip.departureAt()).toInstant()).isEqualTo(appointment.minusSeconds(60L * trip.travelMinutes()));
            }
        }
    }
}
