package com.mju.linkro.room.controller;

import com.mju.linkro.auth.web.CurrentUser;
import com.mju.linkro.common.config.*;
import com.mju.linkro.common.response.ApiResponse;
import com.mju.linkro.room.dto.StationProposalRequest;
import com.mju.linkro.room.dto.StationProposalResponses.*;
import com.mju.linkro.room.service.ProposalVoteService;
import com.mju.linkro.room.service.StationProposalService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.*;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/rooms/{roomId}/station-proposals")
@RequiredArgsConstructor
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
@ApiResponses({
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "VALIDATION_ERROR", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiDocumentation.Failure.class))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "AUTH_REQUIRED / TOKEN_INVALID / TOKEN_EXPIRED", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiDocumentation.Failure.class))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "INTERNAL_ERROR", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiDocumentation.Failure.class)))
})
public class StationProposalController {
    private final StationProposalService service;
    private final ProposalVoteService votes;

    @PutMapping("/{proposalId}/vote")
    @Operation(summary = "약속역 후보 참고용 투표 (7.17)", description = "방 전체에서 1표이며 같은 후보 재요청은 멱등이에요. 요청 본문은 없어요.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "현재 투표 상태", content = @Content(mediaType = "application/json", schema = @Schema(implementation = VoteEnvelope.class)))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "NOT_ROOM_MEMBER", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiDocumentation.Failure.class)))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "ROOM_NOT_FOUND / PROPOSAL_NOT_FOUND", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiDocumentation.Failure.class)))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "ROOM_BUSY / ALREADY_VOTED", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiDocumentation.Failure.class)))
    public ApiResponse<Vote> vote(@CurrentUser UUID userId, @PathVariable UUID roomId, @PathVariable UUID proposalId) {
        return ApiResponse.success(votes.vote(roomId, userId, proposalId));
    }

    @PostMapping
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "NOT_ROOM_MEMBER", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiDocumentation.Failure.class)))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "ROOM_NOT_FOUND / STATION_NOT_FOUND / RESULT_NOT_FOUND", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiDocumentation.Failure.class)))
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "약속역 후보 제안 (7.14)", description = "멤버 확인 후 입력, 역, 추천 결과의 방 소속, 중복, 상한 순서로 검사해요. 추천의 주변 대안역도 허용해요.",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, content = @Content(mediaType = "application/json", schema = @Schema(implementation = StationProposalRequest.class), examples = @ExampleObject(value = "{\"stationId\":112,\"resultId\":\"01900000-0000-7000-8000-000000000001\"}"))))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "후보 생성", content = @Content(mediaType = "application/json", schema = @Schema(implementation = CreatedEnvelope.class), examples = @ExampleObject(value = "{\"success\":true,\"data\":{\"id\":\"01900000-0000-7000-8000-000000000002\",\"station\":{\"id\":112,\"name\":\"강남\"},\"proposedBy\":{\"userId\":\"01900000-0000-7000-8000-000000000003\",\"nickname\":\"멤버\"},\"resultId\":null,\"createdAt\":\"2026-11-11T00:00:00Z\"}}")))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "ROOM_BUSY / ALREADY_PROPOSED", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiDocumentation.Failure.class)))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "422", description = "STATION_UNSUPPORTED / PROPOSAL_LIMIT_EXCEEDED", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiDocumentation.Failure.class)))
    public ApiResponse<Created> create(@CurrentUser UUID userId, @PathVariable UUID roomId, @RequestBody StationProposalRequest request) {
        return ApiResponse.success(service.create(roomId, userId, request));
    }

    @GetMapping
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "NOT_ROOM_MEMBER", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiDocumentation.Failure.class)))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "ROOM_NOT_FOUND", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiDocumentation.Failure.class)))
    @Operation(summary = "약속역 후보 목록 (7.15)", description = "독립된 REPEATABLE_READ 스냅샷에서 권한·확정역·후보·득표를 읽어요. created_at DESC, id DESC 순서예요.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "후보 목록", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ListEnvelope.class), examples = @ExampleObject(value = "{\"success\":true,\"data\":{\"selectedStation\":null,\"maxProposals\":9,\"items\":[]}}")))
    public ApiResponse<Listing> list(@CurrentUser UUID userId, @PathVariable UUID roomId) {
        return ApiResponse.success(service.list(roomId, userId));
    }

    @DeleteMapping("/{proposalId}")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "NOT_ROOM_MEMBER / NOT_PROPOSAL_OWNER", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiDocumentation.Failure.class)))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "ROOM_NOT_FOUND / PROPOSAL_NOT_FOUND", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiDocumentation.Failure.class)))
    @Operation(summary = "약속역 후보 삭제 (7.16)", description = "제안자 본인 또는 현재 OWNER만 삭제해요. 다른 방 후보도 PROPOSAL_NOT_FOUND로 처리해요. 투표는 cascade 삭제하고 확정 약속역은 유지해요.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "삭제 성공, data:null", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiDocumentation.EmptySuccess.class), examples = @ExampleObject(value = "{\"success\":true,\"data\":null}")))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "ROOM_BUSY", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiDocumentation.Failure.class)))
    public ApiResponse<Void> delete(@CurrentUser UUID userId, @PathVariable UUID roomId, @PathVariable UUID proposalId) {
        service.delete(roomId, userId, proposalId);
        return ApiResponse.success();
    }
}
