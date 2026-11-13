package com.mju.linkro.route.kakao;

import com.mju.linkro.auth.jwt.JwtProvider;
import com.mju.linkro.user.domain.User;
import com.mju.linkro.user.repository.UserRepository;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
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
import org.springframework.http.HttpStatus;
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
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;


/**
 * POST /routes/search with the train timetable (graph module): room appointment mode (#122), solo mode (#130) and the
 * Kakao flow when the timetable has no route. Kakao is stubbed; Kakao-first searches without the timetable are in
 * {@link RouteSearchKakaoPostgresTest}.
 */
@Testcontainers
@ExtendWith(OutputCaptureExtension.class)
@SpringBootTest(properties = {
        "spring.config.import=", "spring.jpa.hibernate.ddl-auto=validate", "spring.jpa.open-in-view=false",
        "spring.jpa.show-sql=false", "auth.kakao.app-id=1234", "auth.jwt.access-token-ttl=15m",
        "auth.jwt.secret=dGVzdC1vbmx5LXNlY3JldC0wMTIzNDU2Nzg5YWJjZGVmZ2hpams=", "linkro.subway.module=graph"})
@org.springframework.context.annotation.Import(RouteSearchPostgresSupport.StubKakao.class)
@Timeout(90)
class RouteSearchPostgresTest extends RouteSearchPostgresSupport {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Test void internalFaresAreTheFareTableFaresRecommendationsUse() throws Exception {
        // #109: /routes/search (INTERNAL, no Kakao key) and recommendations charge the same fare-table fare for a pair.
        useKakao("", Duration.ofSeconds(3), Duration.ofSeconds(5));
        long bulgwang = id("불광");
        long hapjeong = id("합정");
        long nowon = id("노원");
        long sadang = id("사당");
        var members = List.of(new com.mju.linkro.recommendation.service.Candidate.Member(UUID.randomUUID(), "a", bulgwang),
                new com.mju.linkro.recommendation.service.Candidate.Member(UUID.randomUUID(), "b", nowon));
        var evaluation = engine.evaluate(members, com.mju.linkro.recommendation.dto.RecommendationResponse.AppliedConstraints.none(),
                java.time.Instant.parse("2026-11-12T04:00:00Z"), java.time.Instant.parse("2026-11-12T03:00:00Z"));
        int recommendedToHapjeong = evaluation.candidates().get(hapjeong).trips().get(0).fareWon();
        int recommendedToSadang = evaluation.candidates().get(sadang).trips().get(1).fareWon();
        JsonNode toHapjeong = ok(search(bulgwang, hapjeong, "2026-11-12T13:00:00+09:00", null), member);
        JsonNode toSadang = ok(search(nowon, sadang, "2026-11-12T13:00:00+09:00", null), member);
        assertThat(toHapjeong.get("routeSource").asString()).isEqualTo("INTERNAL");
        assertThat(toSadang.get("routeSource").asString()).isEqualTo("INTERNAL");
        assertThat(toHapjeong.get("routes").get(0).get("summary").get("fare").asInt()).isEqualTo(recommendedToHapjeong).isEqualTo(1550);
        assertThat(toSadang.get("routes").get(0).get("summary").get("fare").asInt()).isEqualTo(recommendedToSadang).isEqualTo(1850);
        assertThat(fareTable.fareBetween(bulgwang, hapjeong)).hasValue(1550);
        server.verify();
    }


