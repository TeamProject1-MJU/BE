package com.mju.linkro.user.controller;

import com.mju.linkro.auth.web.CurrentUser;
import com.mju.linkro.common.response.ApiResponse;
import com.mju.linkro.user.dto.NicknameUpdateRequest;
import com.mju.linkro.user.dto.NicknameUpdateResponse;
import com.mju.linkro.user.dto.UserProfileResponse;
import com.mju.linkro.user.service.UserService;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/users/me")
@RequiredArgsConstructor
public class UserController {
	private final UserService userService;

	@GetMapping
	public ApiResponse<UserProfileResponse> getMe(@CurrentUser UUID userId) {
		return ApiResponse.success(userService.getMe(userId));
	}

	@PatchMapping
	public ApiResponse<NicknameUpdateResponse> updateNickname(@CurrentUser UUID userId,
		@RequestBody NicknameUpdateRequest request) {
		return ApiResponse.success(userService.updateNickname(userId, request.getNickname()));
	}

	@DeleteMapping
	public ApiResponse<Void> withdraw(@CurrentUser UUID userId) {
		userService.withdraw(userId);
		return ApiResponse.success();
	}
}
