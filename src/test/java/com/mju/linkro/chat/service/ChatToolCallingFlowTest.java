package com.mju.linkro.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mju.linkro.ai.api.AiClient;
import com.mju.linkro.ai.api.ChatReplyRequest;
import com.mju.linkro.ai.api.GeneratedText;
import com.mju.linkro.chat.domain.SenderType;
import com.mju.linkro.chat.dto.ChatResponses.Message;
import com.mju.linkro.common.exception.BusinessException;
import com.mju.linkro.common.exception.ErrorCode;
import com.mju.linkro.route.dto.RouteSearchResponse;
import com.mju.linkro.subway.api.CongestionSource;
import com.mju.linkro.subway.api.DayType;
import com.mju.linkro.subway.api.DirectionCode;
import com.mju.linkro.subway.congestion.dto.CongestionResponse;
import com.mju.linkro.subway.station.dto.StationResponses;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * The whole @링크로 flow with a mock AI: the AI "asks" for a tool, the real {@link ChatTools} (over mocked services)
 * answers, and the AI's final answer is built from the result (call → result → final answer). Per tool: a normal case,
 * a denied one, an unknown station where the tool takes one, and a failure. Rounds and AI failures: see also
 * {@code OpenAiClientTest}, where the real round limit lives.
 */
class ChatToolCallingFlowTest extends ChatToolsTestBase {
    AiClient ai = mock(AiClient.class);
    ChatRoomContext context = mock(ChatRoomContext.class);
    ChatMessageWriter writer = mock(ChatMessageWriter.class);
    ChatAiResponder responder;
    Message question;

    @BeforeEach
    void setUp() {
        question = new Message(UUID.randomUUID(), SenderType.USER, asker, "민지", "@링크로 질문", Instant.parse("2026-11-17T01:00:00Z"), null);
        when(context.build(any(), any())).thenReturn(new ChatReplyRequest("질문", List.of(), "방 정보"));
        responder = new ChatAiResponder(ai, context, writer, tools);
    }

    /** The mock AI calls {@code tool} with {@code arguments} and answers with what the tool returned. */
    String answer(String tool, String arguments) {
        org.mockito.Mockito.doAnswer(call -> {
            ChatReplyRequest request = call.getArgument(0);
            return new GeneratedText("도구 결과: " + request.tools().run(tool, arguments));
        }).when(ai).replyToChat(any());
        clearInvocations(writer);
        responder.reply(roomId, question);
        var text = ArgumentCaptor.forClass(String.class);
        verify(writer).recordAi(eq(roomId), text.capture());
        verify(writer, never()).recordSystem(any(), any());
        return text.getValue();
    }

    void assertCode(String answer, String code) {
        assertThat(answer).contains("\"ok\":false").contains("\"code\":\"" + code + "\"");
    }

    void denyAsker() {
        doThrow(new BusinessException(ErrorCode.NOT_ROOM_MEMBER)).when(access).requireMember(roomId, asker);
    }

    void allowAsker() {
        org.mockito.Mockito.doReturn(room).when(access).requireMember(roomId, asker);
    }

    static final String DEST = "{\"destinationStation\":\"종로3가\"}";

    @Test
    void theAiGetsTheToolsOfThisRoomAndAskerAndTheAnswerIsSaved() {
        answer("get_room_info", "{}");
        var request = ArgumentCaptor.forClass(ChatReplyRequest.class);
        verify(ai).replyToChat(request.capture());
        assertThat(request.getValue().tools()).isNotNull();
        assertThat(request.getValue().tools().specs()).hasSize(9);
        verify(access).requireMember(roomId, asker);
    }

    @Test
    void getRoomInfo() {
        assertThat(answer("get_room_info", "{}")).contains("\"ok\":true").contains("2026-11-17T19:00:00+09:00").contains("\"memberCount\":3");
        denyAsker();
        assertCode(answer("get_room_info", "{}"), "NO_DATA");
        doThrow(new IllegalStateException("db down")).when(roomReads).members(any());
        allowAsker();
        assertCode(answer("get_room_info", "{}"), "NO_DATA");
    }

    @Test
    void listMembers() {
        assertThat(answer("list_members", "{}")).contains("\"nickname\":\"민수\"").contains("\"departureStation\":\"강남\"")
                .contains("\"membersWithoutDeparture\":[\"지아\"]");
        denyAsker();
        assertCode(answer("list_members", "{}"), "NO_DATA");
        allowAsker();
        doThrow(new IllegalStateException("db down")).when(roomReads).members(any());
        assertCode(answer("list_members", "{}"), "NO_DATA");
    }

