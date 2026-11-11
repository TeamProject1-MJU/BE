package com.mju.linkro.realtime;

import com.mju.linkro.auth.jwt.JwtProvider;
import com.mju.linkro.recommendation.event.RecommendationCreatedEvent;
import com.mju.linkro.room.domain.MeetingMember;
import com.mju.linkro.room.domain.RoomRole;
import com.mju.linkro.room.service.RoomCreationAttempt;
import com.mju.linkro.user.domain.User;
import com.mju.linkro.user.repository.UserRepository;
import com.mju.linkro.room.repository.MeetingMemberRepository;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import java.lang.reflect.Type;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.converter.CompositeMessageConverter;
import org.springframework.messaging.converter.JacksonJsonMessageConverter;
import org.springframework.messaging.converter.StringMessageConverter;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * STOMP on a real server and a real PostgreSQL 16 (#42, #43): CONNECT/SUBSCRIBE/SEND rules, leave and withdrawal,
 * and the room events after commit. The client is Spring's WebSocketStompClient over a real socket.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.config.import=", "spring.jpa.hibernate.ddl-auto=validate", "spring.jpa.open-in-view=false",
        "spring.jpa.show-sql=false", "auth.kakao.app-id=1234", "auth.jwt.access-token-ttl=15m",
        "auth.jwt.secret=dGVzdC1vbmx5LXNlY3JldC0wMTIzNDU2Nzg5YWJjZGVmZ2hpams=",
        "websocket.allowed-origins=http://localhost:5173"})
@Timeout(120)
class StompPostgresTest {
    static final String SECRET = "dGVzdC1vbmx5LXNlY3JldC0wMTIzNDU2Nzg5YWJjZGVmZ2hpams=";

    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Value("${local.server.port}") int port;
    @Autowired JwtProvider jwt;
    @Autowired UserRepository users;
    @Autowired MeetingMemberRepository members;
    @Autowired RoomCreationAttempt creation;
    @Autowired JdbcTemplate jdbc;
    @Autowired RoomEvents roomEvents;
    @Autowired ApplicationEventPublisher publisher;
    @Autowired PlatformTransactionManager transactions;

    final JsonMapper mapper = JsonMapper.builder().build();
    final HttpClient http = HttpClient.newHttpClient();
    final ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
    final java.util.List<Client> clients = new java.util.ArrayList<>();
    WebSocketStompClient stomp;
    UUID owner, member, outsider, room;

    @BeforeEach void setup() {
        jdbc.update("delete from meeting_rooms");
        jdbc.update("delete from refresh_tokens");
        jdbc.update("delete from users");
        owner = users.saveAndFlush(new User("owner", "방장")).getId();
        member = users.saveAndFlush(new User("member", "민지")).getId();
        outsider = users.saveAndFlush(new User("outsider", "외부인")).getId();
        room = creation.create(owner, "S00001", "room", null).id();
        members.saveAndFlush(new MeetingMember(room, member, RoomRole.MEMBER));
        scheduler.setPoolSize(1);
        scheduler.initialize();
        stomp = new WebSocketStompClient(new StandardWebSocketClient());
        // Text for ERROR frames, JSON for events.
        stomp.setMessageConverter(new CompositeMessageConverter(List.of(new StringMessageConverter(), new JacksonJsonMessageConverter())));
        stomp.setTaskScheduler(scheduler);
    }

    @AfterEach void tearDown() {
        clients.forEach(client -> { if (client.session != null && client.session.isConnected()) client.session.disconnect(); });
        stomp.stop();
        scheduler.shutdown();
    }

    // --- client helpers -------------------------------------------------------------------------------------------

    final class Client extends StompSessionHandlerAdapter {
        final BlockingQueue<StompHeaders> errors = new LinkedBlockingQueue<>();
        final BlockingQueue<JsonNode> events = new LinkedBlockingQueue<>();
        final CompletableFuture<StompHeaders> connected = new CompletableFuture<>();
        final java.util.List<JsonNode> skipped = new java.util.ArrayList<>();
        final CountDownLatch closed = new CountDownLatch(1);
        StompSession session;

