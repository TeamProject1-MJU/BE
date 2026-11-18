package com.mju.linkro.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mju.linkro.ai.api.AiClient;
import com.mju.linkro.ai.api.ChatReplyRequest;
import com.mju.linkro.ai.api.GeneratedText;
import com.mju.linkro.chat.domain.SenderType;
import com.mju.linkro.chat.dto.ChatResponses.Message;
import com.mju.linkro.chat.event.MessageCreatedEvent;
import com.mju.linkro.chat.repository.RoomMessageRepository;
import com.mju.linkro.common.exception.BusinessException;
import com.mju.linkro.common.exception.ErrorCode;
import com.mju.linkro.recommendation.dto.RecommendationResponse;
import com.mju.linkro.recommendation.dto.RecommendationResponse.*;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import tools.jackson.databind.JsonNode;

/**
 * The AI answer's meta per outcome (#168): the run id only when run_recommendation saved a run with results and the answer
 * completed; null for no results, 8.1 errors, invalid input, a tool timeout and a failed answer. The writer then stores it
 * and announces the saved message (what GET reads back and the STOMP event carries) with it.
 */
class ChatRecommendationMetaTest extends ChatToolsTestBase {
    static final UUID RUN = UUID.fromString("0192f3c1-0000-7000-8000-0000000000aa");

    AiClient ai = mock(AiClient.class);
    ChatRoomContext context = mock(ChatRoomContext.class);
    ChatMessageWriter writer = mock(ChatMessageWriter.class);
    ChatAiResponder responder;
    Message question;

    @BeforeEach
    void setUp() {
        question = new Message(UUID.randomUUID(), SenderType.USER, asker, "민지", "@링크로 추천해줘", Instant.parse("2026-11-17T01:00:00Z"), null);
        when(context.build(any(), any())).thenReturn(new ChatReplyRequest("질문", List.of(), "방 정보"));
        responder = new ChatAiResponder(ai, context, writer, tools);
    }

    /** The mock AI calls run_recommendation with {@code arguments} {@code times} times, then answers "답변". */
    void ask(String arguments, int times) {
        org.mockito.Mockito.doAnswer(call -> {
            ChatReplyRequest request = call.getArgument(0);
            for (int i = 0; i < times; i++) request.tools().run("run_recommendation", arguments);
            return new GeneratedText(" 답변 ");
        }).when(ai).replyToChat(any());
        responder.reply(roomId, question);
    }

    void assertMetaNull() {
        verify(writer).recordAi(roomId, "답변");
        verify(writer, never()).recordAiRecommendation(any(), any(), any());
        verify(writer, never()).recordSystem(any(), any());
    }

    RecommendationResponse response(List<Result> results) {
        return new RecommendationResponse(RUN.toString(), "2026-11-17T10:00:00+09:00", false, List.of(), null,
                AppliedConstraints.none(), null, results);
    }

    Result timeResult() {
        return new Result("res-1", "TIME", new RecommendedStation(3, "종로3가", 37.5, 127.0, true), new Metrics(31.0, 4, 35, 1650, 1),
                List.of(), List.of(), "설명", "AI");
    }

    @Test
    void aSavedRunWithResultsAndACompletedAnswerCarriesTheRunId() {
        when(recommendations.create(eq(asker), eq(roomId), any())).thenReturn(response(List.of(timeResult())));

        ask("{\"maxMinutes\":40}", 1);

        verify(writer).recordAiRecommendation(roomId, "답변", RUN);
        verify(writer, never()).recordAi(any(), any());
    }

    @Test
    void aDuplicateCallInTheSameQuestionStillOpensTheOneRunThatWasSaved() {
        when(recommendations.create(eq(asker), eq(roomId), any())).thenReturn(response(List.of(timeResult())));

        ask("{}", 2);

        verify(recommendations).create(any(), any(), any());
        verify(writer).recordAiRecommendation(roomId, "답변", RUN);
    }

    @Test
    void noResultsLeavesMetaNull() {
        when(recommendations.create(eq(asker), eq(roomId), any())).thenReturn(response(List.of()));
        ask("{\"maxMinutes\":1}", 1);
        assertMetaNull();
    }

