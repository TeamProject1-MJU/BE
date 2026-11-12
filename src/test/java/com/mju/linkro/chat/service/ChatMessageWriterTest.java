package com.mju.linkro.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mju.linkro.chat.domain.SenderType;
import com.mju.linkro.chat.dto.ChatResponses.Message;
import com.mju.linkro.chat.event.MessageCreatedEvent;
import com.mju.linkro.chat.repository.RoomMessageRepository;
import com.mju.linkro.common.exception.BusinessException;
import com.mju.linkro.common.exception.ErrorCode;
import com.mju.linkro.room.service.RoomAccess;
import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class ChatMessageWriterTest {
    UUID room = UUID.randomUUID();
    RoomMessageRepository messages = mock(RoomMessageRepository.class);
    ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    ChatMessageWriter writer = new ChatMessageWriter(mock(RoomAccess.class), messages, events);

    @AfterEach
    void clear() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) TransactionSynchronizationManager.clearSynchronization();
    }

    Message stored(SenderType type, String content) {
        return new Message(UUID.randomUUID(), type, null, null, content, Instant.EPOCH, null);
    }

    @Test
    void outsideATransactionTheEventGoesOutAtOnce() {
        when(messages.insert(any(), any(), any(), any(), any())).thenReturn(stored(SenderType.SYSTEM, "x"));

        Message message = writer.recordSystem(room, "x");

        verify(events).publishEvent(new MessageCreatedEvent(room, message));
    }

    @Test
    void insideATransactionTheEventWaitsForTheCommit() {
        when(messages.insert(any(), any(), any(), any(), any())).thenReturn(stored(SenderType.SYSTEM, "x"));
        TransactionSynchronizationManager.initSynchronization();

        Message message = writer.recordSystem(room, "x");

        verify(events, never()).publishEvent(any(Object.class));
        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
        verify(events).publishEvent(new MessageCreatedEvent(room, message));
    }

    @Test
    void aRolledBackTransactionPublishesNothing() {
        when(messages.insert(any(), any(), any(), any(), any())).thenReturn(stored(SenderType.SYSTEM, "x"));
        TransactionSynchronizationManager.initSynchronization();

        writer.recordSystem(room, "x");
        TransactionSynchronizationManager.getSynchronizations()
                .forEach(sync -> sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));

        verify(events, never()).publishEvent(any(Object.class));
    }

    @Test
    void aFailingListenerDoesNotFailTheSave() {
        when(messages.insert(any(), any(), any(), any(), any())).thenReturn(stored(SenderType.SYSTEM, "x"));
        doThrow(new IllegalStateException("listener")).when(events).publishEvent(any(Object.class));

        assertThat(writer.recordSystem(room, "x").content()).isEqualTo("x");
    }

    @Test
    void aDeletedRoomIsRoomNotFoundNotAServerError() {
        var violation = new SQLException("violates foreign key constraint \"fk_message_room\"", "23503");
        when(messages.insert(any(), any(), any(), any(), any())).thenThrow(new DataIntegrityViolationException("x", violation));

        assertThatThrownBy(() -> writer.recordSystem(room, "x")).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.ROOM_NOT_FOUND));
    }

    @Test
    void anOtherIntegrityFailureStaysAnError() {
        when(messages.insert(any(), any(), any(), any(), any())).thenThrow(new DataIntegrityViolationException("other"));

        assertThatThrownBy(() -> writer.recordSystem(room, "x")).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void aLongAiAnswerIsCutToTheColumnLimit() {
        when(messages.insert(any(), any(), any(), any(), any())).thenAnswer(call -> stored(call.getArgument(2), call.getArgument(4)));

        assertThat(writer.recordAi(room, "😀".repeat(4500)).content().codePointCount(0, writer.recordAi(room, "😀".repeat(4500)).content().length()))
                .isEqualTo(4000);
    }
}