        @Override public void afterConnected(StompSession session, StompHeaders headers) {
            connected.complete(headers);
        }
        @Override public void handleFrame(StompHeaders headers, Object payload) { errors.add(headers); }
        @Override public void handleTransportError(StompSession session, Throwable failure) { closed.countDown(); }
        @Override public Type getPayloadType(StompHeaders headers) { return String.class; }

        /** Subscribes and gives the server a moment to register it (the simple broker sends no receipt). */
        void subscribe(UUID roomId) throws Exception {
            session.subscribe("/topic/rooms/" + roomId, new StompFrameHandler() {
                @Override public Type getPayloadType(StompHeaders headers) { return JsonNode.class; }
                @Override public void handleFrame(StompHeaders headers, Object payload) { events.add((JsonNode) payload); }
            });
            Thread.sleep(500);
        }

        /** The next event of this type; events of other types that come first are kept for later calls. */
        JsonNode next(String type) throws Exception {
            for (int i = 0; i < 20; i++) {
                for (JsonNode event : skipped) {
                    if (type.equals(event.get("type").asString())) {
                        skipped.remove(event);
                        return event;
                    }
                }
                JsonNode event = events.poll(5, TimeUnit.SECONDS);
                assertThat(event).as("no %s event within 5s", type).isNotNull();
                if (type.equals(event.get("type").asString())) return event;
                skipped.add(event);
            }
            throw new AssertionError("no " + type + " event");
        }

        void assertNoEvent() throws Exception {
            assertThat(skipped).isEmpty();
            assertThat(events.poll(700, TimeUnit.MILLISECONDS)).isNull();
        }

        String error() throws Exception {
            StompHeaders headers = errors.poll(5, TimeUnit.SECONDS);
            assertThat(headers).as("no ERROR frame within 5s").isNotNull();
            return headers.getFirst("message");
        }
    }

    Client connect(String authorization) throws Exception {
        Client client = new Client();
        StompHeaders headers = new StompHeaders();
        if (authorization != null) headers.add("Authorization", authorization);
        open(client, new WebSocketHttpHeaders(), headers);
        return client;
    }

    /**
     * A refused CONNECT answers with an ERROR frame (kept in client.errors) and the server closes the socket,
     * so the connect future fails and client.session stays null.
     */
    void open(Client client, WebSocketHttpHeaders handshake, StompHeaders headers) throws Exception {
        try {
            client.session = stomp.connectAsync("ws://localhost:" + port + "/ws", handshake, headers, client).get(10, TimeUnit.SECONDS);
        } catch (java.util.concurrent.ExecutionException refused) {
            assertThat(refused.getCause()).isInstanceOf(org.springframework.messaging.simp.stomp.ConnectionLostException.class);
        }
        clients.add(client);
    }

    Client connectAs(UUID user) throws Exception {
        Client client = connect("Bearer " + jwt.createAccessToken(user));
        client.connected.get(5, TimeUnit.SECONDS);
        return client;
    }

    String token(java.security.Key key, UUID user, Instant expires) {
        return Jwts.builder().subject(user.toString()).issuedAt(Date.from(Instant.now().minusSeconds(3600)))
                .expiration(Date.from(expires)).signWith((javax.crypto.SecretKey) key, Jwts.SIG.HS256).compact();
    }

