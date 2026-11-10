package com.mju.linkro.recommendation;

import com.mju.linkro.common.exception.BusinessException;
import com.mju.linkro.common.exception.ErrorCode;
import com.mju.linkro.recommendation.dto.RecommendationRequest;
import com.mju.linkro.recommendation.dto.RecommendationRequest.Filter;
import com.mju.linkro.recommendation.dto.RecommendationResponse;
import com.mju.linkro.recommendation.dto.RecommendationResponse.Result;
import com.mju.linkro.recommendation.service.RecommendationService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Recommendation on the real V1~V13 schema and the V11 seed (graph module, mock AI, mock congestion): the JDBC
 * repository, the jsonb columns, the unique (run_id, criterion), the neighbour query and the stale rules end to end.
 */
@Testcontainers
@SpringBootTest(properties = {
        "spring.config.import=", "spring.jpa.hibernate.ddl-auto=validate", "spring.jpa.open-in-view=false",
        "spring.jpa.show-sql=false", "auth.kakao.app-id=1234", "auth.jwt.access-token-ttl=15m",
        "auth.jwt.secret=dGVzdC1vbmx5LXNlY3JldC0wMTIzNDU2Nzg5YWJjZGVmZ2hpams=",
        "linkro.subway.module=graph", "linkro.ai.module=mock"})
@Timeout(120)
class RecommendationPostgresTest {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired RecommendationService service;
    @Autowired JdbcTemplate jdbc;

    UUID owner = UUID.randomUUID();
    UUID member = UUID.randomUUID();
    UUID room;
    long gangnam;
    long sadang;

    @BeforeEach
    void seed() {
        jdbc.update("delete from recommendation_results");
        jdbc.update("delete from recommendation_runs");
        jdbc.update("delete from meeting_members");
        jdbc.update("delete from meeting_rooms");
        jdbc.update("delete from users");
        gangnam = station("강남");
        sadang = station("사당");
        owner = user("선호");
        member = user("인준");
        room = UUID.randomUUID();
        jdbc.update("insert into meeting_rooms (id, room_code, name, created_by, appointment_at) values (?, 'ABC123', '약속', ?, ?::timestamptz)",
                room, owner, "2026-11-12T10:00:00Z");
        jdbc.update("insert into meeting_members (id, room_id, user_id, role, departure_station_id) values (?, ?, ?, 'OWNER', ?)",
                UUID.randomUUID(), room, owner, gangnam);
        jdbc.update("insert into meeting_members (id, room_id, user_id, role, departure_station_id) values (?, ?, ?, 'MEMBER', ?)",
                UUID.randomUUID(), room, member, sadang);
    }

    private long station(String name) {
        return jdbc.queryForObject("select id from stations where name = ?", Long.class, name);
    }

