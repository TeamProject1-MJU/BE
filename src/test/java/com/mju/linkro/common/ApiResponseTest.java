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