    @Test void roomMembersUseTheAppointmentAndOthersAreRejected() throws Exception {
        // 2027-01-05 (Tuesday) 08:30 in Seoul. #122: an active appointment uses the timetable route (no Kakao call), so the
        // route is the latest one arriving by 08:30 instead of Kakao's route ending exactly at 08:30.
        java.time.Instant appointment = java.time.Instant.parse("2027-01-04T23:30:00Z");
        String room = createRoom(member, "2027-01-04T23:30:00Z");
        var expected = timetableRoutes.latestDepartures(sadang, appointment, Set.of(gangnam), 0).get(gangnam);

        JsonNode data = ok(search(gangnam, sadang, null, room), member);

        server.verify(); // no expectations: a Kakao request would have failed
        assertThat(data.get("routeSource").asString()).isEqualTo("INTERNAL");
        JsonNode route = data.get("routes").get(0);
        assertThat(instant(route.get("summary").get("departureAt"))).isEqualTo(expected.leaveAt());
        assertThat(instant(route.get("summary").get("arrivalAt"))).isEqualTo(expected.arriveAt()).isBeforeOrEqualTo(appointment);
        assertThat(route.get("summary").get("arrivesAfterAppointment").asBoolean()).isFalse();
        // Boarding before 08:30 at 강남: every stop is in the 08:00 slot of that Tuesday.
        JsonNode first = route.get("steps").get(0).get("stations").get(0);
        assertThat(first.get("slotDate").asString()).isEqualTo("2027-01-05");
        assertThat(first.get("slotStart").asString()).isEqualTo("08:00");
        assertCongestion(first.get("congestion"), 93, "NORMAL", "PREDICTED");

        failure(search(gangnam, sadang, null, room), stranger, 403, "NOT_ROOM_MEMBER");
        failure(search(gangnam, sadang, null, UUID.randomUUID().toString()), member, 404, "ROOM_NOT_FOUND");
        failure(search(gangnam, sadang, null, "not-a-uuid"), member, 400, "VALIDATION_ERROR");
    }

    @Test void stationsAreCheckedBeforeKakao() throws Exception {
        failure(search(999_999, sadang, null, null), member, 404, "STATION_NOT_FOUND");
        jdbc.update("update stations set is_supported = false where id = ?", sadang);
        try {
            failure(search(gangnam, sadang, null, null), member, 422, "STATION_UNSUPPORTED");
        } finally {
            jdbc.update("update stations set is_supported = true where id = ?", sadang);
        }
        failure(post("/api/v1/routes/search").contentType(MediaType.APPLICATION_JSON).content("{}"), member, 400, "VALIDATION_ERROR");
        server.verify(); // none of these reached Kakao
    }

    @Test void authenticationIsRequired() throws Exception {
        String body = mvc.perform(search(gangnam, sadang, null, null)).andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(mapper.readTree(body).get("error").get("code").asString()).isEqualTo("AUTH_REQUIRED");
    }


