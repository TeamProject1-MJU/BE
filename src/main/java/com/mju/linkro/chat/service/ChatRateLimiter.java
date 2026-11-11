package com.mju.linkro.chat.service;

import com.mju.linkro.chat.ChatProperties;
import com.mju.linkro.common.exception.BusinessException;
import com.mju.linkro.common.exception.ErrorCode;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * One @링크로 call per user per {@code linkro.chat.ai-call-interval}, all rooms together. In memory: the server is a
 * single instance, and a restart only forgets the last few seconds. A refused call does not start a new interval.
 */
@Component
public class ChatRateLimiter {
    /** Past this many users, finished intervals are cleaned out on the next call. */
    private static final int CLEANUP_SIZE = 10_000;

    private final ChatProperties properties;
    private final LongSupplier nanos;
    private final Map<UUID, Long> lastCall = new ConcurrentHashMap<>();

    @Autowired
    public ChatRateLimiter(ChatProperties properties) {
        this(properties, System::nanoTime);
    }

    ChatRateLimiter(ChatProperties properties, LongSupplier nanos) {
        this.properties = properties;
        this.nanos = nanos;
    }

    /** Takes the user's turn or throws AI_QUOTA_EXCEEDED (429) with {@code details.retryAfterSeconds}. */
    public void claim(UUID userId) {
        long interval = properties.getAiCallInterval().toNanos();
        if (interval <= 0) return;
        long now = nanos.getAsLong();
        if (lastCall.size() > CLEANUP_SIZE) lastCall.values().removeIf(last -> now - last >= interval);
        long[] waitNanos = {0};
        lastCall.compute(userId, (id, last) -> {
            if (last != null && now - last < interval) {
                waitNanos[0] = interval - (now - last);
                return last;
            }
            return now;
        });
        if (waitNanos[0] > 0) {
            long seconds = (waitNanos[0] + 999_999_999L) / 1_000_000_000L;
            throw new BusinessException(ErrorCode.AI_QUOTA_EXCEEDED, Map.of("retryAfterSeconds", seconds));
        }
    }

    /** Gives the turn back when the message was not saved after all. */
    public void release(UUID userId) {
        lastCall.remove(userId);
    }
}
