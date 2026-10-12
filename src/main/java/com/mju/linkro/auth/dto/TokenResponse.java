package com.mju.linkro.auth.dto;

public record TokenResponse(String accessToken, String refreshToken, long accessTokenExpiresIn) {
}
