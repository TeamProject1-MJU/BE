package com.mju.linkro.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.mju.linkro.ai.api.ChatToolSpec;
import com.mju.linkro.common.exception.BusinessException;
import com.mju.linkro.common.exception.ErrorCode;
import com.mju.linkro.recommendation.dto.RecommendationResponse;
import com.mju.linkro.recommendation.dto.RecommendationResponse.*;
import com.mju.linkro.route.dto.RouteSearchRequest;
import com.mju.linkro.route.dto.RouteSearchResponse;
import com.mju.linkro.subway.api.*;
import com.mju.linkro.subway.congestion.dto.CongestionResponse;
import com.mju.linkro.subway.station.dto.StationResponses;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;

/** The eight tools over mocked services: normal, denied, unknown/ambiguous station and failure results (#129). */
class ChatToolsTest extends ChatToolsTestBase {
    ListAppender<ILoggingEvent> logs;

    @BeforeEach
    void captureLogs() {
        logs = new ListAppender<>();
        logs.start();
        ((Logger) LoggerFactory.getLogger(ChatTools.class)).addAppender(logs);
    }

    @AfterEach
    void detachLogs() {
        ((Logger) LoggerFactory.getLogger(ChatTools.class)).detachAppender(logs);
    }

    void assertFails(JsonNode result, String code) {
        assertThat(result.get("ok").asBoolean()).as(result.toString()).isFalse();
        assertThat(result.get("code").asString()).as(result.toString()).isEqualTo(code);
    }

    // ---- the tool list ----

    @Test
    void exactlyTheEightReadOnlyToolsAreOfferedAndNoneTakesARoomOrUser() {
        List<ChatToolSpec> specs = toolbox.specs();
        assertThat(specs).extracting(ChatToolSpec::name).containsExactly("get_room_info", "list_members",
                "get_latest_recommendation", "get_departure_plan", "get_route", "get_congestion", "search_stations",
                "compare_members_travel");
        for (ChatToolSpec spec : specs) {
            assertThat(spec.parameters().toString()).as(spec.name()).doesNotContain("roomId", "userId", "room", "user");
            assertThat(spec.description()).as(spec.name()).isNotBlank();
        }
        // the look-alikes say when not to use them
        var byName = specs.stream().collect(java.util.stream.Collectors.toMap(ChatToolSpec::name, ChatToolSpec::description));
        assertThat(byName.get("get_route")).contains("get_departure_plan", "compare_members_travel");
        assertThat(byName.get("get_departure_plan")).contains("get_route", "compare_members_travel");
        assertThat(byName.get("compare_members_travel")).contains("get_route", "get_departure_plan");
    }

