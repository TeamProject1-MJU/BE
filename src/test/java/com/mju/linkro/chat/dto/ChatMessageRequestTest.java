package com.mju.linkro.chat.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mju.linkro.common.exception.BusinessException;
import com.mju.linkro.common.exception.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

class ChatMessageRequestTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static ChatMessageRequest of(String json) {
        return JSON.readValue(json, ChatMessageRequest.class);
    }

    private static ChatMessageRequest text(String content) {
        return new ChatMessageRequest(JSON.getNodeFactory().stringNode(content));
    }

    @Test
    void contentIsKeptExactlyAsSent() {
        assertThat(text("  안녕  \n").validatedContent()).isEqualTo("  안녕  \n");
    }

    @Test
    void exactly1000CodePointsPass_1001Fail() {
        assertThat(text("가".repeat(1000)).validatedContent()).hasSize(1000);
        // Emoji are two UTF-16 chars but one character for the limit and for the database's char_length.
        assertThat(text("😀".repeat(1000)).validatedContent()).isNotEmpty();
        assertThatThrownBy(() -> text("가".repeat(1001)).validatedContent()).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getDetails()).containsOnlyKeys("content"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"content\":null}", "{\"content\":123}", "{\"content\":true}", "{\"content\":[\"a\"]}",
            "{\"content\":\"\"}", "{\"content\":\"   \\n\\t \"}", "{\"content\":\"a\\u0000b\"}"})
    void badContentIsAValidationError(String json) {
        assertThatThrownBy(() -> of(json).validatedContent()).isInstanceOfSatisfying(BusinessException.class, e -> {
            assertThat(e.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR);
            assertThat(e.getDetails()).containsKey("content");
        });
    }
}
