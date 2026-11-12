package com.mju.linkro.chat.service;

import com.mju.linkro.chat.ChatProperties;
import com.mju.linkro.chat.repository.RoomMessageRepository;
import com.mju.linkro.chat.repository.RoomMessageRepository.Candidate;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * At startup, answers the @링크로 questions a restart or shutdown cut off with the standard failure notice, so the
 * asker is not left waiting (the answer queue is only in memory).
 *
 * <p><b>Which questions count as unanswered.</b> No column links an answer to its question, so it is counted per
 * room: take the USER messages with the mention in the window (see {@code linkro.chat.answer-sweep-window}), n of
 * them. Count the replies after the oldest of them: AI messages and the two failure notices of
 * {@link ChatAiResponder}, m of them. If n is more than m, n − m questions have no reply and the room gets n − m
 * notices (the notice names no question, so it does not matter which). Because the notices count as replies, a
 * second run finds m = n and adds nothing: restarting again is safe. Only questions saved before this server
 * started count, so a question asked while the sweep runs, whose answer is on its way, is never answered twice.
 * Limits: a question older than the window is left alone, and an AI answer that arrives for a different question
 * than the one it counts for makes no difference to the number of notices. Written for one server instance.
 */
@Slf4j
@Component
public class ChatAnswerSweep {
    private final ChatProperties properties;
    private final RoomMessageRepository messages;
    private final ChatMessageWriter writer;
    private final Instant startedAt = Instant.now();

    ChatAnswerSweep(ChatProperties properties, RoomMessageRepository messages, ChatMessageWriter writer) {
        this.properties = properties;
        this.messages = messages;
        this.writer = writer;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        try {
            int notices = sweep(Instant.now(), startedAt);
            if (notices > 0) log.info("Startup sweep: {} unanswered @링크로 question(s) got the failure notice", notices);
        } catch (RuntimeException failure) {
            log.warn("Startup sweep of unanswered @링크로 questions failed", failure);
        }
    }

    /** Questions saved in {@code [now - window, before)}. Returns how many notices were saved. */
    public int sweep(Instant now, Instant before) {
        Duration window = properties.getAnswerSweepWindow();
        if (window == null || window.isZero() || window.isNegative()) return 0;
        Map<UUID, List<UUID>> byRoom = new LinkedHashMap<>();
        for (Candidate candidate : messages.userMessagesContaining("@링크로", now.minus(window), before)) {
            if (LinkroMention.in(candidate.content())) {
                byRoom.computeIfAbsent(candidate.roomId(), room -> new java.util.ArrayList<>()).add(candidate.id());
            }
        }
        int saved = 0;
        for (Map.Entry<UUID, List<UUID>> room : byRoom.entrySet()) {
            try {
                int questions = room.getValue().size();
                int replies = messages.repliesAfter(room.getKey(), room.getValue().get(0),
                        List.of(ChatAiResponder.QUOTA_NOTICE, ChatAiResponder.UNAVAILABLE_NOTICE));
                for (int i = replies; i < questions; i++) {
                    writer.recordSystem(room.getKey(), ChatAiResponder.UNAVAILABLE_NOTICE);
                    saved++;
                }
            } catch (RuntimeException failure) {
                // Typically the room was deleted in between.
                log.warn("Startup sweep skipped room {}: {}", room.getKey(), failure.toString());
            }
        }
        return saved;
    }
}
