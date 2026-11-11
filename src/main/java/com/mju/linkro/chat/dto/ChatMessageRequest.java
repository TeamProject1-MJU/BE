package com.mju.linkro.chat.dto;

import com.mju.linkro.common.exception.BusinessException;
import com.mju.linkro.common.exception.ErrorCode;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/** Body of POST /rooms/{roomId}/messages. A JsonNode keeps "content": 123 or null a VALIDATION_ERROR, not a coercion. */
public record ChatMessageRequest(
        @Schema(implementation = String.class, type = "string", requiredMode = Schema.RequiredMode.REQUIRED, maxLength = 1000,
                description = "1~1000자(유니코드 코드포인트). 공백만 있는 메시지와 NUL 문자는 거절해요. @linkro가 있으면 AI가 답해요.",
                example = "@linkro 사당역에서 갈만한 곳 알려줘") JsonNode content) {
    public static final int MAX_LENGTH = 1000;

    /** The text as sent: nothing is trimmed or rewritten, so what a member typed is what the room shows. */
    public String validatedContent() {
        if (content == null || !content.isString()) throw invalid("content는 문자열이어야 해요.");
        String text = content.asString();
        if (text.isBlank()) throw invalid("content는 비어 있을 수 없어요.");
        if (text.indexOf('\u0000') >= 0) throw invalid("content에 사용할 수 없는 문자가 있어요.");
        if (text.codePointCount(0, text.length()) > MAX_LENGTH) throw invalid("content는 " + MAX_LENGTH + "자 이하여야 해요.");
        return text;
    }

    private static BusinessException invalid(String reason) {
        return new BusinessException(ErrorCode.VALIDATION_ERROR, Map.of("content", reason));
    }
}