    @Test void openApiDocumentsTheEndpoint() throws Exception {
        JsonNode root = mapper.readTree(mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/v3/api-docs"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        JsonNode operation = root.get("paths").get("/api/v1/routes/search").get("post");
        assertThat(operation.get("security").get(0).has("bearerAuth")).isTrue();
        List<String> codes = new ArrayList<>();
        operation.get("responses").propertyNames().forEach(codes::add);
        assertThat(codes).contains("200", "400", "401", "403", "404", "422");
        assertThat(root.get("components").get("schemas").get("RouteSearchData").toString()).contains("KAKAO", "INTERNAL");
        assertThat(root.get("components").get("schemas").get("RouteSearchData").toString()).contains("appointmentPassed", "notice");
        assertThat(root.get("components").get("schemas").get("RouteSummaryData").toString())
                .contains("departureAt", "noService", "serviceNotice", "arrivesAfterAppointment", "waitSec", "waitKind", "totalWithWaitSec",
                        "slackSeconds");
        assertThat(root.get("components").get("schemas").get("RouteSearchRequest").toString()).contains("arriveAt");
        assertThat(operation.get("responses").get("422").get("description").asString()).contains("NO_SERVICE_TIME", "ARRIVAL_UNREACHABLE");
    }


    /** Appointment Tuesday 05:40, 600 s route: leave 05:30, before the 05:36:00 first train -> arrive 05:46. */
    @Test void aDawnAppointmentPushedToTheFirstTrainArrivesAfterIt() throws Exception {
        String room = createRoom(member, "2027-01-04T20:40:00Z");
        server.expect(ExpectedCount.once(), requestTo(startsWith(URL))).andRespond(withSuccess(KAKAO_OK, MediaType.APPLICATION_JSON));

        JsonNode data = ok(search(gangnam, sadang, null, room), member);

        JsonNode summary = data.get("routes").get(0).get("summary");
        assertThat(data.get("appointmentPassed").asBoolean()).isFalse();
        assertThat(summary.get("noService").asBoolean()).isTrue();
        assertThat(summary.get("departureAt").asString()).isEqualTo("2027-01-05T05:36:00+09:00");
        assertThat(summary.get("arrivalAt").asString()).isEqualTo("2027-01-05T05:46:00+09:00");
        assertThat(summary.get("arrivesAfterAppointment").asBoolean()).isTrue();
    }


    /**
     * #122: in a room with an active appointment the route of each member is the recommendation's trip to the same station
     * (one latestDepartures search for all members): the same leaving and arrival seconds, stations, transfers, fare and
     * minutes, without calling Kakao.
     */
    @Test void roomRouteIsTheRecommendationTrip() throws Exception {
        long jongno = id("종로3가");
        long nowon = id("노원");
        java.time.Instant appointment = java.time.Instant.parse("2027-01-05T10:00:00Z"); // Tuesday 19:00 in Seoul
        var members = List.of(new com.mju.linkro.recommendation.service.Candidate.Member(member, "a", gangnam),
                new com.mju.linkro.recommendation.service.Candidate.Member(UUID.randomUUID(), "b", nowon));
        var evaluation = engine.evaluate(members, com.mju.linkro.recommendation.dto.RecommendationResponse.AppliedConstraints.none(),
                appointment, java.time.Instant.parse("2026-11-12T03:00:00Z"));
        assertThat(evaluation.source()).isEqualTo("TIMETABLE");
        String room = createRoom(member, "2027-01-05T10:00:00Z");

        for (var trip : evaluation.candidates().get(jongno).trips()) {
            long origin = trip.member().departureStationId();
            JsonNode data = ok(search(origin, jongno, null, room), member);
            assertThat(data.get("routeSource").asString()).isEqualTo("INTERNAL");
            assertThat(data.get("routes").size()).isEqualTo(1);
            JsonNode summary = data.get("routes").get(0).get("summary");
            assertThat(instant(summary.get("departureAt"))).as("leaveAt from %d", origin).isEqualTo(trip.timed().leaveAt());
            assertThat(instant(summary.get("arrivalAt"))).as("arriveAt from %d", origin).isEqualTo(trip.timed().arriveAt())
                    .isBeforeOrEqualTo(appointment);
            int total = summary.get("totalTimeSec").asInt();
            assertThat(total).isEqualTo(Duration.between(trip.timed().leaveAt(), trip.timed().arriveAt()).toSeconds());
            // travelMinutes of the recommendation rounds the same seconds up.
            assertThat((total + 59) / 60).isEqualTo(trip.minutes());
            assertThat(summary.get("transferCount").asInt()).isEqualTo(trip.path().transferCount());
            assertThat(summary.get("fare").asInt()).isEqualTo(trip.fareWon());
            assertThat(summary.get("arrivesAfterAppointment").asBoolean()).isFalse();
            assertThat(responseStations(data.get("routes").get(0))).isEqualTo(pathStations(trip.path()));
        }
        assertThat(evaluation.candidates().get(jongno).trips()).hasSize(2);
        server.verify(); // no expectations: a Kakao request would have failed
    }


    // ---- #130 solo mode: no room, or a room without an active appointment ----

    /**
     * The same member, station and time give the recommendation's trip without an appointment (departingAt from the
     * request time, #107 included): the same leaving and arrival seconds, stations, transfers, fare and minutes, on a
     * weekday and a Saturday, without calling Kakao.
     */
    @Test void soloRouteIsTheRecommendationTrip(CapturedOutput output) throws Exception {
        long jongno = id("종로3가");
        long nowon = id("노원");
        for (String requested : List.of("2026-11-12T13:00:00+09:00", "2026-11-14T13:00:00+09:00")) {
            java.time.Instant at = OffsetDateTime.parse(requested).toInstant();
            var members = List.of(new com.mju.linkro.recommendation.service.Candidate.Member(member, "a", gangnam),
                    new com.mju.linkro.recommendation.service.Candidate.Member(UUID.randomUUID(), "b", nowon));
            var evaluation = engine.evaluate(members, com.mju.linkro.recommendation.dto.RecommendationResponse.AppliedConstraints.none(),
                    null, at);
            assertThat(evaluation.source()).isEqualTo("TIMETABLE");
            assertThat(evaluation.candidates().get(jongno).trips()).hasSize(2);
            for (var trip : evaluation.candidates().get(jongno).trips()) {
                long origin = trip.member().departureStationId();
                JsonNode data = ok(search(origin, jongno, requested, null), member);
                assertThat(data.get("routeSource").asString()).isEqualTo("INTERNAL");
                assertThat(data.get("routes").size()).isEqualTo(1);
                assertThat(data.get("appointmentPassed").asBoolean()).isFalse();
                JsonNode route = data.get("routes").get(0);
                assertThat(route.get("routeType").asString()).isEqualTo("FASTEST");
                JsonNode summary = route.get("summary");
                String as = requested + " from " + origin;
                assertThat(summary.get("fareSource").asString()).isEqualTo("ESTIMATED");
                assertThat(instant(summary.get("departureAt"))).as(as).isEqualTo(trip.timed().leaveAt());
                assertThat(instant(summary.get("arrivalAt"))).as(as).isEqualTo(trip.timed().arriveAt());
                int total = summary.get("totalTimeSec").asInt();
                assertThat(total).isEqualTo(Duration.between(trip.timed().leaveAt(), trip.timed().arriveAt()).toSeconds());
                assertThat((total + 59) / 60).isEqualTo(trip.minutes());
                assertThat(summary.get("transferCount").asInt()).isEqualTo(trip.path().transferCount());
                assertThat(summary.get("fare").asInt()).isEqualTo(trip.fareWon());
                assertThat(responseStations(route)).isEqualTo(pathStations(trip.path()));
                // The wait before leaving is separate from totalTimeSec.
                assertThat(summary.get("waitKind").asString()).isEqualTo("TIMETABLE");
                assertThat(summary.get("waitSec").asInt()).isEqualTo(Duration.between(at, trip.timed().leaveAt()).toSeconds()).isNotNegative();
                assertThat(summary.get("totalWithWaitSec").asInt()).isEqualTo(Duration.between(at, trip.timed().arriveAt()).toSeconds())
                        .isEqualTo(summary.get("waitSec").asInt() + total);
                assertThat(summary.get("noService").asBoolean()).isFalse();
                assertThat(summary.get("arrivesAfterAppointment").asBoolean()).isFalse();
            }
        }
        server.verify(); // no expectations: a Kakao request would have failed
        assertThat(output.getAll()).contains("uses INTERNAL: timetable route from the request time")
                .doesNotContain(" attempt 1: ").doesNotContain(KEY);
    }

    /** 강남 -> 사당 on 2호선: first trains from 강남 are 05:36:00 weekdays, 05:35:30 Saturdays (as #120). */
    @Test void soloBeforeTheFirstTrainIsNoService() throws Exception {
        String[][] cases = {
                {"2026-11-12T03:00:00+09:00", "2026-11-12T05:36:00+09:00", "11월 12일 05:36"},
                {"2026-11-12T05:00:00+09:00", "2026-11-12T05:36:00+09:00", "11월 12일 05:36"},
                // Saturday 01:30 is after Friday's last train: Saturday's first train.
                {"2026-11-14T01:30:00+09:00", "2026-11-14T05:35:30+09:00", "11월 14일 05:35"}};
        for (String[] c : cases) {
            JsonNode data = ok(search(gangnam, sadang, c[0], null), member);
            assertThat(data.get("routeSource").asString()).isEqualTo("INTERNAL");
            assertSoloNoService(data, c[0], c[1], c[2]);
        }
        server.verify();
    }

    /**
     * Saturday 23:50 is still Saturday's service day and other trains still run, but the last 강남 -> 교대 train left at
     * 23:45: the route is searched again from Sunday 03:00 and takes Sunday's first train 05:35:30.
     */
    @Test void soloAfterTheRoutesLastTrainTakesTheNextServiceDay(CapturedOutput output) throws Exception {
        java.time.Instant saturdayNight = OffsetDateTime.parse("2026-11-14T23:50:00+09:00").toInstant();
        assertThat(timetableRoutes.departingAt(gangnam, saturdayNight).routeTo(sadang)).isEmpty();
        JsonNode data = ok(search(gangnam, sadang, "2026-11-14T23:50:00+09:00", null), member);
        assertThat(data.get("routeSource").asString()).isEqualTo("INTERNAL");
        assertSoloNoService(data, "2026-11-14T23:50:00+09:00", "2026-11-15T05:35:30+09:00", "11월 15일 05:35");
        server.verify();
        assertThat(output.getAll()).contains("timetable route from the request time (next service day)");
    }

    /** The case measured for #130: Saturday 00:50 (Friday's service day) 수유 -> 사당 has no route until Saturday. */
    @Test void soloSuyuToSadangAfterMidnightTakesSaturdaysFirstTrain() throws Exception {
        long suyu = id("수유");
        java.time.Instant requested = OffsetDateTime.parse("2026-11-14T00:50:00+09:00").toInstant();
        assertThat(timetableRoutes.departingAt(suyu, requested).routeTo(sadang)).isEmpty();
        var saturday = timetableRoutes.departingAt(suyu, OffsetDateTime.parse("2026-11-14T03:00:00+09:00").toInstant())
                .routeTo(sadang).orElseThrow();

        JsonNode summary = ok(search(suyu, sadang, "2026-11-14T00:50:00+09:00", null), member).get("routes").get(0).get("summary");

        assertThat(instant(summary.get("departureAt"))).isEqualTo(saturday.leaveAt());
        assertThat(instant(summary.get("arrivalAt"))).isEqualTo(saturday.arriveAt());
        assertThat(summary.get("noService").asBoolean()).isTrue();
        assertThat(summary.get("serviceNotice").asString()).startsWith("요청한 시각에는 이 경로를 운행하는 열차가 없어 첫차(11월 14일 ");
        assertThat(summary.get("waitSec").asInt()).isEqualTo(Duration.between(requested, saturday.leaveAt()).toSeconds());
        server.verify();
    }

    private static void assertSoloNoService(JsonNode data, String requested, String departureAt, String firstTrain) {
        JsonNode summary = data.get("routes").get(0).get("summary");
        assertThat(summary.get("noService").asBoolean()).as(requested).isTrue();
        assertThat(summary.get("arrivesAfterAppointment").asBoolean()).isFalse();
        assertThat(summary.get("departureAt").asString()).as(requested).isEqualTo(departureAt);
        assertThat(OffsetDateTime.parse(summary.get("arrivalAt").asString()))
                .isEqualTo(OffsetDateTime.parse(departureAt).plusSeconds(summary.get("totalTimeSec").asInt()));
        assertThat(summary.get("serviceNotice").asString()).contains("첫차(" + firstTrain + " 출발)");
        assertThat(summary.get("waitSec").asInt())
                .isEqualTo(Duration.between(OffsetDateTime.parse(requested), OffsetDateTime.parse(departureAt)).toSeconds());
        assertThat(data.get("appointmentPassed").asBoolean()).isFalse();
        assertThat(data.get("notice").isNull()).isTrue();
    }

    /** Running hours: leaving is the next train, waitSec after the request, and nothing is no-service. */
    @Test void soloRunningHoursLeaveWithTheNextTrain() throws Exception {
        for (String departAt : List.of("2026-11-12T14:00:00+09:00", "2026-11-12T23:30:00+09:00")) {
            JsonNode data = ok(search(gangnam, sadang, departAt, null), member);
            JsonNode summary = data.get("routes").get(0).get("summary");
            assertThat(data.get("routeSource").asString()).isEqualTo("INTERNAL");
            assertThat(summary.get("noService").asBoolean()).as(departAt).isFalse();
            assertThat(summary.get("serviceNotice").isNull()).isTrue();
            OffsetDateTime requested = OffsetDateTime.parse(departAt);
            OffsetDateTime departure = OffsetDateTime.parse(summary.get("departureAt").asString());
            assertThat(departure).isAfterOrEqualTo(requested).isBefore(requested.plusMinutes(15));
            assertThat(summary.get("waitSec").asInt()).isEqualTo(Duration.between(requested, departure).toSeconds());
            assertThat(OffsetDateTime.parse(summary.get("arrivalAt").asString()))
                    .isEqualTo(departure.plusSeconds(summary.get("totalTimeSec").asInt()))
                    .isEqualTo(requested.plusSeconds(summary.get("totalWithWaitSec").asInt()));
            assertThat(data.get("appointmentPassed").asBoolean()).isFalse();
            assertThat(data.get("notice").isNull()).isTrue();
        }
        server.verify();
    }

    /** A room whose appointment has passed is solo mode from now; appointmentPassed and its notice stay (#120). */
    @Test void aPassedAppointmentIsReportedAndDepartAtIgnored() throws Exception {
        String room = createRoom(member, "2020-01-01T00:00:00Z");

        JsonNode data = ok(search(gangnam, sadang, "2026-11-12T14:00:00+09:00", room), member);

        server.verify(); // no expectations: solo mode does not call Kakao
        assertThat(data.get("routeSource").asString()).isEqualTo("INTERNAL");
        assertThat(data.get("appointmentPassed").asBoolean()).isTrue();
        assertThat(data.get("notice").asString()).isEqualTo("약속 시각이 이미 지나 지금 출발 기준으로 안내합니다.");
        JsonNode summary = data.get("routes").get(0).get("summary");
        assertThat(summary.get("departureAt").asString()).isNotEqualTo("2026-11-12T14:00:00+09:00");
        assertThat(summary.get("waitKind").asString()).isEqualTo("TIMETABLE");
        assertThat(summary.get("arrivesAfterAppointment").asBoolean()).isFalse();
    }

    /** A room without an appointment is solo mode (#130): the timetable route from departAt, Kakao not called. */
    @Test void aRoomWithoutAnAppointmentIsSoloMode() throws Exception {
        String body = mvc.perform(auth(post("/api/v1/rooms").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"약속 없음\"}"), member))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        String room = mapper.readTree(body).get("data").get("id").asString();
        java.time.Instant requested = OffsetDateTime.parse("2026-11-12T14:00:00+09:00").toInstant();
        var expected = timetableRoutes.departingAt(gangnam, requested).routeTo(sadang).orElseThrow();

        JsonNode data = ok(search(gangnam, sadang, "2026-11-12T14:00:00+09:00", room), member);

        server.verify(); // no expectations: a Kakao request would have failed
        assertThat(data.get("routeSource").asString()).isEqualTo("INTERNAL");
        assertThat(data.get("appointmentPassed").asBoolean()).isFalse();
        JsonNode summary = data.get("routes").get(0).get("summary");
        assertThat(instant(summary.get("departureAt"))).isEqualTo(expected.leaveAt());
        assertThat(instant(summary.get("arrivalAt"))).isEqualTo(expected.arriveAt());
        assertThat(summary.get("waitKind").asString()).isEqualTo("TIMETABLE");
        assertThat(summary.get("slackSeconds").isNull()).isTrue();
    }

