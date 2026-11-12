package com.mju.linkro.chat.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.mju.linkro.chat.domain.SenderType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

public final class ChatResponses {
    private ChatResponses() {}

    /**
     * One room message (API 10.1, 10.2, WS MESSAGE_CREATED). senderId and nickname are null for AI and SYSTEM, and
     * meta is null for every message today (no kind is in use, API 10.3).
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    @Schema(name = "ChatMessage", requiredProperties = {"id", "senderType", "senderId", "nickname", "content", "createdAt", "meta"})
    public record Message(
            UUID id,
            SenderType senderType,
            @Schema(types = {"string", "null"}, format = "uuid") UUID senderId,
            @Schema(types = {"string", "null"}, description = "USER일 때 보낸 사람의 현재 닉네임. 나간 사용자도 닉네임이 남아요.") String nickname,
            String content,
            Instant createdAt,
            @Schema(implementation = Object.class, types = {"object", "null"}, description = "확장 필드. 현재는 항상 null") JsonNode meta) {}

    @JsonInclude(JsonInclude.Include.ALWAYS)
    @Schema(name = "ChatMessageSent", requiredProperties = {"message", "aiPending"})
    public record Sent(Message message,
                       @Schema(description = "@링크로가 있어 AI 답변을 만드는 중이면 true. 답변(또는 실패 안내 SYSTEM 메시지)은 WS MESSAGE_CREATED로 와요. 실시간 연결(#42/#43)이 아직 없어서 지금은 앱으로 바로 오지 않아요. 그 전까지는 메시지 목록 조회(GET .../messages)를 다시 불러서 확인해야 해요.") boolean aiPending) {}

    @JsonInclude(JsonInclude.Include.ALWAYS)
    @Schema(name = "ChatMessagePage", requiredProperties = {"items", "nextCursor", "hasMore"})
    public record Page(
            @Schema(description = "최신 메시지가 먼저(id 내림차순)") List<Message> items,
            @Schema(types = {"string", "null"}, format = "uuid", description = "다음(더 오래된) 페이지의 cursor. hasMore가 false면 null") UUID nextCursor,
            boolean hasMore) {}

    public record SentEnvelope(boolean success, Sent data) {}
    public record PageEnvelope(boolean success, Page data) {}
}
