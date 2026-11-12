package com.mju.linkro.chat;

import com.mju.linkro.ai.mock.MockAiClient;
import com.mju.linkro.auth.jwt.JwtProvider;
import com.mju.linkro.chat.domain.SenderType;
import com.mju.linkro.chat.event.MessageCreatedEvent;
import com.github.f4b6a3.uuid.UuidCreator;
import com.mju.linkro.chat.repository.RoomMessageRepository;
import com.mju.linkro.chat.service.ChatAnswerSweep;
import com.mju.linkro.room.domain.MeetingMember;
import com.mju.linkro.room.domain.RoomRole;
import com.mju.linkro.room.dto.SelectedStationRequest;
import com.mju.linkro.room.dto.StationProposalRequest;
import com.mju.linkro.room.repository.MeetingMemberRepository;
import com.mju.linkro.room.service.RoomCreationAttempt;
import com.mju.linkro.room.service.RoomLeaveService;
import com.mju.linkro.room.service.RoomMembershipService;
import com.mju.linkro.room.service.SelectedStationService;
import com.mju.linkro.room.service.StationProposalService;
import com.mju.linkro.user.domain.User;
import com.mju.linkro.user.repository.UserRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Chat on a real PostgreSQL 16: the cursor query, the HTTP flow, @링크로 answers and the SYSTEM messages of room events. */
@Testcontainers
@SpringBootTest(properties = {
        "spring.config.import=", "spring.jpa.hibernate.ddl-auto=validate", "spring.jpa.open-in-view=false",
        "spring.jpa.show-sql=false", "auth.kakao.app-id=1234", "auth.jwt.access-token-ttl=15m",
        "auth.jwt.secret=dGVzdC1vbmx5LXNlY3JldC0wMTIzNDU2Nzg5YWJjZGVmZ2hpams=",
        "linkro.chat.ai-call-interval=0s", "linkro.chat.answer-sweep-window=0s"})
@Import(ChatPostgresTest.Events.class)
@Timeout(60)
class ChatPostgresTest {
    @TestConfiguration(proxyBeanMethods = false)
    static class Events {
        static final List<MessageCreatedEvent> SEEN = new CopyOnWriteArrayList<>();
        @EventListener void on(MessageCreatedEvent event) { SEEN.add(event); }
    }

    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired WebApplicationContext context;
    @Autowired JwtProvider jwt;
    @Autowired UserRepository users;
    @Autowired MeetingMemberRepository members;
    @Autowired RoomCreationAttempt creation;
    @Autowired RoomMembershipService membership;
    @Autowired RoomLeaveService leaving;
    @Autowired StationProposalService proposals;
    @Autowired SelectedStationService selection;
    @Autowired RoomMessageRepository messages;
    @Autowired JdbcTemplate jdbc;
    @Autowired ChatProperties chatProperties;
    @Autowired ChatAnswerSweep sweep;
    @Autowired ChatAiShutdown aiShutdown;
    @Autowired org.springframework.core.env.Environment environment;
    final JsonMapper mapper = JsonMapper.builder().build();
    MockMvc mvc;
    UUID owner, member, outsider, room;
    List<Long> stations;

    @BeforeEach void setup() {
        jdbc.update("delete from meeting_rooms");
        jdbc.update("delete from refresh_tokens");
        jdbc.update("delete from users");
        Events.SEEN.clear();
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        owner = users.saveAndFlush(new User("owner", "방장")).getId();
        member = users.saveAndFlush(new User("member", "민지")).getId();
        outsider = users.saveAndFlush(new User("outsider", "외부인")).getId();
        room = creation.create(owner, "C00001", "room", null).id();
        members.saveAndFlush(new MeetingMember(room, member, RoomRole.MEMBER));
        stations = jdbc.queryForList("select id from stations where is_supported order by id limit 3", Long.class);
    }

    MockHttpServletRequestBuilder as(UUID user, MockHttpServletRequestBuilder request) {
        return request.header("Authorization", "Bearer " + jwt.createAccessToken(user));
    }

