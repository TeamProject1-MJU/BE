package com.mju.linkro.common;

import com.mju.linkro.common.exception.BusinessException;
import com.mju.linkro.common.exception.ErrorCode;
import com.mju.linkro.common.exception.GlobalExceptionHandler;
import com.mju.linkro.common.response.ApiResponse;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.http.HttpStatus;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpStatus.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Keeps ErrorCode aligned with the error code table in API spec v1.0 (section 2). */
class ErrorCodeTest {
    private static final Map<ErrorCode, HttpStatus> EXPECTED_STATUS = expectedStatus();

    private static Map<ErrorCode, HttpStatus> expectedStatus() {
        Map<ErrorCode, HttpStatus> expected = new EnumMap<>(ErrorCode.class);
        put(expected, BAD_REQUEST, "VALIDATION_ERROR", "IMAGE_INVALID");
        put(expected, UNAUTHORIZED, "AUTH_REQUIRED", "TOKEN_EXPIRED", "TOKEN_INVALID", "KAKAO_AUTH_FAILED");
        put(expected, FORBIDDEN, "NOT_ROOM_MEMBER", "NOT_ROOM_OWNER", "NOT_PROPOSAL_OWNER");
        put(expected, NOT_FOUND, "STATION_NOT_FOUND", "ROOM_NOT_FOUND", "ROOM_CODE_INVALID", "ROUTE_NOT_FOUND",
                "PROPOSAL_NOT_FOUND", "RUN_NOT_FOUND", "RESULT_NOT_FOUND");
        put(expected, CONFLICT, "ROOM_FULL", "ALREADY_JOINED", "ROOM_BUSY", "ALREADY_PROPOSED");
        put(expected, UNPROCESSABLE_CONTENT, "STATION_UNSUPPORTED", "DEPARTURE_MISSING", "TOO_FEW_MEMBERS",
                "PROPOSAL_LIMIT_EXCEEDED");
        put(expected, TOO_MANY_REQUESTS, "AI_QUOTA_EXCEEDED");
        put(expected, BAD_GATEWAY, "EXTERNAL_API_ERROR");
        put(expected, SERVICE_UNAVAILABLE, "AI_UNAVAILABLE");
        // Framework-level codes used by GlobalExceptionHandler, not listed in the spec table.
        put(expected, NOT_FOUND, "NOT_FOUND");
        put(expected, NOT_ACCEPTABLE, "NOT_ACCEPTABLE");
        put(expected, METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED");
        put(expected, UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_MEDIA_TYPE");
        put(expected, INTERNAL_SERVER_ERROR, "INTERNAL_ERROR");
        return expected;
    }

    private static void put(Map<ErrorCode, HttpStatus> expected, HttpStatus status, String... names) {
        for (String name : names) {
            expected.put(ErrorCode.valueOf(name), status);
        }
    }

    @ParameterizedTest
    @EnumSource(ErrorCode.class)
    void codeMatchesEnumName(ErrorCode code) {
        assertThat(code.getCode()).isEqualTo(code.name());
    }

    @Test
    void codesAreUnique() {
        var codes = Arrays.stream(ErrorCode.values()).map(ErrorCode::getCode).toList();
        assertThat(codes).doesNotHaveDuplicates();
    }

    @Test
    void everyCodeHasExpectedSpecStatus() {
        assertThat(EXPECTED_STATUS.keySet()).containsExactlyInAnyOrder(ErrorCode.values());
    }

    @ParameterizedTest
    @EnumSource(ErrorCode.class)
    void statusMatchesSpec(ErrorCode code) {
        assertThat(code.getStatus()).isEqualTo(EXPECTED_STATUS.get(code));
    }

    @Test
    void newCodeUsesCommonFailureEnvelope() throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new TestController())
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        mvc.perform(get("/test/proposal-limit"))
                .andExpect(status().is(UNPROCESSABLE_CONTENT.value()))
                .andExpect(content().json("{\"success\":false,\"error\":{\"code\":\"PROPOSAL_LIMIT_EXCEEDED\","
                        + "\"message\":\"약속역 후보 최대 개수를 초과했습니다.\",\"details\":{}}}", JsonCompareMode.STRICT));
    }

    @RestController
    static class TestController {
        @GetMapping("/test/proposal-limit")
        ApiResponse<Void> proposalLimit() { throw new BusinessException(ErrorCode.PROPOSAL_LIMIT_EXCEEDED); }
    }
}
