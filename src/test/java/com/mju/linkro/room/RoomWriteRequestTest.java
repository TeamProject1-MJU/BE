package com.mju.linkro.room;

import com.mju.linkro.room.dto.RoomWriteRequest;
import com.mju.linkro.common.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class RoomWriteRequestTest {
    private final JsonMapper mapper = JsonMapper.builder().build();
    private RoomWriteRequest request(String json) { return mapper.readValue(json, RoomWriteRequest.class); }

    @Test void omissionAndExplicitNullAreDifferent() {
        var omitted = request("{}");
        var explicit = request("{\"name\":null,\"appointmentAt\":null}");
        assertThat(omitted.name()).isNull();
        assertThat(omitted.appointmentAt()).isNull();
        assertThat(explicit.name().isNull()).isTrue();
        assertThat(explicit.appointmentAt().isNull()).isTrue();
        assertThatThrownBy(() -> explicit.validatedName(false)).isInstanceOf(BusinessException.class);
        assertThat(explicit.validatedAppointmentAt()).isNull();
    }

    @ParameterizedTest @ValueSource(strings = {"a", "가", "\t", "\n", "\u2003", "😀", "é"})
    void onlyBasicSpacesAreTrimmed(String name) {
        var req = request(mapper.writeValueAsString(java.util.Map.of("name", "  " + name + "  ")));
        assertThat(req.validatedName(true)).isEqualTo(name);
    }

    @ParameterizedTest @ValueSource(ints = {1, 30})
    void countsCodePoints(int count) {
        String name = "😀".repeat(count);
        assertThat(request(mapper.writeValueAsString(java.util.Map.of("name", name))).validatedName(true)).isEqualTo(name);
    }

    @ParameterizedTest @ValueSource(strings = {"{}", "{\"name\":null}", "{\"name\":true}", "{\"name\":1}",
            "{\"name\":[]}", "{\"name\":{}}", "{\"name\":\" \"}", "{\"name\":\"\"}"})
    void invalidNames(String json) {
        assertThatThrownBy(() -> request(json).validatedName(true)).isInstanceOf(BusinessException.class);
    }

    @Test void tooLongAndNulAreInvalid() {
        for (String name : java.util.List.of("😀".repeat(31), "é".repeat(16), "a\0b")) {
            assertThatThrownBy(() -> request(mapper.writeValueAsString(java.util.Map.of("name", name))).validatedName(true))
                    .isInstanceOf(BusinessException.class);
        }
    }

    @ParameterizedTest @ValueSource(strings = {"1", "true", "[]", "{}", "\"no\"", "\"2026-11-02T12:00:00\""})
    void invalidAppointments(String json) {
        assertThatThrownBy(() -> request("{\"appointmentAt\":" + json + "}").validatedAppointmentAt())
                .isInstanceOf(BusinessException.class);
    }
}
