package com.mju.linkro.realtime;

import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageHandler;
import org.springframework.messaging.simp.broker.SimpleBrokerMessageHandler;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.assertThat;

class StompReceiptInterceptorTest {
    final MessageChannel outbound = mock(MessageChannel.class);
    final SimpleBrokerMessageHandler broker = mock(SimpleBrokerMessageHandler.class);
    final StompReceiptInterceptor interceptor = new StompReceiptInterceptor(outbound);

    private Message<byte[]> request(StompCommand command, String receipt) {
        StompHeaderAccessor headers = StompHeaderAccessor.create(command);
        headers.setSessionId("session-1");
        if (receipt != null) headers.setReceipt(receipt);
        return MessageBuilder.createMessage(new byte[0], headers.getMessageHeaders());
    }

    @Test void onlySimpleBrokerCompletionSendsOneReceiptToTheRequestingSession() {
        Message<?> request = request(StompCommand.SUBSCRIBE, "ready:123");
        interceptor.afterMessageHandled(request, null, mock(MessageHandler.class), null);
        verifyNoInteractions(outbound);
        interceptor.afterMessageHandled(request, null, broker, null);
        verify(outbound, times(1)).send(any());
        verify(outbound).send(argThat(message -> {
            StompHeaderAccessor headers = StompHeaderAccessor.wrap(message);
            assertThat(headers.getCommand()).isEqualTo(StompCommand.RECEIPT);
            assertThat(headers.getReceiptId()).isEqualTo("ready:123");
            assertThat(headers.getSessionId()).isEqualTo("session-1");
            assertThat(headers.getSubscriptionId()).isNull();
            return true;
        }));
        interceptor.afterMessageHandled(request, null, mock(MessageHandler.class), null);
        verifyNoMoreInteractions(outbound);
    }

    @Test void failedBrokerHandlingDoesNotSendReceipt() {
        interceptor.afterMessageHandled(request(StompCommand.SUBSCRIBE, "failed"), null, broker,
                new IllegalStateException("registration failed"));
        verifyNoInteractions(outbound);
    }

    @Test void noReceiptHeaderAndOtherCommandsAreUntouched() {
        interceptor.afterMessageHandled(request(StompCommand.SUBSCRIBE, null), null, broker, null);
        for (StompCommand command : StompCommand.values()) {
            if (command != StompCommand.SUBSCRIBE) {
                interceptor.afterMessageHandled(request(command, "other"), null, broker, null);
            }
        }
        verifyNoInteractions(outbound);
    }
}
