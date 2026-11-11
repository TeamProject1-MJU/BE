package com.mju.linkro.chat.service;

import com.mju.linkro.chat.repository.RoomMessageRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * SYSTEM messages for room events (API 10.1): join, leave, owner change, station proposal created/deleted, selected
 * station set/changed/cleared. Call these inside the room-changing transaction, after the room lock: the message
 * commits or rolls back with the change, and its MESSAGE_CREATED event follows the outer commit
 * ({@link ChatMessageWriter}). Wording is ours: the spec fixes the events, not the sentences.
 */
@Component
@RequiredArgsConstructor
public class ChatSystemMessages {
    private final ChatMessageWriter writer;
    private final RoomMessageRepository messages;

    public void joined(UUID roomId, UUID userId) {
        writer.recordSystem(roomId, nickname(userId) + "님이 입장했어요.");
    }

    /** The leaver is still readable in users here (a withdrawal soft-deletes after leaving). */
    public void left(UUID roomId, UUID userId, UUID newOwnerId) {
        writer.recordSystem(roomId, nickname(userId) + "님이 나갔어요.");
        if (newOwnerId != null) writer.recordSystem(roomId, nickname(newOwnerId) + "님이 새 방장이 됐어요.");
    }

    public void proposalCreated(UUID roomId, String proposerNickname, String stationName) {
        writer.recordSystem(roomId, proposerNickname + "님이 " + station(stationName) + "을 약속역 후보로 제안했어요.");
    }

    public void proposalDeleted(UUID roomId, UUID actorId, String stationName) {
        writer.recordSystem(roomId, nickname(actorId) + "님이 약속역 후보 " + station(stationName) + "을 삭제했어요.");
    }

    /** {@code replaced}: the room already had a different selected station. */
    public void stationSelected(UUID roomId, UUID actorId, String stationName, boolean replaced) {
        writer.recordSystem(roomId, nickname(actorId) + "님이 약속역을 " + station(stationName) + "으로 " + (replaced ? "변경" : "지정") + "했어요.");
    }

    public void stationCleared(UUID roomId, UUID actorId) {
        writer.recordSystem(roomId, nickname(actorId) + "님이 약속역 지정을 해제했어요.");
    }

    /** Every label ends in 역, so the particles that follow it ("을", "으로") are always the same. */
    private static String station(String name) {
        return name.endsWith("역") ? name : name + "역";
    }

    private String nickname(UUID userId) {
        return messages.nickname(userId).orElse("알 수 없는 사용자");
    }
}
