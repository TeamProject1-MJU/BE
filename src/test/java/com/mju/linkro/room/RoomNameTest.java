package com.mju.linkro.room;

import com.mju.linkro.room.domain.MeetingRoom;
import com.mju.linkro.room.dto.RoomWriteRequest;
import com.mju.linkro.common.exception.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class RoomNameTest {
    private final JsonMapper mapper = JsonMapper.builder().build();

    @ParameterizedTest
    @ValueSource(strings = {"a", "가", "😀", "é", "\t", "\n", "\u2003", "\u00a0"})
    void requestsConstructorAndRenameUseSameNormalization(String name) {
        String input = "  " + name + "  ";
        RoomWriteRequest request = mapper.readValue(mapper.writeValueAsString(Map.of("name", input)), RoomWriteRequest.class);
        MeetingRoom room = new MeetingRoom("ABC123", input, UUID.randomUUID());
        assertThat(room.getName()).isEqualTo(name).isEqualTo(request.validatedName(true));
        room.rename(input);
        assertThat(room.getName()).isEqualTo(name);
    }

    @ParameterizedTest @NullSource @ValueSource(strings = {"", " ", "   ", "a\0b"})
    void invalidNamesCannotEnterDomainAndStillProduceRequest400(String input) {
        assertInvalid(input);
    }

    @Test void codePointBoundaryIsTheSameForDomainAndRequest() {
        String thirty = "😀".repeat(30);
        MeetingRoom room = new MeetingRoom("ABC123", thirty, UUID.randomUUID());
        assertThat(room.getName()).isEqualTo(thirty);
        room.rename("  " + thirty + "  ");
        assertThat(room.getName()).isEqualTo(thirty);
        assertInvalid("😀".repeat(31));
        assertInvalid("é".repeat(16));
    }

    private void assertInvalid(String input) {
        assertThatThrownBy(() -> new MeetingRoom("ABC123", input, UUID.randomUUID())).isInstanceOf(IllegalArgumentException.class);
        MeetingRoom room = new MeetingRoom("ABC123", "before", UUID.randomUUID());
        assertThatThrownBy(() -> room.rename(input)).isInstanceOf(IllegalArgumentException.class);
        assertThat(room.getName()).isEqualTo("before");
        Map<String, String> body = new HashMap<>(); body.put("name", input);
        RoomWriteRequest request = mapper.readValue(mapper.writeValueAsString(body), RoomWriteRequest.class);
        assertThatThrownBy(() -> request.validatedName(true)).isInstanceOfSatisfying(BusinessException.class, error -> {
            assertThat(error.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR);
            assertThat(error.getErrorCode().getStatus().value()).isEqualTo(400);
        });
    }
}
