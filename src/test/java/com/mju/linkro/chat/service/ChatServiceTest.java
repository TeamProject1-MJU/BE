package com.mju.linkro.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.mju.linkro.ai.api.AiClient;
import com.mju.linkro.ai.api.ChatReplyRequest;
import com.mju.linkro.ai.api.GeneratedText;
import com.mju.linkro.chat.ChatProperties;
import com.mju.linkro.chat.domain.SenderType;
import com.mju.linkro.chat.dto.ChatMessageRequest;
import com.mju.linkro.chat.dto.ChatResponses.Message;
import com.mju.linkro.chat.dto.ChatResponses.Page;
import com.mju.linkro.chat.dto.ChatResponses.Sent;
import com.mju.linkro.chat.event.MessageCreatedEvent;
import com.mju.linkro.chat.repository.RoomMessageRepository;
import com.mju.linkro.common.exception.BusinessException;
import com.mju.linkro.common.exception.ErrorCode;
import com.mju.linkro.recommendation.repository.RecommendationRepository;
import com.mju.linkro.recommendation.repository.RecommendationRepository.ResultRow;
import com.mju.linkro.recommendation.repository.RecommendationRepository.RunRow;
import com.mju.linkro.room.domain.MeetingRoom;
import com.mju.linkro.room.domain.RoomRole;
import com.mju.linkro.room.dto.RoomResponses.Station;
import com.mju.linkro.room.repository.MeetingRoomRepository;
import com.mju.linkro.room.repository.RoomReadRepository;
import com.mju.linkro.room.repository.RoomReadRepository.MemberRow;
import com.mju.linkro.room.service.RoomAccess;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.context.ApplicationEventPublisher;
import tools.jackson.databind.json.JsonMapper;

/** The send/AI flow with a real service graph over mocked storage and a scripted AI. */
class ChatServiceTest {
    UUID room = UUID.randomUUID();
    UUID user = UUID.randomUUID();
    RoomAccess access = mock(RoomAccess.class);
    RoomMessageRepository messages = mock(RoomMessageRepository.class);
    MeetingRoomRepository rooms = mock(MeetingRoomRepository.class);
    RoomReadRepository roomReads = mock(RoomReadRepository.class);
    RecommendationRepository recommendations = mock(RecommendationRepository.class);
    AiClient ai = mock(AiClient.class);
    ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    ChatProperties properties = new ChatProperties();
    List<Runnable> queued = new ArrayList<>();
    Executor executor = queued::add;
    ChatService service;

    @BeforeEach
    void setUp() {
        when(messages.insert(any(), eq(room), any(), any(), any())).thenAnswer(call -> new Message(call.getArgument(0),
                call.getArgument(2), call.getArgument(3), call.getArgument(2) == SenderType.USER ? "민지" : null,
                call.getArgument(4), Instant.parse("2026-11-11T02:00:00Z"), null));
        service = build(executor);
    }

    ChatService build(Executor aiExecutor) {
        var writer = new ChatMessageWriter(access, messages, events);
        var context = new ChatRoomContext(properties, messages, rooms, roomReads, recommendations);
        return new ChatService(writer, access, messages, new ChatAiResponder(ai, context, writer), aiExecutor);
    }

    ChatMessageRequest body(String content) {
        return new ChatMessageRequest(JsonMapper.builder().build().getNodeFactory().stringNode(content));
    }

    void runQueued() {
        queued.forEach(Runnable::run);
        queued.clear();
    }

    List<Message> savedMessages() {
        var captor = org.mockito.ArgumentCaptor.forClass(MessageCreatedEvent.class);
        verify(events, org.mockito.Mockito.atLeast(0)).publishEvent(captor.capture());
        return captor.getAllValues().stream().map(MessageCreatedEvent::message).toList();
    }

    @Test
    void aPlainMessageIsSavedAndNeverCallsTheAi() {
        Sent sent = service.send(room, user, body("내일 몇 시에 볼까?"));

        assertThat(sent.aiPending()).isFalse();
        assertThat(sent.message().senderType()).isEqualTo(SenderType.USER);
        assertThat(sent.message().senderId()).isEqualTo(user);
        assertThat(sent.message().content()).isEqualTo("내일 몇 시에 볼까?");
        assertThat(sent.message().meta()).isNull();
        assertThat(queued).isEmpty();
        verifyNoInteractions(ai);
        assertThat(savedMessages()).containsExactly(sent.message());
    }