    @Test
    void getLatestRecommendation() {
        var trip = new com.mju.linkro.recommendation.dto.RecommendationResponse.ParticipantTime(asker, "민지",
                new com.mju.linkro.recommendation.dto.RecommendationResponse.StationRef(1, "사당"), 31, 1, 1650, 12000,
                "2026-11-17T18:20:00+09:00", null, List.of(), null, List.of(), "TIMETABLE", "2026-11-17T18:55:00+09:00", 300);
        var result = new com.mju.linkro.recommendation.dto.RecommendationResponse.Result("r", "TIME",
                new com.mju.linkro.recommendation.dto.RecommendationResponse.RecommendedStation(3, "종로3가", 37.5, 127.0, true),
                new com.mju.linkro.recommendation.dto.RecommendationResponse.Metrics(31.0, 0, 31, 1650, 1), List.of(trip), List.of(), "설명", "AI");
        when(recommendations.latest(asker, roomId)).thenReturn(new com.mju.linkro.recommendation.dto.RecommendationResponse("run",
                "2026-11-17T09:00:00+09:00", false, List.of(), null, null, null, List.of(result)));
        assertThat(answer("get_latest_recommendation", "{}")).contains("\"station\":\"종로3가\"").contains("2026-11-17T18:20:00+09:00");

        when(recommendations.latest(asker, roomId)).thenReturn(null);
        assertCode(answer("get_latest_recommendation", "{}"), "NO_RECOMMENDATION");

        // stale: the AI never even receives the old times
        when(recommendations.latest(asker, roomId)).thenReturn(new com.mju.linkro.recommendation.dto.RecommendationResponse("run",
                "2026-11-17T09:00:00+09:00", true, List.of("APPOINTMENT_CHANGED"), null, null, null, List.of(result)));
        String stale = answer("get_latest_recommendation", "{}");
        assertCode(stale, "STALE");
        assertThat(stale).doesNotContain("18:20", "18:55", "종로3가");

        denyAsker();
        assertCode(answer("get_latest_recommendation", "{}"), "NO_DATA");
        allowAsker();
        doThrow(new BusinessException(ErrorCode.NOT_ROOM_MEMBER)).when(recommendations).latest(asker, roomId);
        assertCode(answer("get_latest_recommendation", "{}"), "NO_DATA");
    }

    @Test
    void getDepartureTimeAnswersFromTheTimetableTripAndFailsWithReasonCodes() {
        Instant leave = Instant.parse("2026-11-17T09:20:00Z");
        var stop = new com.mju.linkro.subway.api.PathStop(1, "사당", 37.5, 127.0, 1, 2, DirectionCode.INNER, 0);
        var leg = new com.mju.linkro.subway.api.TimedLeg(2, "2호선", "2123", "성수", DirectionCode.INNER, 1, "사당", 3, "종로3가",
                leave.plusSeconds(240), leave.plusSeconds(2100), 12, 0, 0);
        var timed = new com.mju.linkro.subway.api.TimedRoute(new com.mju.linkro.subway.api.RoutePath(List.of(), List.of(stop), 1860, 1, 12000, 1, 3),
                List.of(leg), leave, leave.plusSeconds(240), leave.plusSeconds(2100));
        when(timetable.latestDepartures(3, APPOINTMENT, java.util.Set.of(1L), 0)).thenReturn(java.util.Map.of(1L, timed));
        assertThat(answer("get_departure_plan", DEST)).contains("\"leaveAt\":\"2026-11-17T18:20:00+09:00\"")
                .contains("\"boardAt\":\"2026-11-17T18:24:00+09:00\"").contains("\"slackMinutes\":");

        assertCode(answer("get_departure_plan", "{\"destinationStation\":\"종로3가\",\"member\":\"다른방사람\"}"), "MEMBER_NOT_FOUND");
        assertCode(answer("get_departure_plan", "{\"destinationStation\":\"없는역\"}"), "STATION_NOT_FOUND");
        assertCode(answer("get_departure_plan", "{\"destinationStation\":\"서울\"}"), "STATION_AMBIGUOUS");
        room.schedule(null);
        assertCode(answer("get_departure_plan", DEST), "NO_APPOINTMENT");
        room.schedule(APPOINTMENT);
        denyAsker();
        assertCode(answer("get_departure_plan", DEST), "NO_DATA");
        allowAsker();
        doThrow(new IllegalStateException("timetable down")).when(timetable).latestDepartures(any(Long.class), any(), any(), anyInt());
        assertCode(answer("get_departure_plan", DEST), "NO_DATA");
    }

    @Test
    void getRoute() {
        var summary = new RouteSearchResponse.Summary(1860, "2026-11-17T18:55:00+09:00", 1650, RouteSearchResponse.FareSource.ESTIMATED, 1, 3,
                null, "2026-11-17T18:20:00+09:00", false, null, false, null, null, null, null);
        when(routes.search(any(), any())).thenReturn(new RouteSearchResponse(RouteSearchResponse.RouteSource.INTERNAL,
                List.of(new RouteSearchResponse.Route("r1", "FASTEST", summary, List.of())), false, null));
        assertThat(answer("get_route", DEST)).contains("\"routeSource\":\"INTERNAL\"").contains("\"fareSource\":\"ESTIMATED\"")
                .contains("\"travelMinutes\":31");

        assertCode(answer("get_route", "{\"destinationStation\":\"없는역\"}"), "STATION_NOT_FOUND");
        assertCode(answer("get_route", "{\"originStation\":\"서울\",\"destinationStation\":\"사당\"}"), "STATION_AMBIGUOUS");
        denyAsker();
        assertCode(answer("get_route", DEST), "NO_DATA");
        allowAsker();
        doThrow(new BusinessException(ErrorCode.NOT_ROOM_MEMBER)).when(routes).search(any(), any());
        assertCode(answer("get_route", DEST), "NO_DATA");
        doThrow(new BusinessException(ErrorCode.ROUTE_NOT_FOUND)).when(routes).search(any(), any());
        assertCode(answer("get_route", DEST), "NO_ROUTE");
    }