    @Test
    void passedAndLateFlagsAreExplainedInTheToolDescriptionsAndThePrompt() throws Exception {
        var byName = toolbox.specs().stream().collect(java.util.stream.Collectors.toMap(ChatToolSpec::name, ChatToolSpec::description));
        assertThat(byName.get("get_departure_plan")).contains("leaveAtPassed", "get_route");
        assertThat(byName.get("get_route")).contains("arrivesAfterAppointment", "appointmentPassed");
        String prompt;
        try (var in = getClass().getClassLoader().getResourceAsStream("ai/prompt/chat.txt")) {
            prompt = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        assertThat(prompt).contains("leaveAtPassed", "arrivesAfterAppointment", "appointmentPassed", "get_route");
    }

    // ---- room and user are fixed ----

    @Test
    void aNonMemberAskerGetsNothingAndNothingIsRead() {
        when(access.requireMember(roomId, asker)).thenThrow(new BusinessException(ErrorCode.NOT_ROOM_MEMBER));
        for (String tool : List.of("get_room_info", "list_members", "get_latest_recommendation", "get_departure_plan",
                "get_route", "get_congestion", "search_stations", "compare_members_travel")) {
            assertFails(run(tool, "{\"destinationStation\":\"강남\",\"station\":\"강남\",\"query\":\"강남\"}"), "NO_DATA");
        }
        verifyNoInteractions(roomReads, recommendations, routes, timetable, congestion);
    }

    @Test
    void roomAndUserInTheArgumentsAreIgnoredEverythingUsesTheFixedOnes() {
        UUID otherRoom = UUID.randomUUID();
        UUID otherUser = UUID.randomUUID();
        JsonNode result = run("list_members", "{\"roomId\":\"" + otherRoom + "\",\"userId\":\"" + otherUser + "\"}");
        assertThat(result.get("ok").asBoolean()).isTrue();
        verify(access).requireMember(roomId, asker);
        verify(roomReads).members(List.of(roomId));
        verify(access, never()).requireMember(otherRoom, otherUser);
        when(recommendations.latest(asker, roomId)).thenReturn(null);
        run("get_latest_recommendation", "{\"roomId\":\"" + otherRoom + "\"}");
        verify(recommendations).latest(asker, roomId);
    }

    // ---- get_room_info, list_members ----

    @Test
    void roomInfoHasNameAppointmentSelectedStationAndMemberCount() {
        room.confirmStation(3L, NOW);
        JsonNode result = run("get_room_info", "{}");
        assertThat(result.get("ok").asBoolean()).isTrue();
        assertThat(result.get("roomName").asString()).isEqualTo("약속방");
        assertThat(result.get("appointmentAt").asString()).isEqualTo("2026-11-17T19:00:00+09:00");
        assertThat(result.get("selectedStation").asString()).isEqualTo("종로3가");
        assertThat(result.get("memberCount").asInt()).isEqualTo(3);
    }

    @Test
    void membersListNicknameRoleDepartureAndWhoHasNone() {
        JsonNode result = run("list_members", "{}");
        assertThat(result.get("members")).hasSize(3);
        assertThat(result.get("members").get(0).get("nickname").asString()).isEqualTo("민지");
        assertThat(result.get("members").get(0).get("role").asString()).isEqualTo("OWNER");
        assertThat(result.get("members").get(0).get("departureStation").asString()).isEqualTo("사당");
        assertThat(result.get("members").get(2).has("departureStation")).isFalse();
        assertThat(result.get("membersWithoutDeparture")).extracting(JsonNode::asString).containsExactly("지아");
    }

    // ---- get_latest_recommendation ----

    static RecommendationResponse run(boolean stale, List<Result> results) {
        return new RecommendationResponse("run-1", "2026-11-17T09:00:00+09:00", stale, stale ? List.of("MEMBERS_CHANGED") : List.of(),
                null, AppliedConstraints.none(), null, results);
    }

    Result timeResult() {
        var trip = new ParticipantTime(asker, "민지", new StationRef(1, "사당"), 31, 1, 1650, 12000, "2026-11-17T18:20:00+09:00",
                null, List.of(), new BoardingTrain(2, "2호선", 1, "사당", "INNER", "2026-11-17T18:24:00+09:00", "성수", "2123"),
                List.of(), "TIMETABLE", "2026-11-17T18:55:00+09:00", 300);
        return new Result("res-1", "TIME", new RecommendedStation(3, "종로3가", 37.5, 127.0, true), new Metrics(31.0, 4, 35, 1650, 1),
                List.of(trip), List.of(), "설명", "AI");
    }

    @Test
    void latestRecommendationGivesStationAndEachMembersTimes() {
        when(recommendations.latest(asker, roomId)).thenReturn(run(false, List.of(timeResult())));
        JsonNode result = run("get_latest_recommendation", "{}");
        assertThat(result.get("ok").asBoolean()).isTrue();
        assertThat(result.get("isStale").asBoolean()).isFalse();
        JsonNode best = result.get("results").get(0);
        assertThat(best.get("criterion").asString()).isEqualTo("TIME");
        assertThat(best.get("station").asString()).isEqualTo("종로3가");
        JsonNode member = best.get("members").get(0);
        assertThat(member.get("leaveAt").asString()).isEqualTo("2026-11-17T18:20:00+09:00");
        assertThat(member.get("firstTrain").get("line").asString()).isEqualTo("2호선");
        assertThat(member.get("firstTrain").get("boardAt").asString()).isEqualTo("2026-11-17T18:24:00+09:00");
        assertThat(member.get("arriveAt").asString()).isEqualTo("2026-11-17T18:55:00+09:00");
        assertThat(member.get("slackMinutes").asInt()).isEqualTo(5);
    }

    @Test
    void aStaleRecommendationExposesNoTimesAndNoStations() {
        when(recommendations.latest(asker, roomId)).thenReturn(run(true, List.of(timeResult())));
        String raw = toolbox.run("get_latest_recommendation", "{}");
        assertFails(JSON.readTree(raw), "STALE");
        assertThat(raw).doesNotContain("2026-11-17T18", "종로3가", "leaveAt", "arriveAt", "boardAt", "firstTrain");
        assertThat(raw).contains("MEMBERS_CHANGED");
    }

    @Test
    void noRecommendationOrAnEmptyOneIsNoRecommendation() {
        when(recommendations.latest(asker, roomId)).thenReturn(null);
        assertFails(run("get_latest_recommendation", "{}"), "NO_RECOMMENDATION");
        when(recommendations.latest(asker, roomId)).thenReturn(run(false, List.of()));
        assertFails(run("get_latest_recommendation", "{}"), "NO_RECOMMENDATION");
    }

    // ---- get_departure_plan ----

    TimedRoute timed(Instant leaveAt, Instant arriveAt) {
        var stop = new PathStop(1, "사당", 37.5, 127.0, 1, 2, DirectionCode.INNER, 0);
        var leg = new TimedLeg(2, "2호선", "2123", "성수", DirectionCode.INNER, 1, "사당", 3, "종로3가", leaveAt.plusSeconds(240),
                arriveAt, 12, 0, 0);
        return new TimedRoute(new RoutePath(List.of(), List.of(stop), 1860, 1, 12000, 1, 3), List.of(leg), leaveAt,
                leaveAt.plusSeconds(240), arriveAt);
    }

    @Test
    void departurePlanForTheAskerByDefault() {
        Instant leave = Instant.parse("2026-11-17T09:20:00Z");
        Instant arrive = Instant.parse("2026-11-17T09:55:10Z");
        when(timetable.latestDepartures(3, APPOINTMENT, Set.of(1L), 0)).thenReturn(java.util.Map.of(1L, timed(leave, arrive)));
        JsonNode result = run("get_departure_plan", "{\"destinationStation\":\"종로3가역\"}");
        assertThat(result.get("ok").asBoolean()).isTrue();
        assertThat(result.get("member").asString()).isEqualTo("민지");
        assertThat(result.get("destinationStation").asString()).isEqualTo("종로3가");
        assertThat(result.get("leaveAt").asString()).isEqualTo("2026-11-17T18:20:00+09:00");
        assertThat(result.get("leaveAtPassed").asBoolean()).isFalse();
        assertThat(result.get("firstTrain").get("line").asString()).isEqualTo("2호선");
        assertThat(result.get("firstTrain").get("station").asString()).isEqualTo("사당");
        assertThat(result.get("firstTrain").get("boardAt").asString()).isEqualTo("2026-11-17T18:24:00+09:00");
        assertThat(result.get("arriveAt").asString()).isEqualTo("2026-11-17T18:55:10+09:00");
        assertThat(result.get("transferCount").asInt()).isEqualTo(1);
        assertThat(result.get("travelMinutes").asInt()).isEqualTo(36);   // 35 min 10 s, rounded up
        assertThat(result.get("slackMinutes").asInt()).isEqualTo(4);      // 4 min 50 s, rounded down
    }

    @Test
    void departurePlanForAnotherMemberOfTheRoomByNickname() {
        when(timetable.latestDepartures(3, APPOINTMENT, Set.of(2L), 0)).thenReturn(java.util.Map.of(2L,
                timed(Instant.parse("2026-11-17T09:00:00Z"), Instant.parse("2026-11-17T09:30:00Z"))));
        JsonNode result = run("get_departure_plan", "{\"destinationStation\":\"종로3가\",\"member\":\"민수\"}");
        assertThat(result.get("member").asString()).isEqualTo("민수");
        assertThat(result.get("leaveAt").asString()).isEqualTo("2026-11-17T18:00:00+09:00");
    }

    @Test
    void aNicknameOutsideTheRoomIsMemberNotFoundAndNothingIsComputed() {
        assertFails(run("get_departure_plan", "{\"destinationStation\":\"종로3가\",\"member\":\"남의방사람\"}"), "MEMBER_NOT_FOUND");
        verifyNoInteractions(timetable);
    }

    @Test
    void departurePlanReasonCodes() {
        assertFails(run("get_departure_plan", "{\"destinationStation\":\"종로3가\",\"member\":\"지아\"}"), "DEPARTURE_MISSING");
        assertFails(run("get_departure_plan", "{\"destinationStation\":\"사당\"}"), "NO_ROUTE");          // the asker starts there
        assertFails(run("get_departure_plan", "{\"destinationStation\":\"종로3가\"}"), "NO_SERVICE");     // no train arrives in time
        room.schedule(null);
        assertFails(run("get_departure_plan", "{\"destinationStation\":\"종로3가\"}"), "NO_APPOINTMENT");
        room.schedule(NOW.minusSeconds(60));
        assertFails(run("get_departure_plan", "{\"destinationStation\":\"종로3가\"}"), "APPOINTMENT_PASSED");
    }

    @Test
    void aDepartureThatHasAlreadyPassedIsFlagged() {
        when(timetable.latestDepartures(3, APPOINTMENT, Set.of(1L), 0)).thenReturn(java.util.Map.of(1L,
                timed(NOW.minusSeconds(300), Instant.parse("2026-11-17T09:55:00Z"))));
        assertThat(run("get_departure_plan", "{\"destinationStation\":\"종로3가\"}").get("leaveAtPassed").asBoolean()).isTrue();
    }

    @Test
    void stationNamesAreCheckedLikeTheRecommendationsDo() {
        assertFails(run("get_departure_plan", "{\"destinationStation\":\"없는역\"}"), "STATION_NOT_FOUND");
        assertFails(run("get_departure_plan", "{}"), "STATION_NOT_FOUND");
        assertFails(run("get_departure_plan", "{\"destinationStation\":\"서울역\"}"), "STATION_AMBIGUOUS");   // 서울 is 10 and 11
        when(stationQuery.search(any(), any(), any(), anyInt(), anyInt())).thenReturn(new StationResponses.StationPage(
                List.of(new StationResponses.Station(7, "용문", 37.0, 127.0, false, List.of())), 0, 50, 1));
        assertFails(run("get_departure_plan", "{\"destinationStation\":\"용문역\"}"), "STATION_UNSUPPORTED");
        verifyNoInteractions(timetable);
    }

    // ---- get_route, compare_members_travel ----

    static RouteSearchResponse route(int totalSec, int fare, RouteSearchResponse.FareSource fareSource) {
        var summary = new RouteSearchResponse.Summary(totalSec, "2026-11-17T18:55:00+09:00", fare, fareSource, 1, 3, null,
                "2026-11-17T18:20:00+09:00", false, null, false, null, null, null, null);
        var ride = new RouteSearchResponse.Step("SUBWAY", "2호선 승차", 1500, 9000, new RouteSearchResponse.Line(2, "2호선", "#00A84D"),
                null, DirectionCode.INNER, List.of(new RouteSearchResponse.Stop(1, "사당", 1), new RouteSearchResponse.Stop(3, "종로3가", 2)),
                null, List.of());
        return new RouteSearchResponse(RouteSearchResponse.RouteSource.INTERNAL,
                List.of(new RouteSearchResponse.Route("r1", "FASTEST", summary, List.of(ride))), false, null);
    }

    @Test
    void routeStartsAtTheAskersStationInTheFixedRoomAndKeepsTheEstimateMarkers() {
        when(routes.search(any(), any())).thenReturn(route(1860, 1650, RouteSearchResponse.FareSource.ESTIMATED));
        JsonNode result = run("get_route", "{\"destinationStation\":\"종로3가\"}");
        var request = ArgumentCaptor.forClass(RouteSearchRequest.class);
        verify(routes).search(org.mockito.ArgumentMatchers.eq(asker), request.capture());
        assertThat(request.getValue()).isEqualTo(new RouteSearchRequest(1L, 3L, null, roomId.toString()));
        assertThat(result.get("ok").asBoolean()).isTrue();
        assertThat(result.get("routeSource").asString()).isEqualTo("INTERNAL");
        assertThat(result.get("fareSource").asString()).isEqualTo("ESTIMATED");
        assertThat(result.get("fare").asInt()).isEqualTo(1650);
        assertThat(result.get("totalTimeSec").asInt()).isEqualTo(1860);
        assertThat(result.get("totalMinutes").asInt()).isEqualTo(31);
        assertThat(result.get("transferCount").asInt()).isEqualTo(1);
        assertThat(result.get("originStation").asString()).isEqualTo("사당");
        assertThat(result.get("steps").get(0).get("line").asString()).isEqualTo("2호선");
        assertThat(result.get("steps").get(0).get("from").asString()).isEqualTo("사당");
        assertThat(result.get("steps").get(0).get("to").asString()).isEqualTo("종로3가");
    }

    @Test
    void routeWithAnOriginNameUsesIt() {
        when(routes.search(any(), any())).thenReturn(route(1200, 1550, RouteSearchResponse.FareSource.KAKAO));
        run("get_route", "{\"originStation\":\"강남역\",\"destinationStation\":\"종로3가\"}");
        verify(routes).search(asker, new RouteSearchRequest(2L, 3L, null, roomId.toString()));
    }

    @Test
    void routeFailuresAreReasonCodes() {
        assertFails(run("get_route", "{\"destinationStation\":\"없는역\"}"), "STATION_NOT_FOUND");
        assertFails(run("get_route", "{\"originStation\":\"서울\",\"destinationStation\":\"사당\"}"), "STATION_AMBIGUOUS");
        when(roomReads.members(List.of(roomId))).thenReturn(List.of(
                new com.mju.linkro.room.repository.RoomReadRepository.MemberRow(roomId, asker, "민지",
                        com.mju.linkro.room.domain.RoomRole.OWNER, NOW, null)));
        assertFails(run("get_route", "{\"destinationStation\":\"사당\"}"), "DEPARTURE_MISSING");
        doThrow(new BusinessException(ErrorCode.ROUTE_NOT_FOUND)).when(routes).search(any(), any());
        assertFails(run("get_route", "{\"originStation\":\"강남\",\"destinationStation\":\"사당\"}"), "NO_ROUTE");
        doThrow(new BusinessException(ErrorCode.NO_SERVICE_TIME)).when(routes).search(any(), any());
        assertFails(run("get_route", "{\"originStation\":\"강남\",\"destinationStation\":\"사당\"}"), "NO_SERVICE");
        doThrow(new IllegalStateException("db down")).when(routes).search(any(), any());
        assertFails(run("get_route", "{\"originStation\":\"강남\",\"destinationStation\":\"사당\"}"), "NO_DATA");
    }

    @Test
    void compareGivesEveryMembersTimeTheLongestAndWhoHasNoDeparture() {
        when(routes.search(asker, new RouteSearchRequest(1L, 3L, null, roomId.toString())))
                .thenReturn(route(1860, 1650, RouteSearchResponse.FareSource.ESTIMATED));
        when(routes.search(asker, new RouteSearchRequest(2L, 3L, null, roomId.toString())))
                .thenReturn(route(2400, 1550, RouteSearchResponse.FareSource.KAKAO));
        JsonNode result = run("compare_members_travel", "{\"destinationStation\":\"종로3가\"}");
        assertThat(result.get("ok").asBoolean()).isTrue();
        assertThat(result.get("members")).hasSize(2);
        assertThat(result.get("members").get(0).get("nickname").asString()).isEqualTo("민지");
        assertThat(result.get("members").get(0).get("totalMinutes").asInt()).isEqualTo(31);
        assertThat(result.get("members").get(1).get("totalMinutes").asInt()).isEqualTo(40);
        assertThat(result.get("longestMember").asString()).isEqualTo("민수");
        assertThat(result.get("membersWithoutDeparture")).extracting(JsonNode::asString).containsExactly("지아");
    }

    @Test
    void compareKeepsGoingWhenOneMemberHasNoRouteAndFailsOnBadInput() {
        when(routes.search(asker, new RouteSearchRequest(1L, 3L, null, roomId.toString())))
                .thenReturn(route(1860, 1650, RouteSearchResponse.FareSource.ESTIMATED));
        when(routes.search(asker, new RouteSearchRequest(2L, 3L, null, roomId.toString())))
                .thenThrow(new BusinessException(ErrorCode.ROUTE_NOT_FOUND));
        JsonNode result = run("compare_members_travel", "{\"destinationStation\":\"종로3가\"}");
        assertThat(result.get("members").get(1).get("code").asString()).isEqualTo("NO_ROUTE");
        assertThat(result.get("longestMember").asString()).isEqualTo("민지");
        assertFails(run("compare_members_travel", "{\"destinationStation\":\"없는역\"}"), "STATION_NOT_FOUND");
        assertFails(run("compare_members_travel", "{\"destinationStation\":\"서울\"}"), "STATION_AMBIGUOUS");
    }

    // ---- get_congestion, search_stations ----

    CongestionResponse congestionOf(boolean supported) {
        return new CongestionResponse(2, 2, DirectionCode.INNER, DayType.WEEKDAY, "18:30", "2026-11-17", false, supported,
                supported ? 82 : null, supported ? "BUSY" : null, supported ? "혼잡" : null, supported ? CongestionSource.PREDICTED : null);
    }

    @Test
    void congestionIsTheSixPointTwoValueForTheNamedStationAndLine() {
        when(stationQuery.lines()).thenReturn(List.of(new StationResponses.Line(2, "2", "2호선", "#00A84D")));
        when(congestion.congestion(any())).thenReturn(congestionOf(true));
        JsonNode result = run("get_congestion", "{\"station\":\"강남역\",\"line\":\"2호선\",\"direction\":\"INNER\",\"time\":\"18:40\"}");
        var query = ArgumentCaptor.forClass(com.mju.linkro.subway.congestion.dto.CongestionQuery.class);
        verify(congestion).congestion(query.capture());
        assertThat(query.getValue().stationId()).isEqualTo(2);
        assertThat(query.getValue().lineId()).isEqualTo(2);
        assertThat(query.getValue().date()).hasToString("2026-11-17");   // no date: today in Seoul
        assertThat(result.get("ok").asBoolean()).isTrue();
        assertThat(result.get("station").asString()).isEqualTo("강남");
        assertThat(result.get("line").asString()).isEqualTo("2호선");
        assertThat(result.get("pct").asInt()).isEqualTo(82);
        assertThat(result.get("levelLabel").asString()).isEqualTo("혼잡");
        assertThat(result.get("source").asString()).isEqualTo("PREDICTED");
    }

    @Test
    void congestionFailuresAreReasonCodes() {
        when(stationQuery.lines()).thenReturn(List.of(new StationResponses.Line(2, "2", "2호선", "#00A84D")));
        assertFails(run("get_congestion", "{\"station\":\"없는역\",\"line\":\"2호선\",\"direction\":\"UP\",\"time\":\"18:40\"}"), "STATION_NOT_FOUND");
        assertFails(run("get_congestion", "{\"station\":\"서울\",\"line\":\"2호선\",\"direction\":\"UP\",\"time\":\"18:40\"}"), "STATION_AMBIGUOUS");
        assertFails(run("get_congestion", "{\"station\":\"강남\",\"line\":\"99호선\",\"direction\":\"UP\",\"time\":\"18:40\"}"), "NO_DATA");
        when(congestion.congestion(any())).thenReturn(congestionOf(false));
        assertFails(run("get_congestion", "{\"station\":\"강남\",\"line\":\"2호선\",\"direction\":\"UP\",\"time\":\"18:40\"}"), "NO_DATA");
        doThrow(new BusinessException(ErrorCode.VALIDATION_ERROR)).when(congestion).congestion(any());
        JsonNode bad = run("get_congestion", "{\"station\":\"강남\",\"line\":\"2호선\",\"direction\":\"위\",\"time\":\"저녁\"}");
        assertFails(bad, "NO_DATA");
        assertThat(bad.get("message").asString()).contains("HH:mm");
    }

    @Test
    void searchGivesUpToFiveStationsOrNotFound() {
        when(stationQuery.search("강", null, null, 0, 5)).thenReturn(new StationResponses.StationPage(List.of(
                new StationResponses.Station(2, "강남", 37.5, 127.0, true,
                        List.of(new StationResponses.StationLine(2, "2호선", "#00A84D", "222"))),
                new StationResponses.Station(7, "강동", 37.5, 127.1, false, List.of())), 0, 5, 2));
        JsonNode result = run("search_stations", "{\"query\":\"강\"}");
        assertThat(result.get("stations")).hasSize(2);
        assertThat(result.get("stations").get(0).get("name").asString()).isEqualTo("강남");
        assertThat(result.get("stations").get(0).get("lines").get(0).asString()).isEqualTo("2호선");
        assertThat(result.get("stations").get(1).get("isSupported").asBoolean()).isFalse();
        assertFails(run("search_stations", "{\"query\":\"없는역\"}"), "STATION_NOT_FOUND");
        assertFails(run("search_stations", "{}"), "STATION_NOT_FOUND");
    }

    // ---- limits and logs ----

    @Test
    void aToolThatTakesTooLongTimesOutWithNoData() {
        properties.setAiToolTimeout(Duration.ofMillis(100));
        when(recommendations.latest(asker, roomId)).thenAnswer(call -> {
            Thread.sleep(2000);
            return null;
        });
        long started = System.nanoTime();
        assertFails(run("get_latest_recommendation", "{}"), "NO_DATA");
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(1));
    }