    HttpResponse<String> rest(UUID user, String method, String path, String body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path))
                .header("Authorization", "Bearer " + jwt.createAccessToken(user)).header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body))
                .timeout(Duration.ofSeconds(20)).build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    void sendChat(UUID user, String content) throws Exception {
        assertThat(rest(user, "POST", "/rooms/" + room + "/messages", "{\"content\":\"" + content + "\"}").statusCode()).isEqualTo(201);
    }

    // --- CONNECT --------------------------------------------------------------------------------------------------

    @Test void connectWithoutTokenIsRefusedWithAuthRequired() throws Exception {
        Client client = connect(null);
        assertThat(client.error()).isEqualTo("AUTH_REQUIRED");
        assertThat(client.connected.isDone()).isFalse();
    }

    @Test void connectWithMalformedAuthorizationHeaderIsRefused() throws Exception {
        assertThat(connect("Token abc").error()).isEqualTo("AUTH_REQUIRED");
    }

    @Test void connectWithExpiredTokenIsRefusedWithTokenExpired() throws Exception {
        var key = Keys.hmacShaKeyFor(Decoders.BASE64.decode(SECRET));
        assertThat(connect("Bearer " + token(key, owner, Instant.now().minusSeconds(60))).error()).isEqualTo("TOKEN_EXPIRED");
    }

    @Test void connectWithForgedTokenIsRefusedWithTokenInvalid() throws Exception {
        var otherKey = Keys.hmacShaKeyFor(Decoders.BASE64.decode("b3RoZXItc2VjcmV0LTAxMjM0NTY3ODlhYmNkZWZnaGlqa2xtbm9w"));
        assertThat(connect("Bearer " + token(otherKey, owner, Instant.now().plusSeconds(600))).error()).isEqualTo("TOKEN_INVALID");
        assertThat(connect("Bearer not-a-jwt").error()).isEqualTo("TOKEN_INVALID");
    }

    @Test void connectWithTokenOfUnknownUserIsRefused() throws Exception {
        assertThat(connect("Bearer " + jwt.createAccessToken(UUID.randomUUID())).error()).isEqualTo("TOKEN_INVALID");
    }

    @Test void connectWithTokenOfWithdrawnUserIsRefused() throws Exception {
        assertThat(rest(outsider, "DELETE", "/users/me", null).statusCode()).isEqualTo(200);
        assertThat(connect("Bearer " + jwt.createAccessToken(outsider)).error()).isEqualTo("TOKEN_INVALID");
    }

    @Test void validConnectionReceivesTenSecondHeartbeat() throws Exception {
        Client client = connectAs(owner);
        assertThat(client.connected.get().getHeartbeat()).containsExactly(10_000L, 10_000L);
    }

    @Test void handshakeFromAnOriginOutsideTheWebSocketListIsRefused() {
        var headers = new WebSocketHttpHeaders();
        headers.setOrigin("https://evil.example");
        var attempt = stomp.connectAsync("ws://localhost:" + port + "/ws", headers, new StompHeaders(), new Client());
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> attempt.get(10, TimeUnit.SECONDS)).hasMessageContaining("403");
    }

    @Test void handshakeFromAnAllowedOriginWorks() throws Exception {
        var headers = new WebSocketHttpHeaders();
        headers.setOrigin("http://localhost:5173");
        Client client = new Client();
        var stompHeaders = new StompHeaders();
        stompHeaders.add("Authorization", "Bearer " + jwt.createAccessToken(owner));
        open(client, headers, stompHeaders);
        client.connected.get(5, TimeUnit.SECONDS);
    }

    // --- SUBSCRIBE / SEND -----------------------------------------------------------------------------------------

    @Test void memberSubscribesAndReceivesAPostedChatMessage() throws Exception {
        Client client = connectAs(member);
        client.subscribe(room);
        sendChat(owner, "안녕하세요");
        JsonNode event = client.next("MESSAGE_CREATED");
        assertThat(event.get("roomId").asString()).isEqualTo(room.toString());
        assertThat(event.get("at").asString()).isNotBlank();
        JsonNode message = event.get("payload");
        assertThat(message.get("senderType").asString()).isEqualTo("USER");
        assertThat(message.get("senderId").asString()).isEqualTo(owner.toString());
        assertThat(message.get("nickname").asString()).isEqualTo("방장");
        assertThat(message.get("content").asString()).isEqualTo("안녕하세요");
        assertThat(message.get("meta").isNull()).isTrue();
        assertThat(event.propertyNames()).containsExactlyInAnyOrder("type", "roomId", "payload", "at");
    }

    @Test void nonMemberSubscribeIsRefusedAndReceivesNothing() throws Exception {
        Client client = connectAs(outsider);
        client.subscribe(room);
        assertThat(client.error()).isEqualTo("NOT_ROOM_MEMBER");
        sendChat(owner, "비밀 이야기");
        client.assertNoEvent();
    }

    @Test void unknownRoomSubscribeIsRefused() throws Exception {
        Client client = connectAs(owner);
        client.subscribe(UUID.randomUUID());
        assertThat(client.error()).isEqualTo("ROOM_NOT_FOUND");
    }

    @Test void destinationsOtherThanARoomTopicAreRefused() throws Exception {
        for (String destination : new String[] {"/topic/other", "/topic/rooms/not-a-uuid", "/topic/rooms/" + room + "/x",
                "/topic/rooms/" + room.toString().toUpperCase(), "/user/queue/notifications", "/queue/x"}) {
            Client client = connectAs(owner);
            client.session.subscribe(destination, new StompFrameHandler() {
                @Override public Type getPayloadType(StompHeaders headers) { return String.class; }
                @Override public void handleFrame(StompHeaders headers, Object payload) {}
            });
            assertThat(client.error()).as(destination).isEqualTo("VALIDATION_ERROR");
        }
    }

    @Test void clientSendToATopicIsBlockedAndNobodyReceivesIt() throws Exception {
        Client sender = connectAs(member);
        Client listener = connectAs(owner);
        listener.subscribe(room);
        sender.subscribe(room);
        sender.session.send("/topic/rooms/" + room, "{\"type\":\"MESSAGE_CREATED\",\"roomId\":\"" + room + "\",\"payload\":{}}");
        assertThat(sender.error()).isEqualTo("ACCESS_DENIED");
        listener.assertNoEvent();
        Client another = connectAs(member);
        another.session.send("/app/anything", "x");
        assertThat(another.error()).isEqualTo("ACCESS_DENIED");
    }

    @Test void spoofedIdentityHeadersAreIgnored() throws Exception {
        Client client = new Client();
        StompHeaders headers = new StompHeaders();
        headers.add("Authorization", "Bearer " + jwt.createAccessToken(outsider));
        headers.add("login", owner.toString());
        headers.add("user-name", owner.toString());
        headers.add("user", owner.toString());
        open(client, new WebSocketHttpHeaders(), headers);
        client.connected.get(5, TimeUnit.SECONDS);
        client.subscribe(room);
        assertThat(client.error()).isEqualTo("NOT_ROOM_MEMBER");
    }

    // --- leave / withdrawal ---------------------------------------------------------------------------------------

    @Test void afterLeavingTheOldSubscriptionReceivesNothingMore() throws Exception {
        Client leaver = connectAs(member);
        Client stayer = connectAs(owner);
        leaver.subscribe(room);
        stayer.subscribe(room);

        assertThat(rest(member, "DELETE", "/rooms/" + room + "/members/me", null).statusCode()).isEqualTo(200);
        JsonNode left = stayer.next("MEMBER_LEFT");
        assertThat(left.get("payload").get("userId").asString()).isEqualTo(member.toString());
        assertThat(left.get("payload").has("newOwnerId")).isFalse();
        assertThat(stayer.next("MESSAGE_CREATED").get("payload").get("senderType").asString()).isEqualTo("SYSTEM");

        sendChat(owner, "나간 사람은 못 봐요");
        assertThat(stayer.next("MESSAGE_CREATED").get("payload").get("content").asString()).isEqualTo("나간 사람은 못 봐요");
        leaver.assertNoEvent();
        assertThat(leaver.closed.getCount()).as("the connection itself stays open").isEqualTo(1);
    }

    @Test void leavingTheRoomAgainOnANewSubscriptionIsRefused() throws Exception {
        assertThat(rest(member, "DELETE", "/rooms/" + room + "/members/me", null).statusCode()).isEqualTo(200);
        Client client = connectAs(member);
        client.subscribe(room);
        assertThat(client.error()).isEqualTo("NOT_ROOM_MEMBER");
    }

    @Test void ownerLeavingSendsMemberLeftAndOwnerChanged() throws Exception {
        Client stayer = connectAs(member);
        stayer.subscribe(room);
        assertThat(rest(owner, "DELETE", "/rooms/" + room + "/members/me", null).statusCode()).isEqualTo(200);
        JsonNode left = stayer.next("MEMBER_LEFT");
        assertThat(left.get("payload").get("userId").asString()).isEqualTo(owner.toString());
        assertThat(left.get("payload").get("newOwnerId").asString()).isEqualTo(member.toString());
        JsonNode changed = stayer.next("OWNER_CHANGED");
        assertThat(changed.get("payload").get("previousOwnerId").asString()).isEqualTo(owner.toString());
        assertThat(changed.get("payload").get("newOwnerId").asString()).isEqualTo(member.toString());
    }

    @Test void afterWithdrawalAllConnectionsOfThatUserAreClosedAndTheRoomIsTold() throws Exception {
        Client first = connectAs(member);
        Client second = connectAs(member);
        Client stayer = connectAs(owner);
        first.subscribe(room);
        stayer.subscribe(room);

        assertThat(rest(member, "DELETE", "/users/me", null).statusCode()).isEqualTo(200);
        assertThat(first.closed.await(5, TimeUnit.SECONDS)).as("first connection closed").isTrue();
        assertThat(second.closed.await(5, TimeUnit.SECONDS)).as("second connection closed").isTrue();
        assertThat(stayer.next("MEMBER_LEFT").get("payload").get("userId").asString()).isEqualTo(member.toString());
        assertThat(stayer.closed.getCount()).as("other users stay connected").isEqualTo(1);
    }

    // --- events after commit --------------------------------------------------------------------------------------

    @Test void joinSendsMemberJoinedAndTheSystemMessage() throws Exception {
        Client client = connectAs(owner);
        client.subscribe(room);
        assertThat(rest(outsider, "POST", "/rooms/join", "{\"roomCode\":\"S00001\"}").statusCode()).isEqualTo(200);
        JsonNode joined = client.next("MEMBER_JOINED");
        assertThat(joined.get("payload").get("userId").asString()).isEqualTo(outsider.toString());
        assertThat(joined.get("payload").get("nickname").asString()).isEqualTo("외부인");
        assertThat(client.next("MESSAGE_CREATED").get("payload").get("content").asString()).contains("외부인님이 입장했어요");
    }

    @Test void departureChangeSendsOnlyWhenTheStationChanged() throws Exception {
        Client client = connectAs(owner);
        client.subscribe(room);
        long station = jdbc.queryForObject("select id from stations where is_supported order by id limit 1", Long.class);
        String body = "{\"departureStationId\":" + station + "}";
        assertThat(rest(member, "PATCH", "/rooms/" + room + "/members/me", body).statusCode()).isEqualTo(200);
        JsonNode event = client.next("DEPARTURE_UPDATED");
        assertThat(event.get("payload").get("userId").asString()).isEqualTo(member.toString());
        assertThat(event.get("payload").get("station").get("id").asLong()).isEqualTo(station);
        assertThat(event.get("payload").get("station").get("name").asString()).isNotBlank();
        assertThat(rest(member, "PATCH", "/rooms/" + room + "/members/me", body).statusCode()).isEqualTo(200);
        client.assertNoEvent();
    }

    @Test void roomUpdateSendsRoomUpdatedOnlyWhenSomethingChanged() throws Exception {
        Client client = connectAs(member);
        client.subscribe(room);
        assertThat(rest(owner, "PATCH", "/rooms/" + room, "{\"name\":\"새 이름\",\"appointmentAt\":\"2030-01-02T03:04:05Z\"}").statusCode()).isEqualTo(200);
        JsonNode payload = client.next("ROOM_UPDATED").get("payload");
        assertThat(payload.get("id").asString()).isEqualTo(room.toString());
        assertThat(payload.get("name").asString()).isEqualTo("새 이름");
        assertThat(payload.get("status").asString()).isEqualTo("OPEN");
        assertThat(payload.get("memberCount").asInt()).isEqualTo(2);
        assertThat(payload.get("maxMembers").asInt()).isEqualTo(4);
        assertThat(payload.get("appointmentAt").asString()).isEqualTo("2030-01-02T03:04:05Z");
        assertThat(payload.get("selectedStation").isNull()).isTrue();
        assertThat(payload.has("myRole")).isFalse();
        assertThat(rest(owner, "PATCH", "/rooms/" + room, "{\"name\":\"새 이름\"}").statusCode()).isEqualTo(200);
        client.assertNoEvent();
    }

    @Test void stationProposalCreateAndDeleteSendEvents() throws Exception {
        Client client = connectAs(owner);
        client.subscribe(room);
        long station = jdbc.queryForObject("select id from stations where is_supported order by id limit 1", Long.class);
        var created = rest(member, "POST", "/rooms/" + room + "/station-proposals", "{\"stationId\":" + station + "}");
        assertThat(created.statusCode()).isEqualTo(201);
        String proposalId = mapper.readTree(created.body()).get("data").get("id").asString();
        JsonNode event = client.next("STATION_PROPOSAL_CREATED").get("payload");
        assertThat(event.get("id").asString()).isEqualTo(proposalId);
        assertThat(event.get("station").get("id").asLong()).isEqualTo(station);
        assertThat(event.get("proposedBy").get("userId").asString()).isEqualTo(member.toString());
        assertThat(client.next("MESSAGE_CREATED").get("payload").get("senderType").asString()).isEqualTo("SYSTEM");

        assertThat(rest(member, "DELETE", "/rooms/" + room + "/station-proposals/" + proposalId, null).statusCode()).isEqualTo(200);
        assertThat(client.next("STATION_PROPOSAL_DELETED").get("payload").get("proposalId").asString()).isEqualTo(proposalId);
    }

    @Test void recommendationCreatedEventIsForwardedWithItsRunId() throws Exception {
        Client client = connectAs(member);
        client.subscribe(room);
        UUID run = UUID.randomUUID();
        publisher.publishEvent(new RecommendationCreatedEvent(room, run, owner, Instant.now()));
        JsonNode event = client.next("RECOMMENDATION_CREATED");
        assertThat(event.get("payload").get("runId").asString()).isEqualTo(run.toString());
        assertThat(event.get("roomId").asString()).isEqualTo(room.toString());
    }

    @Test void eventsOfOtherRoomsAreNotDelivered() throws Exception {
        UUID otherRoom = creation.create(outsider, "S00002", "other", null).id();
        Client client = connectAs(owner);
        client.subscribe(room);
        roomEvents.publish(otherRoom, RoomEventType.ROOM_UPDATED, java.util.Map.of("x", 1));
        client.assertNoEvent();
    }

    @Test void rolledBackTransactionSendsNoEventButACommittedOneDoes() throws Exception {
        Client client = connectAs(member);
        client.subscribe(room);
        var tx = new TransactionTemplate(transactions);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            roomEvents.publish(room, RoomEventType.ROOM_UPDATED, java.util.Map.of("phase", "rolled-back"));
            throw new IllegalStateException("boom");
        })).hasMessage("boom");
        client.assertNoEvent();

        tx.executeWithoutResult(status -> roomEvents.publish(room, RoomEventType.ROOM_UPDATED, java.util.Map.of("phase", "committed")));
        assertThat(client.next("ROOM_UPDATED").get("payload").get("phase").asString()).isEqualTo("committed");
    }

    @Test void eventIsNotSentBeforeTheTransactionCommits() throws Exception {
        Client client = connectAs(member);
        client.subscribe(room);
        var tx = new TransactionTemplate(transactions);
        tx.executeWithoutResult(status -> {
            roomEvents.publish(room, RoomEventType.ROOM_UPDATED, java.util.Map.of("phase", "inside"));
            try { client.assertNoEvent(); } catch (Exception failure) { throw new AssertionError(failure); }
        });
        assertThat(client.next("ROOM_UPDATED").get("payload").get("phase").asString()).isEqualTo("inside");
    }

    @Test void aFailingDeliveryNeverRollsBackTheCommittedChange() {
        var tx = new TransactionTemplate(transactions);
        Object unserializable = new Object() {
            @SuppressWarnings("unused") public String getBoom() { throw new IllegalStateException("cannot serialize"); }
        };
        tx.executeWithoutResult(status -> {
            jdbc.update("update users set nickname = '바뀐이름' where id = ?", owner);
            roomEvents.publish(room, RoomEventType.ROOM_UPDATED, unserializable);
        });
        assertThat(jdbc.queryForObject("select nickname from users where id = ?", String.class, owner)).isEqualTo("바뀐이름");
    }
}
