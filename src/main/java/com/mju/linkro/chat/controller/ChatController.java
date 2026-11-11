package com.mju.linkro.chat.controller;

import com.mju.linkro.auth.web.CurrentUser;
import com.mju.linkro.chat.dto.ChatMessageRequest;
import com.mju.linkro.chat.dto.ChatResponses.*;
import com.mju.linkro.chat.service.ChatService;
import com.mju.linkro.common.config.ApiDocumentation;
import com.mju.linkro.common.config.OpenApiConfig;
import com.mju.linkro.common.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/rooms/{roomId}/messages")
@RequiredArgsConstructor
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
@ApiResponses({
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "VALIDATION_ERROR", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiDocumentation.Failure.class))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "AUTH_REQUIRED / TOKEN_INVALID / TOKEN_EXPIRED", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiDocumentation.Failure.class))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "NOT_ROOM_MEMBER", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiDocumentation.Failure.class))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "ROOM_NOT_FOUND", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiDocumentation.Failure.class))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "INTERNAL_ERROR", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiDocumentation.Failure.class)))
})
public class ChatController {
    private final ChatService chat;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "채팅 전송 (10.1, #27)", description = "멤버만 보낼 수 있어요. 멤버 확인 후 본문(1~1000자)을 검사하고, 저장한 메시지를 바로 돌려줘요. "
            + "@링크로가 있으면 aiPending:true이고 AI 답변은 비동기로 만들어 MESSAGE_CREATED 이벤트로 나가요. AI가 실패해도 보낸 메시지는 남고 SYSTEM 안내 메시지가 대신 나가요. 실시간 연결(#42/#43)이 아직 없어서 지금은 앱으로 바로 오지 않아요. 그 전까지는 메시지 목록 조회(GET .../messages)를 다시 불러서 확인해야 해요.",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, content = @Content(mediaType = "application/json", schema = @Schema(implementation = ChatMessageRequest.class), examples = @ExampleObject(value = "{\"content\":\"@링크로 사당역에서 갈만한 곳 알려줘\"}"))))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "저장됨", content = @Content(mediaType = "application/json", schema = @Schema(implementation = SentEnvelope.class), examples = @ExampleObject(value = "{\"success\":true,\"data\":{\"message\":{\"id\":\"0192f3c1-0000-7000-8000-000000000001\",\"senderType\":\"USER\",\"senderId\":\"0192f3a4-7b1c-7c3e-9a10-5d2e8f6b1a01\",\"nickname\":\"민지\",\"content\":\"@링크로 사당역에서 갈만한 곳 알려줘\",\"createdAt\":\"2026-11-11T11:00:00Z\",\"meta\":null},\"aiPending\":true}}")))
    public ApiResponse<Sent> send(@CurrentUser UUID userId, @PathVariable UUID roomId, @RequestBody ChatMessageRequest request) {
        return ApiResponse.success(chat.send(roomId, userId, request));
    }

    @GetMapping
    @Operation(summary = "채팅 내역 (10.2, #28)", description = "최신 메시지부터 id 내림차순이에요. cursor는 이전 페이지의 nextCursor이고, 정렬과 비교는 DB에서 해요. "
            + "size 기본 30, 1~100. SYSTEM·AI 메시지도 함께 나와요(senderId·nickname은 null).")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "메시지 페이지", content = @Content(mediaType = "application/json", schema = @Schema(implementation = PageEnvelope.class), examples = @ExampleObject(value = "{\"success\":true,\"data\":{\"items\":[{\"id\":\"0192f3c0-0000-7000-8000-000000000002\",\"senderType\":\"AI\",\"senderId\":null,\"nickname\":null,\"content\":\"...\",\"createdAt\":\"2026-11-11T11:00:03Z\",\"meta\":null}],\"nextCursor\":\"0192f3c0-0000-7000-8000-000000000002\",\"hasMore\":true}}")))
    public ApiResponse<Page> list(@CurrentUser UUID userId, @PathVariable UUID roomId,
                                  @RequestParam(required = false) String cursor, @RequestParam(required = false) String size) {
        return ApiResponse.success(chat.list(roomId, userId, cursor, size));
    }
}