    // ---- #130 arrival mode (arriveAt): the latest departure arriving by the requested time ----

    /** The next {@code day} after today in Seoul (1~7 days ahead), at {@code time}: inside the 30-day arriveAt limit. */
    private static String next(java.time.DayOfWeek day, String time) {
        java.time.LocalDate date = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Seoul")).plusDays(1)
                .with(java.time.temporal.TemporalAdjusters.nextOrSame(day));
        return date + "T" + time + "+09:00";
    }

    private MockHttpServletRequestBuilder arrive(long origin, long destination, String arriveAt, String departAt, String roomId) {
        StringBuilder body = new StringBuilder("{\"originStationId\":" + origin + ",\"destinationStationId\":" + destination
                + ",\"arriveAt\":\"" + arriveAt + '"');
        if (departAt != null) body.append(",\"departAt\":\"").append(departAt).append('"');
        if (roomId != null) body.append(",\"roomId\":\"").append(roomId).append('"');
        return post("/api/v1/routes/search").contentType(MediaType.APPLICATION_JSON).content(body.append('}').toString());
    }

    private JsonNode error(MockHttpServletRequestBuilder request, int status) throws Exception {
        String body = mvc.perform(auth(request, member)).andExpect(status().is(status)).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        return mapper.readTree(body).get("error");
    }

