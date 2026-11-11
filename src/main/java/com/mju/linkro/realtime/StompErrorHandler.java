package com.mju.linkro.realtime;

import com.mju.linkro.common.exception.BusinessException;
import com.mju.linkro.common.exception.ErrorCode;
import java.nio.charset.StandardCharsets;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.util.MimeType;
import org.springframework.web.socket.messaging.StompSubProtocolErrorHandler;

/**
 * ERROR frame for a refused CONNECT, SUBSCRIBE or SEND: the {@code message} header is the error code of the REST API
 * (TOKEN_EXPIRED, NOT_ROOM_MEMBER, ...) and the body is its Korean text. Anything unexpected is INTERNAL_ERROR,
 * logged here and never shown.
 */
@Slf4j
class StompErrorHandler extends StompSubProtocolErrorHandler {
    @Override
    public Message<byte[]> handleClientMessageProcessingError(Message<byte[]> clientMessage, Throwable failure) {
        ErrorCode code = errorCode(failure);
        if (code == ErrorCode.INTERNAL_ERROR) log.error("STOMP message failed", failure);
        StompHeaderAccessor error = StompHeaderAccessor.create(StompCommand.ERROR);
        error.setMessage(code.getCode());
        error.setContentType(new MimeType("text", "plain", StandardCharsets.UTF_8));
        error.setLeaveMutable(true);
        StompHeaderAccessor client = clientMessage == null ? null : StompHeaderAccessor.wrap(clientMessage);
        return handleInternal(error, code.getMessage().getBytes(StandardCharsets.UTF_8), failure, client);
    }

    private static ErrorCode errorCode(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof BusinessException business) return business.getErrorCode();
        }
        return ErrorCode.INTERNAL_ERROR;
    }
}