    @Test
    void aNonMemberSavesNothingAndCallsNothing() {
        when(access.requireMember(room, user)).thenThrow(new BusinessException(ErrorCode.NOT_ROOM_MEMBER));

        assertThatThrownBy(() -> service.send(room, user, body("@linkro 안녕")))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.NOT_ROOM_MEMBER));

        verify(messages, never()).insert(any(), any(), any(), any(), any());
        assertThat(queued).isEmpty();
        verifyNoInteractions(ai, events);
    }

    @Test
    void membershipIsCheckedBeforeTheContent() {
        when(access.requireMember(room, user)).thenThrow(new BusinessException(ErrorCode.NOT_ROOM_MEMBER));

        assertThatThrownBy(() -> service.send(room, user, body("")))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.NOT_ROOM_MEMBER));
    }

    @Test
    void anInvalidMessageSavesNothing() {
        assertThatThrownBy(() -> service.send(room, user, body("   ")))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));

        verify(messages, never()).insert(any(), any(), any(), any(), any());
        verifyNoInteractions(events);
    }

    @Test
    void aMentionReturnsAtOnceAndTheAnswerIsAnAiMessageWithRoomContext() {
        UUID other = UUID.randomUUID();
        UUID earlier = UUID.randomUUID();
        var room1 = new MeetingRoom("ABC123", "금요일 저녁", user);
        room1.schedule(Instant.parse("2026-11-13T10:00:00Z"));
        when(rooms.findById(room)).thenReturn(Optional.of(room1));
        when(roomReads.members(List.of(room))).thenReturn(List.of(
                new MemberRow(room, user, "민지", RoomRole.OWNER, Instant.EPOCH, 1L),
                new MemberRow(room, other, "철수", RoomRole.MEMBER, Instant.EPOCH, null)));
        when(roomReads.stations(any())).thenReturn(Map.of(1L, new Station(1, "사당", true, List.of()), 2L, new Station(2, "강남", true, List.of())));
        UUID run = UUID.randomUUID();
        when(recommendations.latest(room)).thenReturn(Optional.of(new RunRow(run, room, user, "{}", Instant.EPOCH)));
        when(recommendations.results(run)).thenReturn(List.of(new ResultRow(UUID.randomUUID(), run, "TIME", 2L,
                BigDecimal.TEN, "[]", "[]", "r", "AI")));
        when(messages.conversationBefore(eq(room), any(), anyInt())).thenReturn(List.of(
                new Message(earlier, SenderType.USER, other, "철수", "어디서 볼까?", Instant.EPOCH, null),
                new Message(UUID.randomUUID(), SenderType.AI, null, null, "사당은 어때요?", Instant.EPOCH, null)));
        when(ai.replyToChat(any())).thenReturn(new GeneratedText(" 사당역 근처를 추천해요. "));

        Sent sent = service.send(room, user, body("@linkro 사당역에서 갈만한 곳 알려줘"));

        // Answered before the AI ran: only the question exists so far.
        assertThat(sent.aiPending()).isTrue();
        assertThat(savedMessages()).extracting(Message::senderType).containsExactly(SenderType.USER);
        verifyNoInteractions(ai);

        runQueued();

        var request = org.mockito.ArgumentCaptor.forClass(ChatReplyRequest.class);
        verify(ai).replyToChat(request.capture());
        assertThat(request.getValue().question()).isEqualTo("사당역에서 갈만한 곳 알려줘");
        assertThat(request.getValue().recentMessages()).extracting("nickname", "content")
                .containsExactly(org.assertj.core.groups.Tuple.tuple("철수", "어디서 볼까?"), org.assertj.core.groups.Tuple.tuple("linkro", "사당은 어때요?"));
        assertThat(request.getValue().roomContext()).contains("금요일 저녁", "2026-11-13 19:00", "민지(출발역: 사당)", "철수(출발역: 미설정)", "시간 기준 강남");
        verify(messages).conversationBefore(eq(room), eq(sent.message().id()), eq(10));

        List<Message> all = savedMessages();
        assertThat(all).extracting(Message::senderType).containsExactly(SenderType.USER, SenderType.AI);
        assertThat(all.get(1).content()).isEqualTo("사당역 근처를 추천해요.");
        assertThat(all.get(1).senderId()).isNull();
    }

    @Test
    void aFailedAiLeavesTheUserMessageAndAddsAnUnavailableNotice() {
        when(ai.replyToChat(any())).thenThrow(new BusinessException(ErrorCode.AI_UNAVAILABLE));

        service.send(room, user, body("@linkro 안녕"));
        runQueued();

        List<Message> all = savedMessages();
        assertThat(all).extracting(Message::senderType).containsExactly(SenderType.USER, SenderType.SYSTEM);
        assertThat(all.get(0).content()).isEqualTo("@linkro 안녕");
        assertThat(all.get(1).content()).isEqualTo(ChatAiResponder.UNAVAILABLE_NOTICE);
        assertThat(all.get(1).senderId()).isNull();
    }

    @Test
    void aQuotaFailureSaysSo() {
        when(ai.replyToChat(any())).thenThrow(new BusinessException(ErrorCode.AI_QUOTA_EXCEEDED));

        service.send(room, user, body("@linkro 안녕"));
        runQueued();

        assertThat(savedMessages()).extracting(Message::content).containsExactly("@linkro 안녕", ChatAiResponder.QUOTA_NOTICE);
    }

    @Test
    void anUnexpectedAiErrorOrAnEmptyAnswerIsTheUnavailableNotice() {
        when(ai.replyToChat(any())).thenThrow(new IllegalStateException("boom")).thenReturn(new GeneratedText("   "));

        service.send(room, user, body("@linkro 하나"));
        service.send(room, user, body("@linkro 둘"));
        runQueued();

        assertThat(savedMessages()).extracting(Message::content).containsExactly("@linkro 하나", "@linkro 둘",
                ChatAiResponder.UNAVAILABLE_NOTICE, ChatAiResponder.UNAVAILABLE_NOTICE);
    }

    @Test
    void aFullAiQueueStillSavesTheMessageAndTellsTheRoom() {
        Executor full = task -> { throw new RejectedExecutionException("full"); };

        Sent sent = build(full).send(room, user, body("@linkro 안녕"));

        assertThat(sent.message().content()).isEqualTo("@linkro 안녕");
        assertThat(savedMessages()).extracting(Message::content).containsExactly("@linkro 안녕", ChatAiResponder.UNAVAILABLE_NOTICE);
        verifyNoInteractions(ai);
    }

    @Test
    void theRoomDisappearingDuringTheAiCallLosesTheAnswerQuietly() {
        when(ai.replyToChat(any())).thenReturn(new GeneratedText("답변"));
        when(messages.insert(any(), eq(room), eq(SenderType.AI), any(), any()))
                .thenThrow(new org.springframework.dao.DataIntegrityViolationException("room gone"));
        when(messages.insert(any(), eq(room), eq(SenderType.SYSTEM), any(), any()))
                .thenThrow(new org.springframework.dao.DataIntegrityViolationException("room gone"));

        service.send(room, user, body("@linkro 안녕"));
        runQueued();

        assertThat(savedMessages()).extracting(Message::senderType).containsExactly(SenderType.USER);
    }

    @Test
    void listReturnsNewestFirstWithACursorOnlyWhenThereIsMore() {
        var newest = new Message(UUID.randomUUID(), SenderType.USER, user, "민지", "c", Instant.EPOCH, null);
        var middle = new Message(UUID.randomUUID(), SenderType.AI, null, null, "b", Instant.EPOCH, null);
        var oldest = new Message(UUID.randomUUID(), SenderType.SYSTEM, null, null, "a", Instant.EPOCH, null);
        when(messages.page(room, null, 3)).thenReturn(List.of(newest, middle, oldest));
        when(messages.page(room, middle.id(), 3)).thenReturn(List.of(oldest));

        Page first = service.list(room, user, null, "2");
        assertThat(first.items()).containsExactly(newest, middle);
        assertThat(first.hasMore()).isTrue();
        assertThat(first.nextCursor()).isEqualTo(middle.id());

        Page last = service.list(room, user, first.nextCursor().toString(), "2");
        assertThat(last.items()).containsExactly(oldest);
        assertThat(last.hasMore()).isFalse();
        assertThat(last.nextCursor()).isNull();
        InOrder order = inOrder(access, messages);
        order.verify(access).requireMember(room, user);
        order.verify(messages).page(room, null, 3);
    }

    @Test
    void listDefaultsTo30AndRejectsBadParameters() {
        when(messages.page(eq(room), any(), anyInt())).thenReturn(List.of());

        assertThat(service.list(room, user, null, null).items()).isEmpty();
        verify(messages).page(room, null, 31);
        for (String size : List.of("0", "101", "-1", "abc", "1.5")) {
            assertThatThrownBy(() -> service.list(room, user, null, size)).isInstanceOfSatisfying(BusinessException.class,
                    e -> assertThat(e.getDetails()).containsOnlyKeys("size"));
        }
        assertThatThrownBy(() -> service.list(room, user, "not-a-uuid", null)).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getDetails()).containsOnlyKeys("cursor"));
    }

    @Test
    void listChecksMembershipFirst() {
        when(access.requireMember(room, user)).thenThrow(new BusinessException(ErrorCode.NOT_ROOM_MEMBER));

        assertThatThrownBy(() -> service.list(room, user, null, null)).isInstanceOf(BusinessException.class);
        verify(messages, never()).page(any(), any(), anyInt());
    }
}