    /**
     * arriveAt gives the trip recommendations compute for an appointment at that time (one latestDepartures search): the
     * same leaving and arrival seconds, stations, transfers, fare and minutes, on a weekday and a Saturday, without Kakao.
     */
    @Test void arrivalModeIsTheRecommendationTripForThatAppointment(CapturedOutput output) throws Exception {
        long jongno = id("종로3가");
        long nowon = id("노원");
        for (String arriveAt : List.of(next(java.time.DayOfWeek.TUESDAY, "19:00:00"), next(java.time.DayOfWeek.SATURDAY, "19:00:00"))) {
            java.time.Instant target = OffsetDateTime.parse(arriveAt).toInstant();
            var members = List.of(new com.mju.linkro.recommendation.service.Candidate.Member(member, "a", gangnam),
                    new com.mju.linkro.recommendation.service.Candidate.Member(UUID.randomUUID(), "b", nowon));
            var evaluation = engine.evaluate(members, com.mju.linkro.recommendation.dto.RecommendationResponse.AppliedConstraints.none(),
                    target, java.time.Instant.now());
            assertThat(evaluation.candidates().get(jongno).trips()).hasSize(2);
            for (var trip : evaluation.candidates().get(jongno).trips()) {
                long origin = trip.member().departureStationId();
                JsonNode data = ok(arrive(origin, jongno, arriveAt, null, null), member);
                String as = arriveAt + " from " + origin;
                assertThat(data.get("routeSource").asString()).isEqualTo("INTERNAL");
                assertThat(data.get("routes").size()).isEqualTo(1);
                JsonNode route = data.get("routes").get(0);
                assertThat(route.get("routeType").asString()).isEqualTo("FASTEST");
                JsonNode summary = route.get("summary");
                assertThat(instant(summary.get("departureAt"))).as(as).isEqualTo(trip.timed().leaveAt());
                assertThat(instant(summary.get("arrivalAt"))).as(as).isEqualTo(trip.timed().arriveAt()).isBeforeOrEqualTo(target);
                int total = summary.get("totalTimeSec").asInt();
                assertThat(total).isEqualTo(Duration.between(trip.timed().leaveAt(), trip.timed().arriveAt()).toSeconds());
                assertThat((total + 59) / 60).isEqualTo(trip.minutes());
                assertThat(summary.get("transferCount").asInt()).isEqualTo(trip.path().transferCount());
                assertThat(summary.get("fare").asInt()).isEqualTo(trip.fareWon());
                assertThat(summary.get("fareSource").asString()).isEqualTo("ESTIMATED");
                assertThat(responseStations(route)).isEqualTo(pathStations(trip.path()));
                assertThat(summary.get("slackSeconds").asInt()).isEqualTo(Duration.between(trip.timed().arriveAt(), target).toSeconds());
                assertThat(summary.get("noService").asBoolean()).isFalse();
                assertThat(summary.get("waitSec").isNull()).isTrue();
                assertThat(summary.get("waitKind").isNull()).isTrue();
                assertThat(summary.get("totalWithWaitSec").isNull()).isTrue();
            }
        }
        server.verify(); // no expectations: a Kakao request would have failed
        assertThat(output.getAll()).contains("uses INTERNAL: timetable route arriving by the requested time").doesNotContain(" attempt 1: ");
    }

