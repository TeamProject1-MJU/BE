package com.mju.linkro.chat.service;

import com.github.f4b6a3.uuid.UuidCreator;
import com.mju.linkro.chat.domain.SenderType;
import com.mju.linkro.chat.dto.ChatMessageRequest;
import com.mju.linkro.chat.dto.ChatResponses.Message;
import com.mju.linkro.chat.event.MessageCreatedEvent;
import com.mju.linkro.chat.repository.RoomMessageRepository;
import com.mju.linkro.common.exception.BusinessException;
import com.mju.linkro.common.exception.ErrorCode;
import com.mju.linkro.room.service.RoomAccess;
import java.sql.SQLException;
import java.util.UUID;
import java.util.function.Consumer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The one place that writes room_messages. Every message is announced by a {@link MessageCreatedEvent} after its
 * transaction commits.
 *
 * <p>{@link #recordSystem} is the entry point for the SYSTEM messages of room events (join, leave, owner change,
 * proposals, selected station; #43). Call it inside the room-changing transaction <em>after</em> taking the room lock,
 * so the message commits or rolls back with the change and the event follows the outer commit.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatMessageWriter {
    /** Matches ck_msg_content (V8); the AI reply is cut to fit it. */
    static final int DB_MAX_LENGTH = 4000;

    private final RoomAccess access;
    private final RoomMessageRepository messages;
    private final ApplicationEventPublisher events;

    /** Membership first, then the content (API 10.1). Members only; the room is not locked (docs/room-domain-34.md). */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Message recordUser(UUID roomId, UUID userId, ChatMessageRequest request) {
        return recordUser(roomId, userId, request, content -> { });
    }

    /**
     * Same, with a check that runs on the validated content after the membership and content checks and before the
     * save. It may throw to refuse the message (the rate limit does); nothing is saved then.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Message recordUser(UUID roomId, UUID userId, ChatMessageRequest request, Consumer<String> beforeSave) {
        access.requireMember(roomId, userId);
        String content = request.validatedContent();
        beforeSave.accept(content);
        return save(roomId, SenderType.USER, userId, content);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Message recordAi(UUID roomId, String content) {
        return save(roomId, SenderType.AI, null, truncate(content));
    }

    /** SYSTEM messages have no sender (ck_msg_sender). Joins the caller's transaction when there is one. */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Message recordSystem(UUID roomId, String content) {
        return save(roomId, SenderType.SYSTEM, null, truncate(content));
    }

    private Message save(UUID roomId, SenderType type, UUID senderId, String content) {
        Message saved;
        try {
            saved = messages.insert(UuidCreator.getTimeOrderedEpoch(), roomId, type, senderId, content);
        } catch (DataIntegrityViolationException failure) {
            // The room was deleted (last member left) after the membership check: it is gone, not a server error.
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                if (cause instanceof SQLException sql && "23503".equals(sql.getSQLState())
                        && sql.getMessage() != null && sql.getMessage().contains("fk_message_room")) {
                    throw new BusinessException(ErrorCode.ROOM_NOT_FOUND, failure);
                }
            }
            throw failure;
        }
        afterCommit(new MessageCreatedEvent(roomId, saved));
        return saved;
    }

    private void afterCommit(MessageCreatedEvent event) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            publish(event);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                publish(event);
            }
        });
    }

    /** A failing listener must not turn a saved message into an error response. */
    private void publish(MessageCreatedEvent event) {
        try {
            events.publishEvent(event);
        } catch (RuntimeException failure) {
            log.warn("MessageCreatedEvent listener failed for room {} message {}", event.roomId(), event.message().id(), failure);
        }
    }

    private static String truncate(String content) {
        if (content.codePointCount(0, content.length()) <= DB_MAX_LENGTH) return content;
        return content.substring(0, content.offsetByCodePoints(0, DB_MAX_LENGTH));
    }
}
