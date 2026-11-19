package com.mju.linkro.chat.service;

import com.mju.linkro.ai.api.AiClient;
import com.mju.linkro.ai.api.ChatToolbox;
import com.mju.linkro.ai.api.GeneratedText;
import com.mju.linkro.chat.dto.ChatResponses.Message;
import com.mju.linkro.common.exception.BusinessException;
import com.mju.linkro.common.exception.ErrorCode;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Answers one @링크로 question (API 10.1): AI reply as an AI message, or a SYSTEM notice when the AI fails. The
 * asker's USER message is already committed when this runs, so no failure here can lose it. Never throws. The AI may
 * call the tools of {@link ChatTools} for this room and asker (#129); too many rounds or an AI failure is the
 * same SYSTEM notice as any other AI failure. When its run_recommendation saved a run with results (#168), the answer
 * message carries it in meta.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class ChatAiResponder {
    static final String QUOTA_NOTICE = "AI 사용 한도를 초과해 답변하지 못했어요. 잠시 후 다시 시도해 주세요.";
    static final String UNAVAILABLE_NOTICE = "AI가 답변을 만들지 못했어요. 잠시 후 다시 시도해 주세요.";

    private final AiClient ai;
    private final ChatRoomContext context;
    private final ChatMessageWriter writer;
    private final ChatTools tools;

    void reply(UUID roomId, Message question) {
        try {
            ChatToolbox toolbox = tools.forAsker(roomId, question.senderId());
            GeneratedText answer = ai.replyToChat(context.build(roomId, question).withTools(toolbox));
            if (answer == null || answer.text() == null || answer.text().isBlank()) {
                throw new BusinessException(ErrorCode.AI_UNAVAILABLE);
            }
            // Only a completed answer opens the run its question saved (#168); a failure above leaves no meta anywhere.
            UUID runId = toolbox instanceof ChatTools.Asker asker ? asker.recommendationRunId() : null;
            if (runId == null) {
                writer.recordAi(roomId, answer.text().strip());
            } else {
                writer.recordAiRecommendation(roomId, answer.text().strip(), runId);
            }
        } catch (BusinessException failure) {
            notifyFailure(roomId, failure.getErrorCode());
        } catch (RuntimeException failure) {
            log.warn("@링크로 answer failed in room {}", roomId, failure);
            notifyFailure(roomId, ErrorCode.AI_UNAVAILABLE);
        }
    }

    /** Also used when the answer could not even be queued. */
    void notifyFailure(UUID roomId, ErrorCode code) {
        try {
            writer.recordSystem(roomId, code == ErrorCode.AI_QUOTA_EXCEEDED ? QUOTA_NOTICE : UNAVAILABLE_NOTICE);
        } catch (RuntimeException failure) {
            // Typically the room is gone (the last member left while the AI was thinking): nobody is left to tell.
            log.warn("Could not save the AI failure notice for room {}: {}", roomId, failure.toString());
        }
    }
}