    /** In a room with an appointment, a different arriveAt wins; without arriveAt the room appointment mode answers as before. */
    @Test void arriveAtWinsOverTheRoomAppointment() throws Exception {
        String appointment = next(java.time.DayOfWeek.TUESDAY, "19:00:00");
        String arriveAt = next(java.time.DayOfWeek.TUESDAY, "08:30:00");
        String room = createRoom(member, OffsetDateTime.parse(appointment).toInstant().toString());
        var expected = timetableRoutes.latestDepartures(sadang, OffsetDateTime.parse(arriveAt).toInstant(), Set.of(gangnam), 0).get(gangnam);
        var roomRoute = timetableRoutes.latestDepartures(sadang, OffsetDateTime.parse(appointment).toInstant(), Set.of(gangnam), 0).get(gangnam);

        JsonNode withArrival = ok(arrive(gangnam, sadang, arriveAt, null, room), member).get("routes").get(0).get("summary");
        JsonNode withoutArrival = ok(search(gangnam, sadang, null, room), member).get("routes").get(0).get("summary");

        assertThat(instant(withArrival.get("arrivalAt"))).isEqualTo(expected.arriveAt());
        assertThat(instant(withArrival.get("departureAt"))).isEqualTo(expected.leaveAt());
        assertThat(withArrival.get("slackSeconds").isIntegralNumber()).isTrue();
        assertThat(instant(withoutArrival.get("arrivalAt"))).isEqualTo(roomRoute.arriveAt());
        assertThat(withoutArrival.get("slackSeconds").isNull()).isTrue();
        server.verify();
    }