    @Test
    void anErrorOf81LeavesMetaNull() {
        when(recommendations.create(eq(asker), eq(roomId), any())).thenThrow(new BusinessException(ErrorCode.APPOINTMENT_PASSED));
        ask("{}", 1);
        assertMetaNull();
    }

    @Test
    void invalidInputLeavesMetaNullAndRunsNothing() {
        ask("{\"maxMinutes\":999}", 1);
        assertMetaNull();
        verify(recommendations, never()).create(any(), any(), any());
    }

    @Test
    void aToolTimeoutLeavesMetaNullEvenIfTheRunIsSavedLater() {
        properties.setAiRecommendationToolTimeout(Duration.ofMillis(100));
        when(recommendations.create(eq(asker), eq(roomId), any())).thenAnswer(call -> {
            Thread.sleep(1000);
            return response(List.of(timeResult()));
        });
        ask("{}", 1);
        assertMetaNull();
    }

    @Test
    void aToolErrorLeavesMetaNull() {
        when(recommendations.create(eq(asker), eq(roomId), any())).thenThrow(new IllegalStateException("db down"));
        ask("{}", 1);
        assertMetaNull();
    }

    @Test
    void aFailedAnswerAfterASavedRunSavesTheSystemNoticeWithoutMeta() {
        when(recommendations.create(eq(asker), eq(roomId), any())).thenReturn(response(List.of(timeResult())));
        org.mockito.Mockito.doAnswer(call -> {
            ((ChatReplyRequest) call.getArgument(0)).tools().run("run_recommendation", "{}");
            throw new BusinessException(ErrorCode.AI_UNAVAILABLE);
        }).when(ai).replyToChat(any());

        responder.reply(roomId, question);

        verify(writer).recordSystem(roomId, ChatAiResponder.UNAVAILABLE_NOTICE);
        verify(writer, never()).recordAi(any(), any());
        verify(writer, never()).recordAiRecommendation(any(), any(), any());
    }

    @Test
    void anAnswerWithoutAnyToolCallHasNoMeta() {
        when(ai.replyToChat(any())).thenReturn(new GeneratedText("답변"));
        responder.reply(roomId, question);
        assertMetaNull();
    }

    // ---- the writer: storage and the realtime event ----

    @Test
    void theWriterStoresTheMetaAndTheEventCarriesTheSavedMessageWithIt() {
        RoomMessageRepository messages = mock(RoomMessageRepository.class);
        ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
        JsonNode meta = JSON.readTree("{\"type\":\"RECOMMENDATION\",\"runId\":\"" + RUN + "\"}");
        when(messages.insert(any(), eq(roomId), eq(SenderType.AI), eq(null), eq("답변"), any())).thenReturn(
                new Message(UUID.randomUUID(), SenderType.AI, null, null, "답변", Instant.EPOCH, meta));
        var realWriter = new ChatMessageWriter(access, messages, events);

        Message saved = realWriter.recordAiRecommendation(roomId, "답변", RUN);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, ?>> stored = ArgumentCaptor.forClass(Map.class);
        verify(messages).insert(any(), eq(roomId), eq(SenderType.AI), eq(null), eq("답변"), stored.capture());
        assertThat(stored.getValue()).isEqualTo(Map.of("type", "RECOMMENDATION", "runId", RUN.toString()));
        assertThat(saved.meta().get("type").asString()).isEqualTo("RECOMMENDATION");
        assertThat(saved.meta().get("runId").asString()).isEqualTo(RUN.toString());
        ArgumentCaptor<MessageCreatedEvent> event = ArgumentCaptor.forClass(MessageCreatedEvent.class);
        verify(events).publishEvent(event.capture());
        assertThat(event.getValue().message().meta()).isEqualTo(meta);
    }

    @Test
    void anAnswerWithoutAnyRunIsStoredWithoutMeta() {
        RoomMessageRepository messages = mock(RoomMessageRepository.class);
        when(messages.insert(any(), eq(roomId), eq(SenderType.AI), eq(null), eq("답변"))).thenReturn(
                new Message(UUID.randomUUID(), SenderType.AI, null, null, "답변", Instant.EPOCH, null));
        var realWriter = new ChatMessageWriter(access, messages, mock(ApplicationEventPublisher.class));

        assertThat(realWriter.recordAi(roomId, "답변").meta()).isNull();
        verify(messages, never()).insert(any(), any(), any(), any(), any(), any());
    }
}
