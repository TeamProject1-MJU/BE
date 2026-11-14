package com.mju.linkro.realtime;

import com.mju.linkro.common.config.CorsConfig;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;
import org.springframework.web.socket.handler.WebSocketHandlerDecorator;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

/** WebSocket/STOMP at {@code /ws} (API chapter 11): topics {@code /topic/rooms/{roomId}}, heartbeat 10 seconds. */
@Configuration(proxyBeanMethods = false)
@EnableWebSocketMessageBroker
public class RealtimeConfig implements WebSocketMessageBrokerConfigurer {
    private static final long HEARTBEAT_MS = 10_000;

    private final List<String> allowedOrigins;
    private final StompAuthInterceptor auth;
    private final StompDeliveryFilter delivery;
    private final StompSessions sessions;
    private final StompReceiptInterceptor receipts;
    private final TaskScheduler heartbeatScheduler;

    RealtimeConfig(@Value("${websocket.allowed-origins}") String allowedOrigins, StompAuthInterceptor auth,
                   StompDeliveryFilter delivery, StompSessions sessions, StompReceiptInterceptor receipts,
                   @Lazy @Qualifier("messageBrokerTaskScheduler") TaskScheduler heartbeatScheduler) {
        this.allowedOrigins = CorsConfig.parseAllowedOrigins(allowedOrigins, "WS_ALLOWED_ORIGINS (websocket.allowed-origins)");
        this.auth = auth;
        this.delivery = delivery;
        this.sessions = sessions;
        this.receipts = receipts;
        this.heartbeatScheduler = heartbeatScheduler;
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.setErrorHandler(new StompErrorHandler());
        registry.addEndpoint("/ws").setAllowedOrigins(allowedOrigins.toArray(String[]::new));
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        // Events of one commit (SYSTEM message, then the state events) reach each client in the order they were sent.
        // Without this the broker and outbound channel threads may deliver them in any order.
        registry.setPreservePublishOrder(true);
        registry.enableSimpleBroker("/topic").setHeartbeatValue(new long[] {HEARTBEAT_MS, HEARTBEAT_MS})
                .setTaskScheduler(heartbeatScheduler);
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(auth, receipts);
    }

    @Override
    public void configureClientOutboundChannel(ChannelRegistration registration) {
        registration.interceptors(delivery);
    }

    @Override
    public void configureWebSocketTransport(WebSocketTransportRegistration registration) {
        // Clients only subscribe; nothing they send needs more than a few KB.
        registration.setMessageSizeLimit(16 * 1024);
        registration.addDecoratorFactory(handler -> new WebSocketHandlerDecorator(handler) {
            @Override
            public void afterConnectionEstablished(WebSocketSession session) throws Exception {
                sessions.opened(session);
                super.afterConnectionEstablished(session);
            }

            @Override
            public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
                sessions.closed(session.getId());
                super.afterConnectionClosed(session, status);
            }
        });
    }
}
