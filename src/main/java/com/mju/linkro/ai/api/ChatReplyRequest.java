package com.mju.linkro.ai.api;

import java.util.List;
import java.util.Objects;

public record ChatReplyRequest(String question, List<ChatTurn> recentMessages, String roomContext) {
    public ChatReplyRequest {
        Objects.requireNonNull(question, "question");
        recentMessages = recentMessages == null ? List.of() : List.copyOf(recentMessages);
    }
}