    @Test void arriveAtValidationErrors() throws Exception {
        String arriveAt = next(java.time.DayOfWeek.TUESDAY, "19:00:00");
        JsonNode both = error(arrive(gangnam, sadang, arriveAt, "2026-11-12T14:00:00+09:00", null), 400);
        assertThat(both.get("code").asString()).isEqualTo("VALIDATION_ERROR");
        assertThat(both.get("details").has("departAt")).isTrue();
        assertThat(both.get("details").has("arriveAt")).isTrue();

        JsonNode past = error(arrive(gangnam, sadang, "2020-01-01T09:00:00+09:00", null, null), 400);
        assertThat(past.get("details").get("arriveAt").asString()).isEqualTo("arriveAt은 지금 이후여야 합니다.");

        String far = java.time.OffsetDateTime.now(java.time.ZoneOffset.ofHours(9)).plusDays(31).withNano(0).toString();
        JsonNode tooFar = error(arrive(gangnam, sadang, far, null, null), 400);
        assertThat(tooFar.get("details").get("arriveAt").asString()).isEqualTo("arriveAt은 지금부터 30일 이내여야 합니다.");
        server.verify();
    }

    /** 04:00: no train of any line arrives in the 60 minutes before (the recommendation's NO_SERVICE_TIME check and text). */
    @Test void arriveAtWithoutAnyTrainBeforeItIsNoServiceTime() throws Exception {
        JsonNode error = error(arrive(gangnam, sadang, next(java.time.DayOfWeek.WEDNESDAY, "04:00:00"), null, null), 422);
        assertThat(error.get("code").asString()).isEqualTo("NO_SERVICE_TIME");
        assertThat(error.get("message").asString()).isEqualTo("해당 시간대에는 지하철이 운행하지 않습니다. 약속 시간을 바꿔 주세요.");
        server.verify();
    }

    /** 05:40: trains run, but the first 강남 -> 사당 train (05:36:00) reaches 사당 after it. */
    @Test void arriveAtJustAfterTheFirstTrainsIsArrivalUnreachable() throws Exception {
        String arriveAt = next(java.time.DayOfWeek.WEDNESDAY, "05:40:00");
        assertThat(timetableRoutes.noTrainArrivesBefore(OffsetDateTime.parse(arriveAt).toInstant())).isFalse();
        JsonNode error = error(arrive(gangnam, sadang, arriveAt, null, null), 422);
        assertThat(error.get("code").asString()).isEqualTo("ARRIVAL_UNREACHABLE");
        assertThat(error.get("message").asString()).isEqualTo("그 시각까지 도착할 수 있는 열차가 없습니다. 도착 시각을 늦춰 주세요.");
        server.verify();
    }
}
