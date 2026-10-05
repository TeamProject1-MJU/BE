package com.mju.linkro.common;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.mju.linkro.common.response.ApiResponse;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class ApiResponseTest {
    private final JsonMapper mapper = JsonMapper.builder()
            .changeDefaultPropertyInclusion(include -> include.withValueInclusion(JsonInclude.Include.NON_NULL))
            .build();

    @Test
    void variantsCannotAcceptContradictorySuccessFlags() {
        assertThat(new ApiResponse.Success<>(null).success()).isTrue();
        assertThat(new ApiResponse.Failure(new ApiResponse.Error("CODE", "message", null)).success()).isFalse();
        assertThat(ApiResponse.Success.class.getRecordComponents()).extracting(java.lang.reflect.RecordComponent::getName)
                .containsExactly("data");
        assertThat(ApiResponse.Failure.class.getRecordComponents()).extracting(java.lang.reflect.RecordComponent::getName)
                .containsExactly("error");
    }

    @Test
    void nullDetailsAreDroppedAndMapsAreImmutableSnapshots() {
        Map<String, Object> details = new java.util.HashMap<>();
        details.put(null, "ignored");
        details.put("empty", null);
        details.put("safe", "value");
        var cause = new IllegalStateException("secret");
        var exception = new com.mju.linkro.common.exception.BusinessException(
                com.mju.linkro.common.exception.ErrorCode.EXTERNAL_API_ERROR, details, cause);
        var error = new ApiResponse.Error("CODE", "message", details);
        details.put("safe", "changed");
        assertThat(exception.getDetails()).isEqualTo(Map.of("safe", "value"));
        assertThat(error.details()).isEqualTo(Map.of("safe", "value"));
        assertThat(exception.getCause()).isSameAs(cause);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> exception.getDetails().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        var nullDetails = new com.mju.linkro.common.exception.BusinessException(
                com.mju.linkro.common.exception.ErrorCode.VALIDATION_ERROR, (Map<String, ?>) null);
        assertThat(nullDetails.getDetails()).isEmpty();
    }

    @Test
    void successContainsOnlySuccessAndData() {
        assertThat(mapper.readTree(mapper.writeValueAsString(ApiResponse.success(Map.of("id", 1)))))
                .isEqualTo(mapper.readTree("{\"success\":true,\"data\":{\"id\":1}}"));
    }

    @Test
    void nullDataIsPresentEvenWhenMapperOmitsNulls() {
        var expected = mapper.readTree("{\"success\":true,\"data\":null}");
        assertThat(mapper.readTree(mapper.writeValueAsString(ApiResponse.success(null)))).isEqualTo(expected);
        assertThat(mapper.readTree(mapper.writeValueAsString(ApiResponse.success()))).isEqualTo(expected);
    }

    @Test
    void failureContainsOnlySuccessAndErrorAndNormalizesNullDetails() {
        assertThat(mapper.readTree(mapper.writeValueAsString(ApiResponse.failure("CODE", "message", null))))
                .isEqualTo(mapper.readTree("{\"success\":false,\"error\":{\"code\":\"CODE\",\"message\":\"message\",\"details\":{}}}"));
    }
}
