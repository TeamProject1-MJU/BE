package com.mju.linkro.chat.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.mju.linkro.ai.api.ChatToolbox;
import com.mju.linkro.chat.ChatProperties;
import com.mju.linkro.recommendation.repository.RecommendationRepository;
import com.mju.linkro.recommendation.service.RecommendationService;
import com.mju.linkro.room.domain.MeetingRoom;
import com.mju.linkro.room.domain.RoomRole;
import com.mju.linkro.room.dto.RoomResponses.Station;
import com.mju.linkro.room.repository.RoomReadRepository;
import com.mju.linkro.room.repository.RoomReadRepository.MemberRow;
import com.mju.linkro.room.service.RoomAccess;
import com.mju.linkro.route.service.RouteSearchService;
import com.mju.linkro.subway.api.TimetableRouteFinder;
import com.mju.linkro.subway.congestion.service.CongestionQueryService;
import com.mju.linkro.subway.station.dto.StationResponses;
import com.mju.linkro.subway.station.service.StationQueryService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.ObjectProvider;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * A room "약속방" with an appointment Tuesday 19:00 (Seoul), the asker 민지 (사당), 민수 (강남) and 지아 (no departure
 * station), over mocked services. Stations: 사당 1, 강남 2, 종로3가 3, 서울 10 and 11 (a shared name).
 */
abstract class ChatToolsTestBase {
    static final Instant NOW = Instant.parse("2026-11-17T01:00:00Z");
    static final Instant APPOINTMENT = Instant.parse("2026-11-17T10:00:00Z");
    static final JsonMapper JSON = JsonMapper.builder().build();

    UUID roomId = UUID.randomUUID();
    UUID asker = UUID.randomUUID();
    UUID minsu = UUID.randomUUID();
    UUID jia = UUID.randomUUID();
    MeetingRoom room;
    RoomAccess access = mock(RoomAccess.class);
    RoomReadRepository roomReads = mock(RoomReadRepository.class);
    RecommendationRepository recommendationRepository = mock(RecommendationRepository.class);
    RecommendationService recommendations = mock(RecommendationService.class);
    RouteSearchService routes = mock(RouteSearchService.class);
    TimetableRouteFinder timetable = mock(TimetableRouteFinder.class);
    CongestionQueryService congestion = mock(CongestionQueryService.class);
    StationQueryService stationQuery = mock(StationQueryService.class);
    ChatProperties properties = new ChatProperties();
    ThreadPoolExecutor executor = new ThreadPoolExecutor(0, 4, 5, TimeUnit.SECONDS, new SynchronousQueue<>());
    ObjectProvider<TimetableRouteFinder> finder;
    ChatTools tools;
    ChatToolbox toolbox;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void baseSetUp() {
        room = new MeetingRoom("H12345", "약속방", asker);
        room.schedule(APPOINTMENT);
        when(access.requireMember(roomId, asker)).thenReturn(room);
        when(roomReads.members(List.of(roomId))).thenReturn(List.of(
                new MemberRow(roomId, asker, "민지", RoomRole.OWNER, NOW, 1L),
                new MemberRow(roomId, minsu, "민수", RoomRole.MEMBER, NOW, 2L),
                new MemberRow(roomId, jia, "지아", RoomRole.MEMBER, NOW, null)));
        when(roomReads.stations(any())).thenAnswer(call -> {
            Map<Long, Station> all = Map.of(1L, station(1, "사당"), 2L, station(2, "강남"), 3L, station(3, "종로3가"));
            return all.entrySet().stream().filter(entry -> ((java.util.Collection<Long>) call.getArgument(0)).contains(entry.getKey()))
                    .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        });
        when(recommendationRepository.supportedStationIdsByName()).thenReturn(Map.of(
                "사당", List.of(1L), "강남", List.of(2L), "종로3가", List.of(3L), "서울", List.of(10L, 11L)));
        when(stationQuery.search(any(), any(), any(), anyInt(), anyInt())).thenReturn(new StationResponses.StationPage(List.of(), 0, 50, 0));
        when(timetable.covers(any())).thenReturn(true);
        finder = mock(ObjectProvider.class);
        when(finder.getIfAvailable()).thenReturn(timetable);
        tools = new ChatTools(access, roomReads, recommendationRepository, recommendations, routes, finder, congestion,
                stationQuery, JSON, executor, properties, Clock.fixed(NOW, ZoneOffset.UTC));
        toolbox = tools.forAsker(roomId, asker);
    }

    @AfterEach
    void shutdownTools() {
        executor.shutdownNow();
    }

    static Station station(long id, String name) {
        return new Station(id, name, true, List.of());
    }

    JsonNode run(String tool, String arguments) {
        return JSON.readTree(toolbox.run(tool, arguments));
    }

}
