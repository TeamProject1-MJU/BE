package com.mju.linkro.room.controller;

import com.mju.linkro.auth.web.CurrentUser;
import com.mju.linkro.common.response.ApiResponse;
import com.mju.linkro.room.dto.RoomResponses.*;
import com.mju.linkro.room.dto.RoomWriteRequest;
import com.mju.linkro.room.dto.RoomMembershipRequests.*;
import com.mju.linkro.room.service.*;
import com.mju.linkro.common.config.OpenApiConfig;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import com.mju.linkro.common.config.ApiDocumentation;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.*;
import io.swagger.v3.oas.annotations.responses.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/rooms")
@RequiredArgsConstructor
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
@ApiResponses({
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "VALIDATION_ERROR: 요청 형식·값 오류", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiDocumentation.Failure.class))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "AUTH_REQUIRED / TOKEN_INVALID / TOKEN_EXPIRED", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiDocumentation.Failure.class))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "INTERNAL_ERROR", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiDocumentation.Failure.class)))
})
public class RoomController {
    private final RoomCreationService creation;
    private final RoomService rooms;
    private final RoomJoinService joining;
    private final RoomMembershipService membership;
    private final RoomLeaveService leaving;

    @DeleteMapping("/{roomId}/members/me")
    @Operation(summary = "방 나가기", description = "OWNER 퇴장 시 입장 순서로 자동 승계. 마지막 멤버이면 방 삭제. 승계 없으면 newOwnerId:null. 재요청은 남은 방에서 403, 삭제된 방에서 404.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "퇴장 성공. newOwnerId는 멤버 ID가 아닌 사용자 UUID.", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiDocumentation.LeaveResponse.class), examples = {
            @ExampleObject(name = "일반 멤버", value = "{\"success\":true,\"data\":{\"roomDeleted\":false,\"newOwnerId\":null}}"),
            @ExampleObject(name = "방장 승계", value = "{\"success\":true,\"data\":{\"roomDeleted\":false,\"newOwnerId\":\"0192f3a4-9d3e-7e5a-9c43-8f5b1c9e4d03\"}}"),
            @ExampleObject(name = "마지막 멤버", value = "{\"success\":true,\"data\":{\"roomDeleted\":true,\"newOwnerId\":null}}") }))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "NOT_ROOM_MEMBER", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiDocumentation.Failure.class)))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "ROOM_NOT_FOUND", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiDocumentation.Failure.class)))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "ROOM_BUSY", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiDocumentation.Failure.class)))
    public ApiResponse<Left> leave(@CurrentUser UUID userId, @PathVariable UUID roomId) {
        return ApiResponse.success(leaving.leave(roomId, userId));
    }

    @PostMapping
    @Operation(summary = "약속방 생성", requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true,
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiDocumentation.RoomCreateRequest.class))))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "방과 OWNER 생성 성공. 초대 코드는 생성 응답에만 포함됩니다.", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiDocumentation.RoomCreated.class)))
    public ResponseEntity<ApiResponse<Created>> create(@CurrentUser UUID userId, @RequestBody RoomWriteRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(creation.create(userId, request)));
    }
    @GetMapping
    @Operation(summary = "내가 참여 중인 방 목록")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "방 배열. 참여 방이 없으면 data: []. 초대 코드 미포함.", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiDocumentation.RoomList.class)))
    public ApiResponse<List<Summary>> list(@CurrentUser UUID userId) { return ApiResponse.success(rooms.list(userId)); }
    @GetMapping("/{roomId}")
    @Operation(summary = "방 상세 조회", description = "참여자만 조회 가능합니다. ownerId는 현재 OWNER이며 최초 생성자와 다를 수 있습니다. 초대 코드와 후보는 포함하지 않습니다.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "방 상세", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiDocumentation.RoomDetail.class)))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "NOT_ROOM_MEMBER", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiDocumentation.Failure.class)))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "ROOM_NOT_FOUND", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiDocumentation.Failure.class)))
    public ApiResponse<Detail> detail(@CurrentUser UUID userId, @PathVariable UUID roomId) {
        return ApiResponse.success(rooms.detail(roomId, userId));
    }
    @PatchMapping("/{roomId}")
    @Operation(summary = "방 이름·약속 시각 수정", description = "현재 OWNER 전용. 생략한 필드는 유지하며 name:null은 오류, appointmentAt:null은 해제입니다. 추천·후보·투표·확정역을 유지합니다.",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, content = @Content(mediaType = "application/json",
                    schema = @Schema(implementation = ApiDocumentation.RoomPatchRequest.class), examples = {
                    @ExampleObject(name = "이름만 변경", value = "{\"name\":\"토요일 점심\"}"),
                    @ExampleObject(name = "시각 해제", value = "{\"appointmentAt\":null}"),
                    @ExampleObject(name = "모든 필드 유지", value = "{}") })))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "수정 성공. data는 null.", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiDocumentation.EmptySuccess.class), examples = @ExampleObject(value = "{\"success\":true,\"data\":null}")))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "NOT_ROOM_MEMBER / NOT_ROOM_OWNER", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiDocumentation.Failure.class)))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "ROOM_NOT_FOUND", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiDocumentation.Failure.class)))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "ROOM_BUSY: 방 락 대기 초과", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiDocumentation.Failure.class)))
    public ApiResponse<Void> update(@CurrentUser UUID userId, @PathVariable UUID roomId, @RequestBody RoomWriteRequest request) {
        rooms.update(roomId, userId, request);
        return ApiResponse.success();
    }
    @GetMapping("/{roomId}/members")
    @Operation(summary = "방 멤버 목록", description = "참여자 전용. 출발역 미설정이면 departureStation:null. 초대 코드는 포함하지 않습니다.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "멤버 배열", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiDocumentation.RoomMembers.class)))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "NOT_ROOM_MEMBER", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiDocumentation.Failure.class)))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "ROOM_NOT_FOUND", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiDocumentation.Failure.class)))
    public ApiResponse<List<Member>> members(@CurrentUser UUID userId, @PathVariable UUID roomId) {
        return ApiResponse.success(rooms.members(roomId, userId));
    }
    @GetMapping("/{roomId}/invite-code")
    @Operation(summary = "초대 코드 조회", description = "최초 생성자가 아닌 현재 OWNER에게만 허용됩니다.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "초대 코드", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiDocumentation.RoomInviteCode.class)))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "NOT_ROOM_MEMBER / NOT_ROOM_OWNER", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiDocumentation.Failure.class)))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "ROOM_NOT_FOUND", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiDocumentation.Failure.class)))
    public ApiResponse<InviteCode> inviteCode(@CurrentUser UUID userId, @PathVariable UUID roomId) {
        return ApiResponse.success(rooms.inviteCode(roomId, userId));
    }

    @PostMapping("/join")
    @Operation(
            summary = "초대 코드 방 참여",
            description = "대문자 영문·숫자 6자 필수. 소문자·공백을 변환하지 않습니다. OPEN·CONFIRMED 모두 정원 4명까지 참여 가능합니다.",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    required = true,
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = Join.class),
                            examples = @ExampleObject(value = "{\"roomCode\":\"A3K9PZ\"}"))))
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200", description = "Success",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiDocumentation.JoinResponse.class),
                            examples = @ExampleObject(value = "{\"success\":true,\"data\":{\"roomId\":\"0192f3a4-8c2d-7d4f-8b21-6e3f9a7c2b02\"}}"))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404", description = "ROOM_CODE_INVALID",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiDocumentation.Failure.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "409", description = "ALREADY_JOINED / ROOM_FULL / ROOM_BUSY",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiDocumentation.Failure.class)))
    })
    public ApiResponse<Joined> join(@CurrentUser UUID userId, @RequestBody Join request) {
        return ApiResponse.success(joining.join(userId, request));
    }

    @PatchMapping("/{roomId}/members/me")
    @Operation(
            summary = "내 출발역 설정·변경",
            description = "OWNER·MEMBER 본인만 변경합니다. null·생략·문자열·소수는 VALIDATION_ERROR. 출발역 해제는 지원하지 않습니다. 기존 추천·후보·투표·확정역을 보존합니다.",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    required = true,
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = Departure.class),
                            examples = @ExampleObject(value = "{\"departureStationId\":105}"))))
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200", description = "Success",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiDocumentation.EmptySuccess.class),
                            examples = @ExampleObject(value = "{\"success\":true,\"data\":null}"))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "403", description = "NOT_ROOM_MEMBER",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiDocumentation.Failure.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404", description = "ROOM_NOT_FOUND / STATION_NOT_FOUND",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiDocumentation.Failure.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "409", description = "ROOM_BUSY",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiDocumentation.Failure.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "422", description = "STATION_UNSUPPORTED",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiDocumentation.Failure.class)))
    })
    public ApiResponse<Void> departure(@CurrentUser UUID userId, @PathVariable UUID roomId, @RequestBody Departure request) {
        membership.departure(roomId, userId, request);
        return ApiResponse.success();
    }
}
