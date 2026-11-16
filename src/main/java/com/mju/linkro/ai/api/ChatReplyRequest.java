package com.mju.linkro.ai.api;

import java.util.List;
import java.util.Objects;

/** @param tools the read-only tools the AI may call while answering (#129); null for an answer without tools */
public record ChatReplyRequest(String question, List<ChatTurn> recentMessages, String roomContext, ChatToolbox tools) {
    public ChatReplyRequest {
        Objects.requireNonNull(question, "question");
        recentMessages = recentMessages == null ? List.of() : List.copyOf(recentMessages);
    }

    public ChatReplyRequest(String question, List<ChatTurn> recentMessages, String roomContext) {
        this(question, recentMessages, roomContext, null);
    }

    public ChatReplyRequest withTools(ChatToolbox tools) {
        return new ChatReplyRequest(question, recentMessages, roomContext, tools);
    }
}
