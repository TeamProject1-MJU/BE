package com.mju.linkro.common.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum ErrorCode {
    VALIDATION_ERROR(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "요청 값이 올바르지 않습니다."),
    IMAGE_INVALID(HttpStatus.BAD_REQUEST, "IMAGE_INVALID", "이미지가 올바르지 않습니다."),
    AUTH_REQUIRED(HttpStatus.UNAUTHORIZED, "AUTH_REQUIRED", "인증이 필요합니다."),
    TOKEN_EXPIRED(HttpStatus.UNAUTHORIZED, "TOKEN_EXPIRED", "토큰이 만료되었습니다."),
    TOKEN_INVALID(HttpStatus.UNAUTHORIZED, "TOKEN_INVALID", "토큰이 올바르지 않습니다."),
    KAKAO_AUTH_FAILED(HttpStatus.UNAUTHORIZED, "KAKAO_AUTH_FAILED", "카카오 인증에 실패했습니다."),
    NOT_ROOM_MEMBER(HttpStatus.FORBIDDEN, "NOT_ROOM_MEMBER", "방 참여자가 아닙니다."),
    NOT_ROOM_OWNER(HttpStatus.FORBIDDEN, "NOT_ROOM_OWNER", "방장 권한이 필요합니다."),
    STATION_NOT_FOUND(HttpStatus.NOT_FOUND, "STATION_NOT_FOUND", "역을 찾을 수 없습니다."),
    ROOM_NOT_FOUND(HttpStatus.NOT_FOUND, "ROOM_NOT_FOUND", "방을 찾을 수 없습니다."),
    ROOM_CODE_INVALID(HttpStatus.NOT_FOUND, "ROOM_CODE_INVALID", "방 코드가 올바르지 않습니다."),
    ROUTE_NOT_FOUND(HttpStatus.NOT_FOUND, "ROUTE_NOT_FOUND", "경로를 찾을 수 없습니다."),
    ROOM_FULL(HttpStatus.CONFLICT, "ROOM_FULL", "방 참여 인원이 가득 찼습니다."),
    ALREADY_JOINED(HttpStatus.CONFLICT, "ALREADY_JOINED", "이미 참여한 방입니다."),
    ROOM_BUSY(HttpStatus.CONFLICT, "ROOM_BUSY", "방에서 작업이 진행 중입니다."),
    STATION_UNSUPPORTED(HttpStatus.UNPROCESSABLE_CONTENT, "STATION_UNSUPPORTED", "지원하지 않는 역입니다."),
    DEPARTURE_MISSING(HttpStatus.UNPROCESSABLE_CONTENT, "DEPARTURE_MISSING", "출발지가 설정되지 않았습니다."),
    TOO_FEW_MEMBERS(HttpStatus.UNPROCESSABLE_CONTENT, "TOO_FEW_MEMBERS", "참여 인원이 부족합니다."),
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "요청 횟수 제한을 초과했습니다."),
    AI_QUOTA_EXCEEDED(HttpStatus.TOO_MANY_REQUESTS, "AI_QUOTA_EXCEEDED", "AI 사용 한도를 초과했습니다."),
    EXTERNAL_API_ERROR(HttpStatus.BAD_GATEWAY, "EXTERNAL_API_ERROR", "외부 서비스 호출에 실패했습니다."),
    AI_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "AI_UNAVAILABLE", "AI 서비스를 사용할 수 없습니다."),
    NOT_FOUND(HttpStatus.NOT_FOUND, "NOT_FOUND", "??? ???? ?? ? ????."),
    NOT_ACCEPTABLE(HttpStatus.NOT_ACCEPTABLE, "NOT_ACCEPTABLE", "???? ?? ?? ?????."),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED", "지원하지 않는 HTTP 메서드입니다."),
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_MEDIA_TYPE", "지원하지 않는 Content-Type입니다."),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "서버 내부 오류가 발생했습니다.");

    private final HttpStatus status;
    private final String code;
    private final String message;
}
