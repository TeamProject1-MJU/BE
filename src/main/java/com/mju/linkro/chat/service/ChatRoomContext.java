package com.mju.linkro.chat.service;

import com.mju.linkro.chat.repository.RoomMessageRepository;
import com.mju.linkro.chat.ChatProperties;
import com.mju.linkro.chat.dto.ChatResponses.Message;
import com.mju.linkro.ai.api.ChatReplyRequest;
import com.mju.linkro.ai.api.ChatTurn;
import com.mju.linkro.chat.domain.SenderType;
import com.mju.linkro.recommendation.repository.RecommendationRepository;
import com.mju.linkro.recommendation.repository.RecommendationRepository.ResultRow;
import com.mju.linkro.room.domain.MeetingRoom;
import com.mju.linkro.room.dto.RoomResponses.Station;
import com.mju.linkro.room.repository.MeetingRoomRepository;
import com.mju.linkro.room.repository.RoomReadRepository;
import com.mju.linkro.room.repository.RoomReadRepository.MemberRow;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Builds what the AI gets with an @linkro question (API 10.1): the earlier conversation and the room context
 * (participants, departures, appointment, selected station, latest recommendation). Facts only, no numbers the AI
 * could repeat wrongly. Runs without a transaction: the caller holds no database connection during the AI call.
 */
@Component
@RequiredArgsConstructor
class ChatRoomContext {
    /** The name the AI's own earlier answers carry in the conversation. */
    static final String AI_NAME = "linkro";
    private static final DateTimeFormatter APPOINTMENT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.of("Asia/Seoul"));

    private final ChatProperties properties;
    private final RoomMessageRepository messages;
    private final MeetingRoomRepository rooms;
    private final RoomReadRepository roomReads;
    private final RecommendationRepository recommendations;

    ChatReplyRequest build(UUID roomId, Message question) {
        List<ChatTurn> recent = messages.conversationBefore(roomId, question.id(), properties.getAiContextMessages()).stream()
                .map(message -> new ChatTurn(message.senderType() == SenderType.AI ? AI_NAME : message.nickname(), message.content()))
                .toList();
        return new ChatReplyRequest(LinkroMention.question(question.content()), recent, describe(roomId));
    }

    private String describe(UUID roomId) {
        Optional<MeetingRoom> found = rooms.findById(roomId);
        if (found.isEmpty()) return null;
        MeetingRoom room = found.get();
        List<MemberRow> members = roomReads.members(List.of(roomId));
        var recommendation = recommendations.latest(roomId).map(run -> recommendations.results(run.id())).orElse(List.of());

        LinkedHashSet<Long> stationIds = new LinkedHashSet<>();
        if (room.getSelectedStationId() != null) stationIds.add(room.getSelectedStationId());
        members.stream().map(MemberRow::stationId).filter(java.util.Objects::nonNull).forEach(stationIds::add);
        recommendation.stream().map(ResultRow::stationId).forEach(stationIds::add);
        Map<Long, Station> stations = roomReads.stations(stationIds);

        var text = new StringBuilder("약속방 이름: ").append(room.getName()).append('\n');
        text.append("약속 시각: ").append(room.getAppointmentAt() == null ? "미정" : APPOINTMENT.format(room.getAppointmentAt())).append('\n');
        text.append("확정된 약속역: ").append(name(stations, room.getSelectedStationId(), "아직 없음")).append('\n');
        List<String> people = new ArrayList<>();
        for (MemberRow member : members) {
            people.add(member.nickname() + "(출발역: " + name(stations, member.stationId(), "미설정") + ")");
        }
        text.append("참여자: ").append(String.join(", ", people)).append('\n');
        List<String> best = new ArrayList<>();
        for (ResultRow result : recommendation) {
            best.add(criterion(result.criterion()) + " 기준 " + name(stations, result.stationId(), "?"));
        }
        text.append("최근 중간역 추천: ").append(best.isEmpty() ? "없음" : String.join(", ", best));
        return text.toString();
    }

    private static String name(Map<Long, Station> stations, Long id, String fallback) {
        Station station = id == null ? null : stations.get(id);
        return station == null ? fallback : station.name();
    }

    private static String criterion(String code) {
        return switch (code) {
            case "TIME" -> "시간";
            case "FARE" -> "요금";
            case "DISTANCE" -> "거리";
            default -> code;
        };
    }
}