    @Test
    void getCongestion() {
        when(stationQuery.lines()).thenReturn(List.of(new StationResponses.Line(2, "2", "2호선", "#00A84D")));
        when(congestion.congestion(any())).thenReturn(new CongestionResponse(2, 2, DirectionCode.INNER, DayType.WEEKDAY, "18:30",
                "2026-11-17", false, true, 82, "BUSY", "혼잡", CongestionSource.PREDICTED));
        String args = "{\"station\":\"강남\",\"line\":\"2호선\",\"direction\":\"INNER\",\"time\":\"18:40\"}";
        assertThat(answer("get_congestion", args)).contains("\"pct\":82").contains("\"levelLabel\":\"혼잡\"");

        assertCode(answer("get_congestion", args.replace("강남", "없는역")), "STATION_NOT_FOUND");
        denyAsker();
        assertCode(answer("get_congestion", args), "NO_DATA");
        allowAsker();
        doThrow(new IllegalStateException("db down")).when(congestion).congestion(any());
        assertCode(answer("get_congestion", args), "NO_DATA");
    }

    @Test
    void searchStations() {
        when(stationQuery.search("강", null, null, 0, 5)).thenReturn(new StationResponses.StationPage(List.of(
                new StationResponses.Station(2, "강남", 37.5, 127.0, true, List.of())), 0, 5, 1));
        assertThat(answer("search_stations", "{\"query\":\"강\"}")).contains("\"name\":\"강남\"");
        assertCode(answer("search_stations", "{\"query\":\"없는역\"}"), "STATION_NOT_FOUND");
        denyAsker();
        assertCode(answer("search_stations", "{\"query\":\"강\"}"), "NO_DATA");
        allowAsker();
        doThrow(new IllegalStateException("db down")).when(stationQuery).search(any(), any(), any(), anyInt(), anyInt());
        assertCode(answer("search_stations", "{\"query\":\"강\"}"), "NO_DATA");
    }

    @Test
    void compareMembersTravel() {
        var summary = new RouteSearchResponse.Summary(2400, "2026-11-17T18:55:00+09:00", 1550, RouteSearchResponse.FareSource.KAKAO, 0, 3,
                null, "2026-11-17T18:15:00+09:00", false, null, false, null, null, null, null);
        when(routes.search(any(), any())).thenReturn(new RouteSearchResponse(RouteSearchResponse.RouteSource.KAKAO,
                List.of(new RouteSearchResponse.Route("r1", "FASTEST", summary, List.of())), false, null));
        assertThat(answer("compare_members_travel", DEST)).contains("\"longestMember\":\"민지\"").contains("\"totalMinutes\":40");

        assertCode(answer("compare_members_travel", "{\"destinationStation\":\"없는역\"}"), "STATION_NOT_FOUND");
        assertCode(answer("compare_members_travel", "{\"destinationStation\":\"서울\"}"), "STATION_AMBIGUOUS");
        denyAsker();
        assertCode(answer("compare_members_travel", DEST), "NO_DATA");
        allowAsker();
        doThrow(new IllegalStateException("db down")).when(routes).search(any(), any());
        String failed = answer("compare_members_travel", DEST);
        assertThat(failed).contains("\"code\":\"NO_DATA\"");   // every member's route failed: the rows carry the code
    }

    // ---- limits and AI failures ----

    @Test
    void anAiFailureOrAnExceededRoundLimitBecomesTheUnavailableNotice() {
        // the AI client throws AI_UNAVAILABLE for both (the round limit is OpenAiClient's, see OpenAiClientTest)
        when(ai.replyToChat(any())).thenThrow(new BusinessException(ErrorCode.AI_UNAVAILABLE));
        responder.reply(roomId, question);
        verify(writer).recordSystem(roomId, ChatAiResponder.UNAVAILABLE_NOTICE);
        verify(writer, never()).recordAi(any(), any());

        clearInvocations(writer);
        doThrow(new IllegalStateException("boom")).when(ai).replyToChat(any());
        responder.reply(roomId, question);
        verify(writer).recordSystem(roomId, ChatAiResponder.UNAVAILABLE_NOTICE);
    }

    @Test
    void aQuotaFailureStillGetsItsOwnNotice() {
        when(ai.replyToChat(any())).thenThrow(new BusinessException(ErrorCode.AI_QUOTA_EXCEEDED));
        responder.reply(roomId, question);
        verify(writer).recordSystem(roomId, ChatAiResponder.QUOTA_NOTICE);
    }
}
