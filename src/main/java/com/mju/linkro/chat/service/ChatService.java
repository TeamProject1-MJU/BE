package com.mju.linkro.chat.service;

import com.mju.linkro.chat.dto.ChatMessageRequest;
import com.mju.linkro.chat.dto.ChatResponses.Message;
import com.mju.linkro.chat.dto.ChatResponses.Page;
import com.mju.linkro.chat.dto.ChatResponses.Sent;
import com.mju.linkro.chat.repository.RoomMessageRepository;
import com.mju.linkro.common.exception.BusinessException;
import com.mju.linkro.common.exception.ErrorCode;
import com.mju.linkro.room.service.RoomAccess;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** API 10.1 (send) and 10.2 (history). Deliberately not transactional as a whole: see {@link #send}. */
@Slf4j
@Service
public class ChatService {
    static final int DEFAULT_PAGE_SIZE = 30;
    static final int MAX_PAGE_SIZE = 100;

    private final ChatMessageWriter writer;
    private final RoomAccess access;
    private final RoomMessageRepository messages;
    private final ChatAiResponder responder;
    private final ChatRateLimiter limiter;
    private final Executor aiExecutor;

    ChatService(ChatMessageWriter writer, RoomAccess access, RoomMessageRepository messages, ChatAiResponder responder,
                ChatRateLimiter limiter, @Qualifier("chatAiExecutor") Executor aiExecutor) {
        this.writer = writer;
        this.access = access;
        this.messages = messages;
        this.responder = responder;
        this.limiter = limiter;
        this.aiExecutor = aiExecutor;
    }

    /**
     * Saves the message (one short transaction, committed before this returns), then starts the AI answer in the
     * background. The response never waits for the AI, and an AI failure never touches the saved message.
     *
     * <p>A message with the mention takes the user's turn in the rate limiter after the membership and content checks
     * and before the save, so a refused call (AI_QUOTA_EXCEEDED, 429) saves nothing. If the save fails after the turn
     * was taken, the turn is given back. Messages without the mention are never limited.
     */
    public Sent send(UUID roomId, UUID userId, ChatMessageRequest request) {
        boolean[] turnTaken = {false};
        Message saved;
        try {
            saved = writer.recordUser(roomId, userId, request, content -> {
                if (LinkroMention.in(content)) {
                    limiter.claim(userId);
                    turnTaken[0] = true;
                }
            });
        } catch (RuntimeException | Error failure) {
            if (turnTaken[0]) limiter.release(userId);
            throw failure;
        }
        boolean aiPending = LinkroMention.in(saved.content());
        if (aiPending) {
            try {
                aiExecutor.execute(() -> responder.reply(roomId, saved));
            } catch (RejectedExecutionException busy) {
                log.warn("@링크로 answer queue is full or shutting down; room {}", roomId);
                responder.notifyFailure(roomId, ErrorCode.AI_UNAVAILABLE);
            }
        }
        return new Sent(saved, aiPending);
    }

    /** Newest first. The membership check and the page share one read-only transaction. */
    @Transactional(readOnly = true, propagation = Propagation.REQUIRED, isolation = Isolation.READ_COMMITTED)
    public Page list(UUID roomId, UUID userId, String cursor, String size) {
        access.requireMember(roomId, userId);
        UUID before = parseCursor(cursor);
        int limit = parseSize(size);
        List<Message> rows = messages.page(roomId, before, limit + 1);
        boolean hasMore = rows.size() > limit;
        List<Message> items = hasMore ? List.copyOf(rows.subList(0, limit)) : List.copyOf(rows);
        return new Page(items, hasMore ? items.get(items.size() - 1).id() : null, hasMore);
    }

    private static UUID parseCursor(String cursor) {
        if (cursor == null || cursor.isEmpty()) return null;
        try {
            UUID parsed = UUID.fromString(cursor);
            if (parsed.toString().equalsIgnoreCase(cursor)) return parsed;
        } catch (IllegalArgumentException ignored) { }
        throw new BusinessException(ErrorCode.VALIDATION_ERROR, Map.of("cursor", "UUID 문자열이어야 해요."));
    }

    private static int parseSize(String size) {
        if (size == null || size.isEmpty()) return DEFAULT_PAGE_SIZE;
        try {
            int parsed = Integer.parseInt(size);
            if (parsed >= 1 && parsed <= MAX_PAGE_SIZE) return parsed;
        } catch (NumberFormatException ignored) { }
        throw new BusinessException(ErrorCode.VALIDATION_ERROR, Map.of("size", "size는 1~" + MAX_PAGE_SIZE + " 사이 정수여야 해요."));
    }
}
