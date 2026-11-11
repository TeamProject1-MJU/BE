package com.mju.linkro.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.mju.linkro.chat.ChatProperties;
import com.mju.linkro.chat.repository.RoomMessageRepository;
import com.mju.linkro.chat.repository.RoomMessageRepository.Candidate;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The counting rule with a scripted repository; ChatPostgresTest runs the real queries. */
class ChatAnswerSweepTest {
    Instant now = Instant.parse("2026-11-11T12:00:00Z");
    Instant started = Instant.parse("2026-11-11T11:59:00Z");
    ChatProperties properties = new ChatProperties();
    RoomMessageRepository messages = mock(RoomMessageRepository.class);
    ChatMessageWriter writer = mock(ChatMessageWriter.class);
    ChatAnswerSweep sweep = new ChatAnswerSweep(properties, messages, writer);
    UUID roomA = UUID.randomUUID();
    UUID roomB = UUID.randomUUID();

    Candidate question(UUID room, String content) {
        return new Candidate(UUID.randomUUID(), room, content);
    }

    @Test
    void theWindowIsTenMinutesByDefaultAndIsAppliedToTheQuery() {
        when(messages.userMessagesContaining(any(), any(), any())).thenReturn(List.of());

        sweep.sweep(now, started);

        assertThat(properties.getAnswerSweepWindow()).isEqualTo(Duration.ofMinutes(10));
        verify(messages).userMessagesContaining("@링크로", Instant.parse("2026-11-11T11:50:00Z"), started);
    }

    @Test
    void anUnansweredQuestionGetsTheFailureNotice() {
        Candidate q = question(roomA, "@링크로 안녕");
        when(messages.userMessagesContaining(any(), any(), any())).thenReturn(List.of(q));
        when(messages.repliesAfter(eq(roomA), eq(q.id()), anyList())).thenReturn(0);

        assertThat(sweep.sweep(now, started)).isEqualTo(1);

        verify(writer).recordSystem(roomA, "AI가 답변을 만들지 못했어요. 잠시 후 다시 시도해 주세요.");
    }

    @Test
    void aQuestionWithAReplyIsLeftAlone() {
        Candidate q = question(roomA, "@링크로 안녕");
        when(messages.userMessagesContaining(any(), any(), any())).thenReturn(List.of(q));
        when(messages.repliesAfter(eq(roomA), eq(q.id()), anyList())).thenReturn(1);

        assertThat(sweep.sweep(now, started)).isZero();

        verifyNoInteractions(writer);
    }

    @Test
    void severalQuestionsInOneRoomAreCountedAgainstTheRepliesAfterTheFirst() {
        Candidate first = question(roomA, "@링크로 하나");
        Candidate second = question(roomA, "@링크로 둘");
        Candidate third = question(roomA, "@링크로 셋");
        when(messages.userMessagesContaining(any(), any(), any())).thenReturn(List.of(first, second, third));
        when(messages.repliesAfter(eq(roomA), eq(first.id()), anyList())).thenReturn(1);

        assertThat(sweep.sweep(now, started)).isEqualTo(2);

        verify(writer, times(2)).recordSystem(eq(roomA), any());
    }

    @Test
    void roomsAreCountedSeparately() {
        Candidate a = question(roomA, "@링크로 A");
        Candidate b = question(roomB, "@링크로 B");
        when(messages.userMessagesContaining(any(), any(), any())).thenReturn(List.of(a, b));
        when(messages.repliesAfter(eq(roomA), eq(a.id()), anyList())).thenReturn(1);
        when(messages.repliesAfter(eq(roomB), eq(b.id()), anyList())).thenReturn(0);

        sweep.sweep(now, started);

        verify(writer, never()).recordSystem(eq(roomA), any());
        verify(writer).recordSystem(eq(roomB), any());
    }

    @Test
    void textThatOnlyLooksLikeTheMentionIsNotAQuestion() {
        when(messages.userMessagesContaining(any(), any(), any())).thenReturn(List.of(question(roomA, "me@링크로 메일")));

        assertThat(sweep.sweep(now, started)).isZero();

        verify(messages, never()).repliesAfter(any(), any(), anyList());
        verifyNoInteractions(writer);
    }

    @Test
    void theNoticesAndAiMessagesBothCountAsRepliesSoASecondRunAddsNothing() {
        Candidate q = question(roomA, "@링크로 안녕");
        when(messages.userMessagesContaining(any(), any(), any())).thenReturn(List.of(q));
        when(messages.repliesAfter(eq(roomA), eq(q.id()), anyList())).thenReturn(0).thenReturn(1);

        assertThat(sweep.sweep(now, started)).isEqualTo(1);
        assertThat(sweep.sweep(now, started)).isZero();

        var notices = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(messages, times(2)).repliesAfter(eq(roomA), eq(q.id()), notices.capture());
        assertThat(notices.getValue()).containsExactlyInAnyOrder(ChatAiResponder.QUOTA_NOTICE, ChatAiResponder.UNAVAILABLE_NOTICE);
    }

    @Test
    void zeroTurnsTheSweepOff() {
        properties.setAnswerSweepWindow(Duration.ZERO);

        assertThat(sweep.sweep(now, started)).isZero();

        verifyNoInteractions(messages, writer);
    }

    @Test
    void aRoomThatFailsDoesNotStopTheOthersOrStartup() {
        Candidate a = question(roomA, "@링크로 A");
        Candidate b = question(roomB, "@링크로 B");
        when(messages.userMessagesContaining(any(), any(), any())).thenReturn(List.of(a, b));
        when(messages.repliesAfter(any(), any(), anyList())).thenReturn(0);
        when(writer.recordSystem(eq(roomA), any())).thenThrow(new IllegalStateException("room gone"));

        assertThat(sweep.sweep(now, started)).isEqualTo(1);
        verify(writer).recordSystem(eq(roomB), any());

        when(messages.userMessagesContaining(any(), any(), any())).thenThrow(new IllegalStateException("db down"));
        sweep.onReady();
    }
}