    ResultActions send(UUID user, String content) throws Exception {
        String body = mapper.writeValueAsString(Map.of("content", content));
        return mvc.perform(as(user, post("/api/v1/rooms/" + room + "/messages").contentType("application/json").content(body)));
    }

    JsonNode list(UUID user, String query) throws Exception {
        String json = mvc.perform(as(user, get("/api/v1/rooms/" + room + "/messages" + query))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return mapper.readTree(json).get("data");
    }

    List<String> texts(String senderType) {
        return jdbc.queryForList("select content from room_messages where room_id = ? and sender_type = ? order by id",
                String.class, room, senderType);
    }

    static void waitFor(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean()) {
            assertThat(System.nanoTime()).as("condition not met in 10s").isLessThan(deadline);
            Thread.sleep(50);
        }
    }

    // --- cursor query ---------------------------------------------------------------------------------------------

    @Test void cursorOrderIsThePostgresUuidOrderNotJavaUuidOrder() {
        // Same first 64 bits, last 64 bits differ in the sign bit: Java compares signed longs, PostgreSQL unsigned bytes.
        UUID low = UUID.fromString("0192f3c0-0000-7000-7fff-ffffffffffff");
        UUID high = UUID.fromString("0192f3c0-0000-7000-8000-000000000000");
        UUID newest = UUID.fromString("0192f3c0-0001-7000-8000-000000000000");
        assertThat(high.compareTo(low)).isNegative();
        for (UUID id : List.of(high, newest, low)) messages.insert(id, room, SenderType.USER, owner, id.toString());

        assertThat(messages.page(room, null, 10)).extracting(m -> m.id()).containsExactly(newest, high, low);
        assertThat(messages.page(room, newest, 10)).extracting(m -> m.id()).containsExactly(high, low);
        assertThat(messages.page(room, high, 10)).extracting(m -> m.id()).containsExactly(low);
        assertThat(messages.page(room, low, 10)).isEmpty();
        assertThat(messages.page(room, null, 2)).hasSize(2);
    }

    @Test void pagesAreScopedToTheRoomAndWalkWithoutGapsOrRepeats() throws Exception {
        UUID otherRoom = creation.create(owner, "C00002", "other", null).id();
        messages.insert(UUID.fromString("0192f3c0-0005-7000-8000-000000000000"), otherRoom, SenderType.USER, owner, "elsewhere");
        for (int i = 1; i <= 5; i++) send(owner, "m" + i).andExpect(status().isCreated());

        List<String> seen = new ArrayList<>();
        String cursor = null;
        for (int page = 0; page < 3; page++) {
            JsonNode data = list(member, "?size=2" + (cursor == null ? "" : "&cursor=" + cursor));
            data.get("items").forEach(item -> seen.add(item.get("content").asString()));
            if (!data.get("hasMore").asBoolean()) {
                assertThat(data.get("nextCursor").isNull()).isTrue();
                break;
            }
            cursor = data.get("nextCursor").asString();
        }
        assertThat(seen).containsExactly("m5", "m4", "m3", "m2", "m1");
    }

    // --- HTTP flow ------------------------------------------------------------------------------------------------

