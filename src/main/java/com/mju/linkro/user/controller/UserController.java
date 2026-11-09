package com.mju.linkro.user.controller;

import com.mju.linkro.auth.web.CurrentUser;
import com.mju.linkro.common.response.ApiResponse;
import com.mju.linkro.user.dto.NicknameUpdateRequest;
import com.mju.linkro.user.dto.NicknameUpdateResponse;
import com.mju.linkro.user.dto.UserProfileResponse;
import com.mju.linkro.user.service.UserService;
import com.mju.linkro.common.config.OpenApiConfig;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import com.mju.linkro.common.config.ApiDocumentation;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.*;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/users/me")
@RequiredArgsConstructor
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
@ApiResponses({
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "VALIDATION_ERROR", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiDocumentation.Failure.class))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "AUTH_REQUIRED / TOKEN_INVALID / TOKEN_EXPIRED", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiDocumentation.Failure.class))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "INTERNAL_ERROR", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiDocumentation.Failure.class)))
})
public class UserController {
	private final UserService userService;

	@GetMapping
	@Operation(summary = "내 정보 조회", description = "Access Token의 사용자 정보를 조회합니다. userId 입력은 받지 않습니다.")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "내 정보", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiDocumentation.Profile.class)))
	public ApiResponse<UserProfileResponse> getMe(@CurrentUser UUID userId) {
		return ApiResponse.success(userService.getMe(userId));
	}

	@PatchMapping
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "ROOM_BUSY: 사용자 잠금 대기 초과", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiDocumentation.Failure.class)))
	@Operation(summary = "내 닉네임 수정", description = "nickname 필수. 생략·null은 오류입니다. 앞뒤 strip 후 코드포인트 2~20자. 방 PATCH와 달리 생략 시 유지하지 않습니다.")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "수정된 id와 nickname", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiDocumentation.Nickname.class)))
	public ApiResponse<NicknameUpdateResponse> updateNickname(@CurrentUser UUID userId,
		@RequestBody NicknameUpdateRequest request) {
		return ApiResponse.success(userService.updateNickname(userId, request.getNickname()));
	}

	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "ROOM_BUSY", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiDocumentation.Failure.class)))
	@DeleteMapping
	@Operation(summary = "회원 탈퇴", description = "모든 참여 방에서 퇴장한 뒤 계정을 soft delete하고 모든 Refresh Token을 폐기합니다. 전체 처리는 원자적입니다.")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "탈퇴 성공. data는 null.", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiDocumentation.EmptySuccess.class), examples = @ExampleObject(value = "{\"success\":true,\"data\":null}")))
	public ApiResponse<Void> withdraw(@CurrentUser UUID userId) {
		userService.withdraw(userId);
		return ApiResponse.success();
	}
}
