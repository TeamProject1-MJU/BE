package com.mju.linkro.realtime;

import com.mju.linkro.auth.jwt.JwtProvider;
import com.mju.linkro.common.exception.BusinessException;
import com.mju.linkro.common.exception.ErrorCode;
import com.mju.linkro.room.service.RoomAccess;
import com.mju.linkro.user.domain.User;
import com.mju.linkro.user.repository.UserRepository;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.messaging.simp.broker.SimpleBrokerMessageHandler;
import org.springframework.messaging.simp.broker.SubscriptionRegistry;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.spy;

/** Real WebSocket/STOMP frames through the production config, auth, broker and delivery filter. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.config.import=", "spring.jpa.show-sql=false"})
@ActiveProfiles("test")
@Timeout(30)
class StompReceiptIntegrationTest {
    @Value("${local.server.port}") int port;
    @Autowired JwtProvider jwt;
    @Autowired UserRepository users;
    @Autowired RoomEvents events;
    @Autowired SimpleBrokerMessageHandler broker;
    // Membership decisions are isolated from DB setup; the production auth interceptor still performs the check.
    @MockitoBean RoomAccess access;
    UUID user;
    UUID room;

    @BeforeEach void setup() {
        user = users.saveAndFlush(new User(UUID.randomUUID().toString(), "receipt-test")).getId();
        room = UUID.randomUUID();
    }

    @Test void receiptMatchesRequestAndIsSentExactlyOnce() throws Exception {
        try (Client client = connect()) {
            client.subscribe(room, "ready-1");
            assertThat(client.next()).startsWith("RECEIPT\n").contains("receipt-id:ready-1\n");
            // Every event is published immediately after observing RECEIPT, without a readiness sleep.
            for (int i = 0; i < 100; i++) events.publish(room, RoomEventType.ROOM_UPDATED, Map.of("seq", i));
            for (int i = 0; i < 100; i++) {
                assertThat(client.next()).startsWith("MESSAGE\n").contains("\"seq\":" + i + "}");
            }
            assertThat(client.frames.poll(300, TimeUnit.MILLISECONDS)).isNull();
        }
    }

    @Test void subscriptionWithoutReceiptStillDeliversAndDoesNotAcknowledge() throws Exception {
        try (Client client = connect()) {
            client.subscribe(room, null);
            // Observe actual broker registration rather than guessing readiness from elapsed time.
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (subscriptions() == 0 && System.nanoTime() < deadline) Thread.sleep(10);
            assertThat(subscriptions()).isEqualTo(1);
            events.publish(room, RoomEventType.ROOM_UPDATED, Map.of("legacy", true));
            assertThat(client.next()).startsWith("MESSAGE\n").contains("\"legacy\":true");
            assertThat(client.frames.poll(300, TimeUnit.MILLISECONDS)).isNull();
        }
    }

    @Test void unauthorizedSubscriptionIsRejectedWithoutReceipt() throws Exception {
        doThrow(new BusinessException(ErrorCode.NOT_ROOM_MEMBER)).when(access).requireMember(room, user);
        try (Client client = connect()) {
            client.subscribe(room, "denied-1");
            assertThat(client.next()).startsWith("ERROR\n").contains("message:NOT_ROOM_MEMBER\n");
            assertThat(client.closed.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(client.frames.poll(300, TimeUnit.MILLISECONDS)).isNull();
            assertThat(subscriptions()).isZero();
        }
    }

    @Test void receiptWaitsForActualRegistrationEvenWhenBrokerIsSlow() throws Exception {
        SubscriptionRegistry original = broker.getSubscriptionRegistry();
        SubscriptionRegistry delayed = spy(original);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        doAnswer(call -> {
            entered.countDown();
            if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("registration not released");
            return call.callRealMethod();
        }).when(delayed).registerSubscription(any());
        broker.setSubscriptionRegistry(delayed);
        try (Client client = connect()) {
            client.subscribe(room, "slow-1");
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(client.frames.poll(650, TimeUnit.MILLISECONDS)).isNull();
            assertThat(subscriptions()).isZero();
            release.countDown();
            assertThat(client.next()).startsWith("RECEIPT\n").contains("receipt-id:slow-1\n");
            assertThat(subscriptions()).isEqualTo(1);
            events.publish(room, RoomEventType.ROOM_UPDATED, Map.of("immediate", true));
            assertThat(client.next()).startsWith("MESSAGE\n").contains("\"immediate\":true");
            assertThat(client.frames.poll(300, TimeUnit.MILLISECONDS)).isNull();
        } finally {
            release.countDown();
            broker.setSubscriptionRegistry(original);
        }
    }

    private int subscriptions() {
        var headers = org.springframework.messaging.simp.SimpMessageHeaderAccessor.create(
                org.springframework.messaging.simp.SimpMessageType.MESSAGE);
        headers.setDestination(RoomEvents.TOPIC + room);
        return broker.getSubscriptionRegistry().findSubscriptions(org.springframework.messaging.support.MessageBuilder
                .createMessage(new byte[0], headers.getMessageHeaders())).values().stream().mapToInt(java.util.List::size).sum();
    }

    private Client connect() throws Exception {
        Client client = new Client();
        client.socket = HttpClient.newHttpClient().newWebSocketBuilder()
                .buildAsync(URI.create("ws://localhost:" + port + "/ws"), client).get(5, TimeUnit.SECONDS);
        client.send("CONNECT\naccept-version:1.2\nheart-beat:0,0\nAuthorization:Bearer "
                + jwt.createAccessToken(user) + "\n\n");
        assertThat(client.next()).startsWith("CONNECTED\n");
        return client;
    }

    private static class Client implements WebSocket.Listener, AutoCloseable {
        final BlockingQueue<String> frames = new LinkedBlockingQueue<>();
        final CountDownLatch closed = new CountDownLatch(1);
        final StringBuilder pending = new StringBuilder();
        WebSocket socket;

        @Override public java.util.concurrent.CompletionStage<?> onText(WebSocket socket, CharSequence data, boolean last) {
            pending.append(data);
            int end;
            while ((end = pending.indexOf("\0")) >= 0) {
                String frame = pending.substring(0, end).stripLeading();
                pending.delete(0, end + 1);
                if (!frame.isEmpty()) frames.add(frame);
            }
            socket.request(1);
            return null;
        }

        @Override public java.util.concurrent.CompletionStage<?> onClose(WebSocket socket, int status, String reason) {
            closed.countDown();
            return null;
        }

        void send(String frame) { socket.sendText(frame + "\0", true).join(); }
        void subscribe(UUID room, String receipt) {
            send("SUBSCRIBE\nid:sub-0\ndestination:" + RoomEvents.TOPIC + room + "\n"
                    + (receipt == null ? "" : "receipt:" + receipt + "\n") + "\n");
        }
        String next() throws Exception {
            String frame = frames.poll(5, TimeUnit.SECONDS);
            assertThat(frame).as("server STOMP frame within 5s").isNotNull();
            return frame;
        }
        @Override public void close() { socket.abort(); }
    }
}