    @Test
    void anUnknownToolOrBrokenArgumentsAreReasonCodesNotErrors() {
        assertFails(run("delete_room", "{}"), "NO_DATA");
        assertFails(run("run_recommendation", "{}"), "NO_DATA");
        assertFails(run("get_route", "not json"), "STATION_NOT_FOUND");
        assertFails(run("get_route", null), "STATION_NOT_FOUND");
        verify(access, never()).lockOwner(any(), any());
    }

    @Test
    void theLogHasOnlyToolNameResultCodeAndDuration() {
        doThrow(new BusinessException(ErrorCode.ROUTE_NOT_FOUND)).when(routes).search(any(), any());
        run("get_route", "{\"originStation\":\"강남\",\"destinationStation\":\"사당\"}");
        run("get_departure_plan", "{\"destinationStation\":\"종로3가\",\"member\":\"민수\"}");
        run("delete_room", "{\"x\":\"민지\"}");
        assertThat(logs.list).isNotEmpty();
        for (ILoggingEvent event : logs.list) {
            String line = event.getFormattedMessage();
            assertThat(line).matches("chat tool (get_route|get_departure_plan|unknown) [A-Z_]+ \\d+ms");
            assertThat(line).doesNotContain("민지", "민수", "지아", "강남", "사당", "종로3가", "약속방");
        }
        assertThat(logs.list).extracting(ILoggingEvent::getFormattedMessage)
                .anyMatch(line -> line.startsWith("chat tool get_route NO_ROUTE "));
    }
}
