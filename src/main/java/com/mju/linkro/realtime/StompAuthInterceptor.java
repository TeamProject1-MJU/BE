package com.mju.linkro.realtime;

import com.mju.linkro.auth.jwt.JwtProvider;
import com.mju.linkro.auth.web.JwtAuthenticationFilter;
import com.mju.linkro.common.exception.BusinessException;
import com.mju.linkro.common.exception.ErrorCode;
import com.mju.linkro.room.service.RoomAccess;
import com.mju.linkro.user.repository.UserRepository;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.stereotype.Component;

/**
 * Inbound channel (API chapter 11): CONNECT checks the access token once, SUBSCRIBE checks the current room
 * membership, and every client SEND is refused (STOMP only receives; messages go through REST). A failure throws a
 * BusinessException that {@link StompErrorHandler} turns into an ERROR frame with the error code.
 */
@Component
@RequiredArgsConstructor
class StompAuthInterceptor implements ChannelInterceptor {
    private final JwtProvider jwt;
    private final UserRepository users;
    private final RoomAccess access;
    private final StompSessions sessions;

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || accessor.getCommand() == null) return message;
        StompCommand command = accessor.getCommand();
        if (StompCommand.CONNECT.equals(command) || StompCommand.STOMP.equals(command)) {
            connect(accessor);
        } else if (StompCommand.SUBSCRIBE.equals(command)) {
            subscribe(accessor);
        } else if (StompCommand.UNSUBSCRIBE.equals(command)) {
            sessions.unsubscribed(accessor.getSessionId(), accessor.getSubscriptionId());
        } else if (StompCommand.SEND.equals(command)) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED);
        }
        return message;
    }

    /** The token is checked here only; a connection that stays open is not cut when the token later expires. */
    private void connect(StompHeaderAccessor accessor) {
        UUID userId = jwt.parseUserId(JwtAuthenticationFilter.bearerToken(accessor.getFirstNativeHeader("Authorization")));
        if (users.findById(userId).filter(user -> user.getDeletedAt() == null).isEmpty()) {
            throw new BusinessException(ErrorCode.TOKEN_INVALID);
        }
        if (!sessions.authenticated(accessor.getSessionId(), userId)) throw new BusinessException(ErrorCode.AUTH_REQUIRED);
        accessor.setUser(new UsernamePasswordAuthenticationToken(userId, null, List.of()));
    }

    /** The subscription is recorded before the membership check so a concurrent leave cannot slip past it. */
    private void subscribe(StompHeaderAccessor accessor) {
        String sessionId = accessor.getSessionId();
        UUID userId = sessions.userOf(sessionId);
        if (userId == null) throw new BusinessException(ErrorCode.AUTH_REQUIRED);
        UUID roomId = roomId(accessor.getDestination());
        String subscriptionId = accessor.getSubscriptionId();
        if (roomId == null || subscriptionId == null) throw new BusinessException(ErrorCode.VALIDATION_ERROR);
        sessions.subscribed(sessionId, subscriptionId, roomId);
        try {
            access.requireMember(roomId, userId);
        } catch (RuntimeException failure) {
            sessions.unsubscribed(sessionId, subscriptionId);
            throw failure;
        }
    }

    /** {@code /topic/rooms/{roomId}} with a canonical UUID, or null. */
    private static UUID roomId(String destination) {
        if (destination == null || !destination.startsWith(RoomEvents.TOPIC)) return null;
        String id = destination.substring(RoomEvents.TOPIC.length());
        try {
            UUID roomId = UUID.fromString(id);
            return roomId.toString().equals(id) ? roomId : null;
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }
}
