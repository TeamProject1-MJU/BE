package com.mju.linkro.chat.event;

import com.mju.linkro.chat.dto.ChatResponses.Message;
import java.util.UUID;

/**
 * Published (Spring {@code ApplicationEventPublisher}) after a room message is committed: USER, AI and SYSTEM alike.
 * This is the hook for the WebSocket message {@code MESSAGE_CREATED} (API chapter 11, payload = {@code message});
 * the WebSocket module (#42, #43) listens to it, this module does not know about STOMP. When the message is saved
 * inside a larger transaction (a SYSTEM message of a room change) the event is published after that outer commit.
 */
public record MessageCreatedEvent(UUID roomId, Message message) {}
