package com.mju.linkro.common;

import com.mju.linkro.common.exception.BusinessException;
import com.mju.linkro.common.exception.ErrorCode;
import com.mju.linkro.common.exception.GlobalExceptionHandler;
import com.mju.linkro.common.response.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.constraints.Size;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.web.bind.annotation.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class GlobalExceptionHandlerTest {
    private MockMvc mvc;
    private LocalValidatorFactoryBean validator;

    @BeforeEach
    void setUp() {
        validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mvc = MockMvcBuilders.standaloneSetup(new TestController())
                .setControllerAdvice(new GlobalExceptionHandler()).setValidator(validator).build();
    }

    @AfterEach
    void tearDown() {
        validator.close();
    }

    @Test
    void successResponsesHaveExactEnvelope() throws Exception {
        mvc.perform(get("/test/success"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"success\":true,\"data\":{\"id\":1}}"))
                .andExpect(jsonPath("$.error").doesNotExist());
        var result = mvc.perform(get("/test/empty"))
                .andExpect(status().isOk()).andReturn();
        assertThat(result.getResponse().getContentAsString()).isEqualTo("{\"success\":true,\"data\":null}");
    }

    @ParameterizedTest
    @EnumSource(ErrorCode.class)
    void businessExceptionsPreserveEveryCodeAndStatus(ErrorCode code) throws Exception {
        failure(mvc.perform(get("/test/business").param("code", code.name())),
                code.getStatus().value(), code.getCode())
                .andExpect(jsonPath("$.error.message").value(code.getMessage()))
                .andExpect(jsonPath("$.error.details").isEmpty());
    }

    @Test
    void businessExceptionSupportsDetails() throws Exception {
        failure(mvc.perform(get("/test/details")), 400, "VALIDATION_ERROR")
                .andExpect(jsonPath("$.error.details.maxMembers").value("현재 참여 인원보다 작게 설정할 수 없습니다."));
    }

    @Test
    void validFailureIncludesOnlyFieldReasons() throws Exception {
        failure(mvc.perform(post("/test/body").contentType(MediaType.APPLICATION_JSON)
                .content("{\"nickname\":\"abcdefghijklmnopqrstu\"}")), 400, "VALIDATION_ERROR")
                .andExpect(jsonPath("$.error.details.nickname").value("20자 이하여야 합니다."))
                .andExpect(jsonPath("$.error.details.length()").value(1));
    }

    @Test
    void constraintViolationIncludesFieldReason() throws Exception {
        failure(mvc.perform(get("/test/constraint")), 400, "VALIDATION_ERROR")
                .andExpect(jsonPath("$.error.details.nickname").value("20자 이하여야 합니다."));
    }

    @Test
    void malformedJsonUsesCommonEnvelope() throws Exception {
        failure(mvc.perform(post("/test/body").contentType(MediaType.APPLICATION_JSON)
                .content("{invalid")), 400, "VALIDATION_ERROR")
                .andExpect(jsonPath("$.error.details").isEmpty());
    }

    @Test
    void missingParameterUsesCommonEnvelope() throws Exception {
        failure(mvc.perform(get("/test/parameter")), 400, "VALIDATION_ERROR");
    }

    @Test
    void typeMismatchUsesCommonEnvelope() throws Exception {
        failure(mvc.perform(get("/test/parameter").param("count", "not-a-number")), 400, "VALIDATION_ERROR");
    }

    @Test
    void unsupportedMethodPreservesAllowHeader() throws Exception {
        failure(mvc.perform(put("/test/body")), 405, "METHOD_NOT_ALLOWED")
                .andExpect(header().string("Allow", "POST"));
    }

    @Test
    void unsupportedContentTypeUsesCommonEnvelope() throws Exception {
        failure(mvc.perform(post("/test/body").contentType(MediaType.TEXT_PLAIN)
                .content("hello")), 415, "UNSUPPORTED_MEDIA_TYPE");
    }

    @Test
    void unexpectedExceptionDoesNotExposeInternalInformation() throws Exception {
        var result = failure(mvc.perform(get("/test/unexpected")), 500, "INTERNAL_ERROR")
                .andExpect(jsonPath("$.error.message").value("서버 내부 오류가 발생했습니다."))
                .andExpect(jsonPath("$.error.details").isEmpty()).andReturn();
        assertThat(result.getResponse().getContentAsString()).doesNotContain("secret SQL", "RuntimeException", "stackTrace");
    }

    private ResultActions failure(ResultActions result, int statusCode, String code) throws Exception {
        return result.andExpect(status().is(statusCode))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.error.code").value(code))
                .andExpect(jsonPath("$.error.details").isMap())
                .andExpect(jsonPath("$.timestamp").doesNotExist())
                .andExpect(jsonPath("$.status").doesNotExist())
                .andExpect(jsonPath("$.path").doesNotExist());
    }

    @RestController
    @RequestMapping("/test")
    static class TestController {
        @GetMapping("/success")
        ApiResponse<Map<String, Integer>> success() { return ApiResponse.success(Map.of("id", 1)); }

        @GetMapping("/empty")
        ApiResponse<Void> empty() { return ApiResponse.success(); }

        @GetMapping("/business")
        ApiResponse<Void> business(@RequestParam("code") ErrorCode code) { throw new BusinessException(code); }

        @GetMapping("/details")
        ApiResponse<Void> details() {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    Map.of("maxMembers", "현재 참여 인원보다 작게 설정할 수 없습니다."));
        }

        @PostMapping(value = "/body", consumes = MediaType.APPLICATION_JSON_VALUE)
        ApiResponse<Void> body(@Valid @RequestBody Request request) { return ApiResponse.success(); }

        @GetMapping("/parameter")
        ApiResponse<Void> parameter(@RequestParam("count") int count) { return ApiResponse.success(); }

        @GetMapping("/constraint")
        ApiResponse<Void> constraint() {
            try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
                throw new ConstraintViolationException(factory.getValidator().validate(new Request("abcdefghijklmnopqrstu")));
            }
        }

        @GetMapping("/unexpected")
        ApiResponse<Void> unexpected() { throw new RuntimeException("secret SQL information"); }
    }

    record Request(@Size(max = 20, message = "20자 이하여야 합니다.") String nickname) {}
}
