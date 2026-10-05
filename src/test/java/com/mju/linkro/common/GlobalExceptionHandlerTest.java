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
import jakarta.validation.constraints.Min;
import org.springframework.test.json.JsonCompareMode;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;
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
                .andExpect(content().json("{\"success\":true,\"data\":{\"id\":1}}", JsonCompareMode.STRICT))
                .andExpect(jsonPath("$.error").doesNotExist());
        var result = mvc.perform(get("/test/empty"))
                .andExpect(status().isOk()).andReturn();
        assertThat(result.getResponse().getContentAsString()).isEqualTo("{\"data\":null,\"success\":true}");
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

    @Test
    void missingUrlUsesNotFound() throws Exception {
        failure(mvc.perform(get("/missing")), 404, "NOT_FOUND");
    }

    @Test
    void unacceptableResponseUsesStableCode() throws Exception {
        failure(mvc.perform(get("/test/success").accept(MediaType.APPLICATION_XML)), 406, "NOT_ACCEPTABLE");
    }

    @Test
    void methodParameterValidationIncludesNames() throws Exception {
        failure(mvc.perform(get("/test/validated/0").param("size", "0")), 400, "VALIDATION_ERROR")
                .andExpect(jsonPath("$.error.details.size").value("minimum size"))
                .andExpect(jsonPath("$.error.details.id").value("minimum id"))
                .andExpect(result -> assertThat(result.getResolvedException())
                        .isInstanceOf(org.springframework.web.method.annotation.HandlerMethodValidationException.class));
    }

    @Test
    void classLevelValidationUsesGlobalKey() throws Exception {
        failure(mvc.perform(post("/test/global").contentType(MediaType.APPLICATION_JSON).content("{}")),
                400, "VALIDATION_ERROR").andExpect(jsonPath("$.error.details._global").value("global reason"));
    }

    @Test
    void globalAndDuplicateFieldErrorsChooseDeterministicMessages() throws Exception {
        for (boolean reverse : new boolean[] {false, true}) {
            var binding = new org.springframework.validation.BeanPropertyBindingResult(new Request("x"), "request");
            var messages = reverse ? java.util.List.of("z reason", "a reason") : java.util.List.of("a reason", "z reason");
            messages.forEach(message -> {
                binding.addError(new org.springframework.validation.ObjectError("request", message));
                binding.addError(new org.springframework.validation.FieldError("request", "nickname", message));
            });
            var method = TestController.class.getDeclaredMethod("body", Request.class);
            var exception = new org.springframework.web.bind.MethodArgumentNotValidException(
                    new org.springframework.core.MethodParameter(method, 0), binding);
            var handler = new GlobalExceptionHandler();
            var response = handler.handleException(exception,
                    new org.springframework.web.context.request.ServletWebRequest(new org.springframework.mock.web.MockHttpServletRequest()));
            var error = ((ApiResponse.Failure) response.getBody()).error();
            assertThat(error.details()).isEqualTo(Map.of("_global", "a reason", "nickname", "a reason"));
        }
    }

    @ParameterizedTest
    @EnumSource(value = ErrorCode.class, names = {"EXTERNAL_API_ERROR", "AI_UNAVAILABLE"})
    void serverBusinessCausesAreLoggedButNeverReturned(ErrorCode code) throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            failure(mvc.perform(get("/test/cause").param("code", code.name())), code.getStatus().value(), code.getCode())
                    .andExpect(content().json("{\"success\":false,\"error\":{\"code\":\"" + code.getCode()
                            + "\",\"message\":\"" + code.getMessage() + "\",\"details\":{}}}", JsonCompareMode.STRICT));
            assertThat(appender.list).anySatisfy(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.ERROR);
                assertThat(event.getThrowableProxy().getCause().getMessage()).isEqualTo("private upstream secret");
                assertThat(event.getThrowableProxy().getStackTraceElementProxyArray()).isNotEmpty();
            });
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    private ResultActions failure(ResultActions result, int statusCode, String code) throws Exception {
        return result.andExpect(status().is(statusCode))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$.error.length()").value(3))
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

        @PostMapping("/global")
        ApiResponse<Void> global(@Valid @RequestBody GlobalRequest request) { return ApiResponse.success(); }

        @GetMapping("/validated/{id}")
        ApiResponse<Void> validated(@PathVariable("id") @Min(value = 1, message = "minimum id") int id,
                @RequestParam("size") @Min(value = 1, message = "minimum size") int size) {
            return ApiResponse.success();
        }

        @GetMapping("/cause")
        ApiResponse<Void> cause(@RequestParam("code") ErrorCode code) {
            throw new BusinessException(code, new IllegalStateException("private upstream secret"));
        }

        @GetMapping("/unexpected")
        ApiResponse<Void> unexpected() { throw new RuntimeException("secret SQL information"); }
    }

    @java.lang.annotation.Target(java.lang.annotation.ElementType.TYPE)
    @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
    @jakarta.validation.Constraint(validatedBy = GlobalValidator.class)
    @interface GlobalConstraint {
        String message() default "global reason";
        Class<?>[] groups() default {};
        Class<? extends jakarta.validation.Payload>[] payload() default {};
    }

    public static class GlobalValidator implements jakarta.validation.ConstraintValidator<GlobalConstraint, GlobalRequest> {
        public boolean isValid(GlobalRequest request, jakarta.validation.ConstraintValidatorContext context) { return false; }
    }

    @GlobalConstraint
    record GlobalRequest() {}

    record Request(@Size(max = 20, message = "20자 이하여야 합니다.") String nickname) {}
}