    @Test void aMemberSendsAndEveryoneReadsAndAnOutsiderCannot() throws Exception {
        send(member, "안녕").andExpect(status().isCreated()).andExpect(jsonPath("$.data.aiPending").value(false))
                .andExpect(jsonPath("$.data.message.senderType").value("USER"))
                .andExpect(jsonPath("$.data.message.senderId").value(member.toString()))
                .andExpect(jsonPath("$.data.message.nickname").value("민지"))
                .andExpect(jsonPath("$.data.message.meta").value((Object) null));
        send(outsider, "들어가도 돼?").andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("NOT_ROOM_MEMBER"));
        mvc.perform(as(outsider, get("/api/v1/rooms/" + room + "/messages"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/rooms/" + room + "/messages")).andExpect(status().isUnauthorized());

        JsonNode items = list(owner, "").get("items");
        assertThat(items).hasSize(1);
        assertThat(items.get(0).get("content").asString()).isEqualTo("안녕");
        assertThat(Events.SEEN).extracting(e -> e.message().content()).containsExactly("안녕");
    }

    @Test void theLengthLimitIs1000CharactersAndTheDatabaseColumnAllows4000() throws Exception {
        send(owner, "가".repeat(1000)).andExpect(status().isCreated());
        messages.insert(UUID.randomUUID(), room, SenderType.AI, null, "가".repeat(4000));
        org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,
                () -> messages.insert(UUID.randomUUID(), room, SenderType.AI, null, "가".repeat(4001)));
        send(owner, "가".repeat(1001)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        send(owner, "   ").andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("select count(*) from room_messages where room_id = ?", Integer.class, room)).isEqualTo(2);
        mvc.perform(as(owner, get("/api/v1/rooms/" + room + "/messages?size=0"))).andExpect(status().isBadRequest());
        mvc.perform(as(owner, get("/api/v1/rooms/" + room + "/messages?cursor=nope"))).andExpect(status().isBadRequest());
    }

    @Test void aMentionIsAnsweredByAnAiMessageWithNoSender() throws Exception {
        send(member, "@링크로 사당역에서 갈만한 곳 알려줘").andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.aiPending").value(true));
        waitFor(() -> !texts("AI").isEmpty());
        assertThat(jdbc.queryForObject("select sender_id is null from room_messages where sender_type = 'AI' and room_id = ?",
                Boolean.class, room)).isTrue();
        waitFor(() -> Events.SEEN.size() == 2);
        assertThat(Events.SEEN).extracting(e -> e.message().senderType()).containsExactly(SenderType.USER, SenderType.AI);
        JsonNode items = list(owner, "").get("items");
        assertThat(items.get(0).get("senderType").asString()).isEqualTo("AI");
        assertThat(items.get(0).get("senderId").isNull()).isTrue();
        assertThat(items.get(0).get("nickname").isNull()).isTrue();
    }

    @Test void aFailedAiLeavesTheQuestionAndAddsASystemNotice() throws Exception {
        send(member, "@링크로 " + MockAiClient.FAIL_MARKER).andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.aiPending").value(true));
        waitFor(() -> !texts("SYSTEM").isEmpty());
        assertThat(texts("USER")).hasSize(1);
        assertThat(texts("AI")).isEmpty();
        assertThat(jdbc.queryForObject("select sender_id is null from room_messages where sender_type = 'SYSTEM' and room_id = ?",
                Boolean.class, room)).isTrue();
    }

    // --- call interval (#117) ---------------------------------------------------------------------------------------

    @Test void aSecondMentionWithinTheIntervalIs429AndNotSavedWhileOtherMessagesAndUsersAreFine() throws Exception {
        chatProperties.setAiCallInterval(java.time.Duration.ofSeconds(10));
        try {
            send(member, "@링크로 하나").andExpect(status().isCreated());
            send(member, "@링크로 둘").andExpect(status().isTooManyRequests())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.error.code").value("AI_QUOTA_EXCEEDED"))
                    .andExpect(jsonPath("$.error.details.retryAfterSeconds").value(org.hamcrest.Matchers.allOf(
                            org.hamcrest.Matchers.greaterThanOrEqualTo(1), org.hamcrest.Matchers.lessThanOrEqualTo(10))));
            // A message without the mention is never limited, even right after a refused call.
            for (int i = 0; i < 3; i++) send(member, "그냥 말해요 " + i).andExpect(status().isCreated());
            // Another user has their own interval.
            send(owner, "@링크로 방장도 물어요").andExpect(status().isCreated());

            assertThat(texts("USER")).containsExactly("@링크로 하나", "그냥 말해요 0", "그냥 말해요 1", "그냥 말해요 2", "@링크로 방장도 물어요");
            waitFor(() -> texts("AI").size() == 2);
        } finally {
            chatProperties.setAiCallInterval(java.time.Duration.ZERO);
        }
    }

    @Test void aRefusedOutsiderOrInvalidMentionDoesNotUseUpTheTurn() throws Exception {
        chatProperties.setAiCallInterval(java.time.Duration.ofSeconds(10));
        try {
            send(outsider, "@링크로 들어가도 되나요").andExpect(status().isForbidden());
            send(member, "   ").andExpect(status().isBadRequest());
            send(member, "@링크로 " + "가".repeat(1001)).andExpect(status().isBadRequest());
            send(member, "@링크로 진짜 질문").andExpect(status().isCreated());
            waitFor(() -> texts("AI").size() == 1);
        } finally {
            chatProperties.setAiCallInterval(java.time.Duration.ZERO);
        }
    }

    // --- shutdown and startup sweep (#117) ----------------------------------------------------------------------------

    @Test void gracefulShutdownIsOnAndTheAnswerDrainIsPartOfTheLifecycle() {
        assertThat(environment.getProperty("server.shutdown")).isEqualTo("graceful");
        assertThat(aiShutdown.isRunning()).isTrue();
        assertThat(aiShutdown.getPhase()).isLessThan(org.springframework.context.SmartLifecycle.DEFAULT_PHASE);
        assertThat(chatProperties.getAiShutdownWait()).isEqualTo(java.time.Duration.ofSeconds(15));
    }

    UUID save(UUID roomId, SenderType type, UUID sender, String content) {
        UUID id = UuidCreator.getTimeOrderedEpoch();
        messages.insert(id, roomId, type, sender, content);
        return id;
    }

    static final String NOTICE = "AI가 답변을 만들지 못했어요. 잠시 후 다시 시도해 주세요.";

    @Test void theStartupSweepAnswersOnlyTheUnansweredQuestionsOfAMultiQuestionRoomAndIsIdempotent() {
        chatProperties.setAnswerSweepWindow(java.time.Duration.ofMinutes(10));
        try {
            UUID otherRoom = creation.create(owner, "C00002", "other", null).id();
            save(room, SenderType.USER, member, "@링크로 첫 질문");           // answered
            save(room, SenderType.AI, null, "첫 답");
            save(room, SenderType.USER, member, "그냥 대화");
            save(room, SenderType.USER, owner, "@링크로 둘째 질문");          // lost
            save(room, SenderType.USER, member, "me@링크로 메일");            // not a mention
            save(room, SenderType.USER, member, "@링크로야 셋째 질문");       // lost
            save(otherRoom, SenderType.USER, owner, "@링크로 다른 방 질문");  // lost, other room
            UUID old = save(room, SenderType.USER, owner, "@링크로 오래된 질문");
            jdbc.update("update room_messages set created_at = now() - interval '1 hour' where id = ?", old);
            Instant before = Instant.now().plusSeconds(60);
            Events.SEEN.clear();

            assertThat(sweep.sweep(Instant.now(), before)).isEqualTo(3);

            assertThat(texts("SYSTEM")).containsExactly(NOTICE, NOTICE);
            assertThat(jdbc.queryForList("select content from room_messages where room_id = ? and sender_type = 'SYSTEM'",
                    String.class, otherRoom)).containsExactly(NOTICE);
            assertThat(Events.SEEN).extracting(e -> e.message().senderType()).containsOnly(SenderType.SYSTEM).hasSize(3);

            // A restart again changes nothing: the notices count as replies.
            assertThat(sweep.sweep(Instant.now(), before)).isZero();
            assertThat(texts("SYSTEM")).hasSize(2);
        } finally {
            chatProperties.setAnswerSweepWindow(java.time.Duration.ZERO);
        }
    }

    @Test void aQuestionAnsweredByTheAiOrByAFailureNoticeIsNotSweptAndANewerQuestionIsLeftToTheRunningServer() {
        chatProperties.setAnswerSweepWindow(java.time.Duration.ofMinutes(10));
        try {
            save(room, SenderType.USER, member, "@링크로 답 받은 질문");
            save(room, SenderType.AI, null, "답");
            save(room, SenderType.USER, member, "@링크로 안내 받은 질문");
            save(room, SenderType.SYSTEM, null, "AI 사용 한도를 초과해 답변하지 못했어요. 잠시 후 다시 시도해 주세요.");
            Instant before = Instant.now().plusSeconds(60);
            save(room, SenderType.USER, member, "@링크로 서버가 뜬 뒤의 질문");
            jdbc.update("update room_messages set created_at = now() + interval '2 minutes' where content = ?", "@링크로 서버가 뜬 뒤의 질문");

            assertThat(sweep.sweep(Instant.now(), before)).isZero();
            assertThat(texts("SYSTEM")).hasSize(1);
        } finally {
            chatProperties.setAnswerSweepWindow(java.time.Duration.ZERO);
        }
    }

    // --- SYSTEM messages of room events ---------------------------------------------------------------------------

    @Test void roomEventsLeaveSystemMessagesInOrder() {
        UUID joiner = users.saveAndFlush(new User("joiner", "새멤버")).getId();
        membership.join(joiner, "C00001");
        var created = proposals.create(room, member, mapper.readValue("{\"stationId\":" + stations.get(0) + "}", StationProposalRequest.class));
        proposals.delete(room, owner, created.id());
        selection.select(room, owner, mapper.readValue("{\"stationId\":" + stations.get(0) + "}", SelectedStationRequest.class));
        selection.select(room, owner, mapper.readValue("{\"stationId\":" + stations.get(0) + "}", SelectedStationRequest.class));
        selection.select(room, owner, mapper.readValue("{\"stationId\":" + stations.get(1) + "}", SelectedStationRequest.class));
        selection.clear(room, owner);
        selection.clear(room, owner);
        leaving.leave(room, owner);

        List<String> system = texts("SYSTEM");
        String first = jdbc.queryForObject("select name from stations where id = ?", String.class, stations.get(0));
        String second = jdbc.queryForObject("select name from stations where id = ?", String.class, stations.get(1));
        assertThat(system).hasSize(8);
        assertThat(system.get(0)).isEqualTo("새멤버님이 입장했어요.");
        assertThat(system.get(1)).contains("민지님이").contains(first).contains("후보로 제안했어요");
        assertThat(system.get(2)).contains("방장님이").contains("후보").contains(first).endsWith("삭제했어요.");
        assertThat(system.get(3)).contains("지정했어요").contains(first);
        assertThat(system.get(4)).contains("변경했어요").contains(second);
        assertThat(system.get(5)).isEqualTo("방장님이 약속역 지정을 해제했어요.");
        assertThat(system.get(6)).isEqualTo("방장님이 나갔어요.");
        assertThat(system.get(7)).endsWith("님이 새 방장이 됐어요.");
        assertThat(jdbc.queryForObject("select count(*) from room_messages where sender_type = 'SYSTEM' and sender_id is not null",
                Integer.class)).isZero();
        assertThat(Events.SEEN).hasSize(8).allSatisfy(e -> assertThat(e.message().senderType()).isEqualTo(SenderType.SYSTEM));
    }

    @Test void aRolledBackRoomChangeLeavesNoSystemMessageAndNoEvent() {
        long unknownStation = -1L;
        org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,
                () -> selection.select(room, owner, mapper.readValue("{\"stationId\":" + unknownStation + "}", SelectedStationRequest.class)));
        assertThat(texts("SYSTEM")).isEmpty();
        assertThat(Events.SEEN).isEmpty();
    }

    @Test void theLastMemberLeavingDeletesTheRoomAndItsMessages() throws Exception {
        send(owner, "마지막 말").andExpect(status().isCreated());
        leaving.leave(room, member);
        leaving.leave(room, owner);
        assertThat(jdbc.queryForObject("select count(*) from room_messages where room_id = ?", Integer.class, room)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from meeting_rooms where id = ?", Integer.class, room)).isZero();
    }
}
