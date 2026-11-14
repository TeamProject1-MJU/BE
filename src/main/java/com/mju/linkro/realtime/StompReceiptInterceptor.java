package com.mju.linkro.realtime;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Lazy;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageHandler;
import org.springframework.messaging.simp.broker.SimpleBrokerMessageHandler;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ExecutorChannelInterceptor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;

/** Acknowledge SUBSCRIBE only after the simple broker has synchronously registered it. */
@Component
class StompReceiptInterceptor implements ExecutorChannelInterceptor {
    private final MessageChannel outbound;

    StompReceiptInterceptor(@Lazy @Qualifier("clientOutboundChannel") MessageChannel outbound) {
        this.outbound = outbound;
    }

    @Override
    public void afterMessageHandled(Message<?> message, MessageChannel channel, MessageHandler handler, Exception ex) {
        if (ex != null || !(handler instanceof SimpleBrokerMessageHandler)) return;
        StompHeaderAccessor request = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (request == null || request.getCommand() != StompCommand.SUBSCRIBE || request.getReceipt() == null) return;

        StompHeaderAccessor receipt = StompHeaderAccessor.create(StompCommand.RECEIPT);
        receipt.setSessionId(request.getSessionId());
        receipt.setReceiptId(request.getReceipt());
        outbound.send(MessageBuilder.createMessage(new byte[0], receipt.getMessageHeaders()));
    }
}