    private UUID user(String nickname) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into users (id, kakao_id, nickname) values (?, ?, ?)", id, id.toString(), nickname);
        return id;
    }

    @Test
    void createsStoresAndReadsBackThreeResults() {
        RecommendationResponse created = service.create(owner, room, null);

        assertThat(created.results()).extracting(Result::criterion).containsExactly("TIME", "FARE", "DISTANCE");
        assertThat(created.isStale()).isFalse();
        assertThat(created.results()).allSatisfy(result -> {
            assertThat(result.participantTimes()).hasSize(2);
            assertThat(result.recommendedStation().isSupported()).isTrue();
            assertThat(result.explanationSource()).isEqualTo("AI");
        });
        // The best station of a two-person trip is on the way: nobody needs more than the longer direct trip.
        assertThat(created.results().get(0).metrics().maxMinutes()).isLessThanOrEqualTo(
                created.results().get(0).participantTimes().stream().mapToInt(trip -> trip.travelMinutes()).max().orElseThrow());
        assertThat(created.results().get(0).nearbyAlternatives()).isNotEmpty().allSatisfy(nearby -> {
            assertThat(nearby.relation()).isIn("PREV", "NEXT");
            assertThat(nearby.relationLabel()).isEqualTo("최적역에서 1정거장");
        });

        assertThat(jdbc.queryForObject("select count(*) from recommendation_runs where room_id = ?", Integer.class, room)).isEqualTo(1);
        assertThat(jdbc.queryForList("select criterion from recommendation_results order by criterion", String.class))
                .containsExactly("DISTANCE", "FARE", "TIME");
        assertThat(jdbc.queryForObject("select jsonb_typeof(trips) || jsonb_typeof(nearby) from recommendation_results where criterion = 'TIME'", String.class))
                .isEqualTo("arrayarray");
        // trips keep only the departure station id; the name in the response is filled in on read
        assertThat(jdbc.queryForObject("select count(*) from recommendation_results, jsonb_array_elements(trips) trip "
                + "where trip ? 'departureStationId' and not trip ? 'departureStation'", Integer.class)).isEqualTo(6);
        // congestion.pct is stored as an integer; member departure stations are never recommended or shown nearby
        assertThat(jdbc.queryForObject("select count(*) from recommendation_results, jsonb_array_elements(trips) trip "
                + "where trip -> 'congestion' ->> 'pct' ~ '[.]'", Integer.class)).isZero();
        assertThat(created.appliedConstraints().excludeStationIds()).isEmpty();
        assertThat(created.results()).allSatisfy(result -> {
            assertThat(result.recommendedStation().id()).isNotIn(gangnam, sadang);
            assertThat(result.nearbyAlternatives()).extracting(nearby -> nearby.station().id()).doesNotContain(gangnam, sadang);
            assertThat(result.participantTimes()).allSatisfy(trip -> {
                assertThat(trip.travelMinutes()).isPositive();
                assertThat(trip.fareWon()).isPositive();
            });
        });
        assertThat(jdbc.queryForObject("select jsonb_typeof(criteria) || jsonb_array_length(criteria -> 'participants') from recommendation_runs", String.class))
                .isEqualTo("object2");
        assertThat(jdbc.queryForObject("select metric_value::int from recommendation_results where criterion = 'TIME'", Integer.class))
                .isEqualTo(created.results().get(0).metrics().maxMinutes());

        assertThat(service.latest(owner, room)).isEqualTo(created);
        assertThat(service.byRun(member, room, UUID.fromString(created.runId()))).isEqualTo(created);
    }

    @Test
    void excludedStationsAreNeverRecommended() {
        RecommendationResponse first = service.create(owner, room, null);
        List<Long> best = first.results().stream().map(result -> result.recommendedStation().id()).distinct().toList();
        RecommendationResponse second = service.create(owner, room, new RecommendationRequest(null, new Filter(null, null, null, best, null)));
        assertThat(second.results()).isNotEmpty().allSatisfy(result -> assertThat(best).doesNotContain(result.recommendedStation().id()));
        assertThat(second.appliedConstraints().excludeStationIds()).containsExactlyInAnyOrderElementsOf(best);
        assertThat(service.latest(owner, room).runId()).isEqualTo(second.runId());
        assertThat(service.byRun(owner, room, UUID.fromString(first.runId())).isStale()).isNull();
    }

    @Test
    void noCandidateKeepsTheRunWithoutResultRows() {
        RecommendationResponse response = service.create(owner, room, new RecommendationRequest(null, new Filter(1, null, null, null, null)));
        assertThat(response.results()).isEmpty();
        assertThat(response.message()).isEqualTo("조건에 맞는 중간역이 없습니다. 조건을 완화해 보세요.");
        assertThat(jdbc.queryForObject("select count(*) from recommendation_runs", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from recommendation_results", Integer.class)).isZero();
    }

    @Test
    void departureMissingLeavesNoRun() {
        jdbc.update("update meeting_members set departure_station_id = null where user_id = ?", member);
        assertThatThrownBy(() -> service.create(owner, room, null)).isInstanceOfSatisfying(BusinessException.class, exception -> {
            assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.DEPARTURE_MISSING);
            assertThat(exception.getDetails().get("missingUserIds")).isEqualTo(List.of(member.toString()));
        });
        assertThat(jdbc.queryForObject("select count(*) from recommendation_runs", Integer.class)).isZero();
    }

    @Test
    void staleIsComputedFromTheCurrentRoom() {
        service.create(owner, room, null);
        assertThat(service.latest(owner, room).staleReasons()).isEmpty();
        jdbc.update("update meeting_rooms set appointment_at = ?::timestamptz where id = ?", "2026-11-12T11:00:00Z", room);
        assertThat(service.latest(owner, room).staleReasons()).containsExactly("APPOINTMENT_CHANGED");
        jdbc.update("update meeting_members set departure_station_id = ? where user_id = ?", gangnam, member);
        assertThat(service.latest(owner, room).staleReasons()).containsExactly("APPOINTMENT_CHANGED", "MEMBERS_CHANGED");
        jdbc.update("update meeting_rooms set appointment_at = ?::timestamptz where id = ?", "2026-11-12T10:00:00Z", room);
        jdbc.update("update meeting_members set departure_station_id = ? where user_id = ?", sadang, member);
        assertThat(service.latest(owner, room).isStale()).isFalse();
    }

    @Test
    void bothMembersAtTheSameStationStillExcludeThatStation() {
        jdbc.update("update meeting_members set departure_station_id = ? where user_id = ?", gangnam, member);
        RecommendationResponse created = service.create(owner, room, null);
        assertThat(created.results()).isNotEmpty().allSatisfy(result -> {
            assertThat(result.recommendedStation().id()).isNotEqualTo(gangnam);
            assertThat(result.nearbyAlternatives()).extracting(nearby -> nearby.station().id()).doesNotContain(gangnam);
            assertThat(result.participantTimes()).allSatisfy(trip -> assertThat(trip.travelMinutes()).isPositive());
        });
        assertThat(created.appliedConstraints().excludeStationIds()).isEmpty();
    }

    @Test
    void aRunOfAnotherRoomIsNotFound() {
        RecommendationResponse created = service.create(owner, room, null);
        UUID otherRoom = UUID.randomUUID();
        jdbc.update("insert into meeting_rooms (id, room_code, name, created_by) values (?, 'ZZZ999', '다른방', ?)", otherRoom, owner);
        jdbc.update("insert into meeting_members (id, room_id, user_id, role) values (?, ?, ?, 'OWNER')", UUID.randomUUID(), otherRoom, owner);
        assertThatThrownBy(() -> service.byRun(owner, otherRoom, UUID.fromString(created.runId())))
                .isInstanceOfSatisfying(BusinessException.class, exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.RUN_NOT_FOUND));
        assertThat(service.latest(owner, otherRoom)).isNull();
    }
}
