package com.mju.linkro.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mju.linkro.ai.api.AiClient;
import com.mju.linkro.ai.api.ChatReplyRequest;
import com.mju.linkro.ai.api.GeneratedText;
import com.mju.linkro.auth.jwt.JwtProvider;
import com.mju.linkro.chat.service.ChatTools;
import com.mju.linkro.recommendation.service.RecommendationService;
import com.mju.linkro.room.domain.MeetingMember;
import com.mju.linkro.room.domain.RoomRole;
import com.mju.linkro.room.repository.MeetingMemberRepository;
import com.mju.linkro.room.service.RoomCreationAttempt;
import com.mju.linkro.subway.api.TimetableRouteFinder;
import com.mju.linkro.user.domain.User;
import com.mju.linkro.user.repository.UserRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The @링크로 tools (#129) on a real PostgreSQL 16 with the seed, the graph module and the train timetable: the tools
 * read what the recommendation and route services compute, and a scripted AI answers a chat question from a tool result.
 */
@Testcontainers
@SpringBootTest(properties = {
        "spring.config.import=", "spring.jpa.hibernate.ddl-auto=validate", "spring.jpa.open-in-view=false",
        "spring.jpa.show-sql=false", "auth.kakao.app-id=1234", "auth.jwt.access-token-ttl=15m",
        "auth.jwt.secret=dGVzdC1vbmx5LXNlY3JldC0wMTIzNDU2Nzg5YWJjZGVmZ2hpams=", "linkro.subway.module=graph",
        "linkro.chat.ai-call-interval=0s", "linkro.chat.answer-sweep-window=0s"})
@Timeout(120)
class ChatToolsPostgresTest {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    /** 2027-01-05 (Tuesday) 08:30 in Seoul, like the route search tests. */
    static final Instant APPOINTMENT = Instant.parse("2027-01-04T23:30:00Z");

    @Autowired WebApplicationContext context;
    @Autowired JwtProvider jwt;
    @Autowired UserRepository users;
    @Autowired MeetingMemberRepository members;
    @Autowired RoomCreationAttempt creation;
    @Autowired RecommendationService recommendations;
    @Autowired ChatTools tools;
    @Autowired TimetableRouteFinder timetable;
    @Autowired JdbcTemplate jdbc;
    @MockitoSpyBean AiClient ai;
    final JsonMapper mapper = JsonMapper.builder().build();
    MockMvc mvc;
    UUID asker, friend, outsider, room;
    long gangnam, nowon, sadang;

    long id(String name) {
        return jdbc.queryForObject("select id from stations where name = ?", Long.class, name);
    }

    @BeforeEach void setup() {
        jdbc.update("delete from meeting_rooms");
        jdbc.update("delete from refresh_tokens");
        jdbc.update("delete from users");
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        asker = users.saveAndFlush(new User("asker", "민지")).getId();
        friend = users.saveAndFlush(new User("friend", "민수")).getId();
        outsider = users.saveAndFlush(new User("outsider", "외부인")).getId();
        gangnam = id("강남");
        nowon = id("노원");
        sadang = id("사당");
        room = creation.create(asker, "T00001", "점심약속", APPOINTMENT).id();
        members.saveAndFlush(new MeetingMember(room, friend, RoomRole.MEMBER));
        depart(asker, gangnam);
        depart(friend, nowon);
    }

    void depart(UUID user, Long station) {
        jdbc.update("update meeting_members set departure_station_id = ? where room_id = ? and user_id = ?", station, room, user);
    }

    JsonNode run(UUID user, String tool, String arguments) {
        return mapper.readTree(tools.forAsker(room, user).run(tool, arguments));
    }

    @Test void theTwoRoomToolsReadTheRealRoom() {
        JsonNode info = run(asker, "get_room_info", "{}");
        assertThat(info.get("roomName").asString()).isEqualTo("점심약속");
        assertThat(info.get("appointmentAt").asString()).isEqualTo("2027-01-05T08:30:00+09:00");
        assertThat(info.get("memberCount").asInt()).isEqualTo(2);
        assertThat(info.has("selectedStation")).isFalse();

        JsonNode list = run(asker, "list_members", "{}");
        assertThat(list.get("members")).hasSize(2);
        assertThat(list.get("membersWithoutDeparture")).isEmpty();
        depart(friend, null);
        assertThat(run(asker, "list_members", "{}").get("membersWithoutDeparture").get(0).asString()).isEqualTo("민수");
    }

    @Test void aNonMemberGetsNothingFromAnyTool() {
        for (String tool : List.of("get_room_info", "list_members", "get_latest_recommendation", "get_departure_plan", "get_route",
                "compare_members_travel")) {
            JsonNode result = run(outsider, tool, "{\"destinationStation\":\"사당\"}");
            assertThat(result.get("ok").asBoolean()).as(tool).isFalse();
            assertThat(result.get("code").asString()).as(tool).isEqualTo("NO_DATA");
            assertThat(result.toString()).as(tool).doesNotContain("점심약속", "민지", "민수");
        }
    }

    @Test void theDepartureTimeIsTheTimetableTripRecommendationsUse() {
        var expected = timetable.latestDepartures(sadang, APPOINTMENT, Set.of(gangnam), 0).get(gangnam);
        JsonNode plan = run(asker, "get_departure_plan", "{\"destinationStation\":\"사당역\"}");
        assertThat(plan.get("ok").asBoolean()).as(plan.toString()).isTrue();
        assertThat(Instant.parse(plan.get("leaveAt").asString())).isEqualTo(expected.leaveAt().truncatedTo(java.time.temporal.ChronoUnit.SECONDS));
        assertThat(Instant.parse(plan.get("arriveAt").asString())).isEqualTo(expected.arriveAt().truncatedTo(java.time.temporal.ChronoUnit.SECONDS))
                .isBeforeOrEqualTo(APPOINTMENT);
        assertThat(plan.get("firstTrain").get("line").asString()).isEqualTo(expected.legs().get(0).lineName());
        assertThat(Instant.parse(plan.get("firstTrain").get("boardAt").asString()))
                .isEqualTo(expected.legs().get(0).boardAt().truncatedTo(java.time.temporal.ChronoUnit.SECONDS));
        assertThat(plan.get("transferCount").asInt()).isEqualTo(expected.path().transferCount());

        // another member of the room by nickname; a nickname that is not in this room is refused
        JsonNode friendPlan = run(asker, "get_departure_plan", "{\"destinationStation\":\"사당\",\"member\":\"민수\"}");
        assertThat(friendPlan.get("member").asString()).isEqualTo("민수");
        assertThat(run(asker, "get_departure_plan", "{\"destinationStation\":\"사당\",\"member\":\"외부인\"}").get("code").asString())
                .isEqualTo("MEMBER_NOT_FOUND");
        assertThat(run(asker, "get_departure_plan", "{\"destinationStation\":\"없는역이름\"}").get("code").asString()).isEqualTo("STATION_NOT_FOUND");
    }

    @Test void routeAndCompareUseTheRoomAppointmentRoutes() {
        JsonNode route = run(asker, "get_route", "{\"destinationStation\":\"사당\"}");
        assertThat(route.get("ok").asBoolean()).as(route.toString()).isTrue();
        assertThat(route.get("originStation").asString()).isEqualTo("강남");   // the asker's departure station
        assertThat(route.get("routeSource").asString()).isEqualTo("INTERNAL");
        assertThat(route.get("steps")).isNotEmpty();
        assertThat(route.get("arrivalAt").asString()).isNotBlank();

        JsonNode compare = run(asker, "compare_members_travel", "{\"destinationStation\":\"사당\"}");
        assertThat(compare.get("ok").asBoolean()).as(compare.toString()).isTrue();
        assertThat(compare.get("members")).hasSize(2);
        assertThat(compare.get("longestMember").asString()).isIn("민지", "민수");
    }

    @Test void searchStationsAndTheNameRules() {
        JsonNode found = run(asker, "search_stations", "{\"query\":\"강남\"}");
        assertThat(found.get("ok").asBoolean()).isTrue();
        assertThat(found.get("stations").size()).isBetween(1, 5);
        assertThat(found.get("stations").get(0).get("name").asString()).isEqualTo("강남");
        assertThat(run(asker, "search_stations", "{\"query\":\"zzzz없는역\"}").get("code").asString()).isEqualTo("STATION_NOT_FOUND");
        // a name shared by several supported stations is ambiguous, whatever the tool
        List<String> shared = jdbc.queryForList("select name from stations where is_supported group by name having count(*) > 1 limit 1", String.class);
        for (String name : shared) {
            assertThat(run(asker, "get_route", "{\"destinationStation\":\"" + name + "\"}").get("code").asString()).isEqualTo("STATION_AMBIGUOUS");
        }
    }

    @Test void congestionOfAStationAndLine() {
        jdbc.update("delete from congestion_stats");
        JsonNode result = run(asker, "get_congestion",
                "{\"station\":\"강남\",\"line\":\"2호선\",\"direction\":\"INNER\",\"time\":\"08:10\",\"date\":\"2027-01-05\"}");
        // no congestion_stats rows in this test database: the tool reports NO_DATA instead of inventing a value
        assertThat(result.get("ok").asBoolean()).isFalse();
        assertThat(result.get("code").asString()).isEqualTo("NO_DATA");
        assertThat(run(asker, "get_congestion", "{\"station\":\"강남\",\"line\":\"2호선\",\"direction\":\"위\",\"time\":\"저녁\"}").get("code").asString())
                .isEqualTo("NO_DATA");
    }

    @Test void aRecommendationIsReadBackUntilTheRoomChangesThenItIsStaleWithoutTimes() {
        assertThat(run(asker, "get_latest_recommendation", "{}").get("code").asString()).isEqualTo("NO_RECOMMENDATION");
        recommendations.create(asker, room, null);

        JsonNode fresh = run(asker, "get_latest_recommendation", "{}");
        assertThat(fresh.get("ok").asBoolean()).as(fresh.toString()).isTrue();
        assertThat(fresh.get("isStale").asBoolean()).isFalse();
        JsonNode first = fresh.get("results").get(0);
        assertThat(first.get("station").asString()).isNotBlank();
        assertThat(first.get("members")).hasSize(2);
        assertThat(first.get("members").get(0).get("leaveAt").asString()).startsWith("2027-01-05T");
        assertThat(first.get("members").get(0).get("firstTrain").get("line").asString()).isNotBlank();

        jdbc.update("update meeting_rooms set appointment_at = ? where id = ?", java.sql.Timestamp.from(APPOINTMENT.plusSeconds(3600)), room);
        String stale = tools.forAsker(room, asker).run("get_latest_recommendation", "{}");
        assertThat(mapper.readTree(stale).get("code").asString()).isEqualTo("STALE");
        assertThat(stale).doesNotContain("2027-01-05T", "leaveAt", "firstTrain");
    }

    @Test void aChatQuestionIsAnsweredFromTheToolResultOfItsOwnRoom() throws Exception {
        // the scripted AI asks for the departure plan, then answers with what the tool returned
        doAnswer(call -> {
            ChatReplyRequest request = call.getArgument(0);
            JsonNode plan = mapper.readTree(request.tools().run("get_departure_plan", "{\"destinationStation\":\"사당\"}"));
            return new GeneratedText("강남에서 " + plan.get("leaveAt").asString() + "에 나서세요.");
        }).when(ai).replyToChat(any());
        String token = jwt.createAccessToken(asker);
        mvc.perform(post("/api/v1/rooms/" + room + "/messages").header("Authorization", "Bearer " + token).contentType("application/json")
                .content(mapper.writeValueAsString(Map.of("content", "@링크로 약속 시간까지 몇 시 열차 타야 돼?")))).andExpect(status().isCreated());

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        List<String> answers;
        while ((answers = jdbc.queryForList("select content from room_messages where room_id = ? and sender_type = 'AI'", String.class, room)).isEmpty()
                && System.nanoTime() < deadline) {
            Thread.sleep(50);
        }
        var expected = timetable.latestDepartures(sadang, APPOINTMENT, Set.of(gangnam), 0).get(gangnam);
        assertThat(answers).hasSize(1);
        assertThat(answers.get(0)).contains("2027-01-05T").contains(
                java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(
                        expected.leaveAt().truncatedTo(java.time.temporal.ChronoUnit.SECONDS).atZone(java.time.ZoneId.of("Asia/Seoul"))));
        assertThat(jdbc.queryForObject("select count(*) from room_messages where room_id = ? and sender_type = 'SYSTEM'", Integer.class, room)).isZero();
    }

    // ---- run_recommendation (#168) ----

    int runs() {
        return jdbc.queryForObject("select count(*) from recommendation_runs where room_id = ?", Integer.class, room);
    }

    @Test void runRecommendationSavesTheSameRunAs81AndItShowsInTheLatestAndByRunReads() {
        JsonNode out = run(asker, "run_recommendation", "{\"maxMinutes\":300,\"maxTransfers\":10}");
        assertThat(out.get("ok").asBoolean()).as(out.toString()).isTrue();
        assertThat(out.get("hasResults").asBoolean()).isTrue();
        assertThat(out.get("bestStation").get("station").asString()).isNotBlank();
        assertThat(out.get("appliedConstraints").get("maxMinutes").asInt()).isEqualTo(300);
        assertThat(out.get("appliedConstraints").get("maxTransfers").asInt()).isEqualTo(10);
        assertThat(runs()).isEqualTo(1);

        var latest = recommendations.latest(asker, room);
        assertThat(latest.runId()).isEqualTo(out.get("runId").asString());
        var byRun = recommendations.byRun(friend, room, UUID.fromString(out.get("runId").asString()));
        assertThat(byRun.appliedConstraints().maxMinutes()).isEqualTo(300);
        assertThat(byRun.appliedConstraints().maxTransfers()).isEqualTo(10);
        assertThat(byRun.conditionText()).isNull();
        assertThat(byRun.results()).isNotEmpty();
        assertThat(byRun.results().get(0).recommendedStation().name()).isNotBlank();
        // the tool's best station is the TIME result of the saved run
        var time = byRun.results().stream().filter(result -> "TIME".equals(result.criterion())).findFirst().orElseThrow();
        assertThat(out.get("bestStation").get("station").asString()).isEqualTo(time.recommendedStation().name());
        assertThat(out.get("bestStation").get("maxMinutes").asInt()).isEqualTo(time.metrics().maxMinutes());
    }

    @Test void withoutConditionsItRunsAnUnfilteredRunAndAnImpossibleOneSavesAnEmptyRunWithoutARunId() {
        JsonNode plain = run(asker, "run_recommendation", "{}");
        assertThat(plain.get("hasResults").asBoolean()).as(plain.toString()).isTrue();
        assertThat(recommendations.latest(asker, room).appliedConstraints().maxMinutes()).isNull();

        JsonNode none = run(asker, "run_recommendation", "{\"maxMinutes\":1}");
        assertThat(none.get("ok").asBoolean()).as(none.toString()).isTrue();
        assertThat(none.get("hasResults").asBoolean()).isFalse();
        assertThat(none.has("runId")).isFalse();
        assertThat(runs()).isEqualTo(2);   // 8.1 saves an empty run too; the answer just has no run to open
    }

    @Test void the81RulesApplyAndRefusedRunsSaveNothing() {
        depart(friend, null);
        JsonNode missing = run(asker, "run_recommendation", "{}");
        assertThat(missing.get("code").asString()).isEqualTo("DEPARTURE_MISSING");
        assertThat(missing.get("message").asString()).contains("민수");

        depart(friend, nowon);
        assertThat(run(asker, "run_recommendation", "{\"maxMinutes\":999}").get("code").asString()).isEqualTo("VALIDATION_ERROR");
        assertThat(run(outsider, "run_recommendation", "{}").get("code").asString()).isEqualTo("NO_DATA");
        jdbc.update("update meeting_rooms set appointment_at = ? where id = ?", java.sql.Timestamp.from(Instant.parse("2026-01-01T00:00:00Z")), room);
        assertThat(run(asker, "run_recommendation", "{}").get("code").asString()).isEqualTo("APPOINTMENT_PASSED");
        jdbc.update("update meeting_rooms set appointment_at = ? where id = ?", java.sql.Timestamp.from(APPOINTMENT), room);
        jdbc.update("delete from meeting_members where room_id = ? and user_id = ?", room, friend);
        assertThat(run(asker, "run_recommendation", "{}").get("code").asString()).isEqualTo("TOO_FEW_MEMBERS");
        assertThat(runs()).isZero();
    }

    String ask(String question) throws Exception {
        String token = jwt.createAccessToken(asker);
        mvc.perform(post("/api/v1/rooms/" + room + "/messages").header("Authorization", "Bearer " + token).contentType("application/json")
                .content(mapper.writeValueAsString(Map.of("content", question)))).andExpect(status().isCreated());
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (jdbc.queryForObject("select count(*) from room_messages where room_id = ? and sender_type <> 'USER'", Integer.class, room) == 0
                && System.nanoTime() < deadline) {
            Thread.sleep(50);
        }
        // GET /rooms/{roomId}/messages, newest first
        String page = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/rooms/" + room + "/messages")
                .header("Authorization", "Bearer " + token)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return page;
    }

    @Test void aChatQuestionThatRunsARecommendationAnswersWithTheRunIdInMetaOfTheMessageList() throws Exception {
        doAnswer(call -> {
            ChatReplyRequest request = call.getArgument(0);
            JsonNode result = mapper.readTree(request.tools().run("run_recommendation", "{\"maxTransfers\":10}"));
            return new GeneratedText("추천: " + result.get("bestStation").get("station").asString());
        }).when(ai).replyToChat(any());

        JsonNode newest = mapper.readTree(ask("@링크로 환승 10번 이하로 추천해줘")).get("data").get("items").get(0);

        assertThat(newest.get("senderType").asString()).isEqualTo("AI");
        assertThat(newest.get("meta").propertyNames()).containsExactlyInAnyOrder("type", "runId");
        assertThat(newest.get("meta").get("type").asString()).isEqualTo("RECOMMENDATION");
        String runId = newest.get("meta").get("runId").asString();
        assertThat(runId).isEqualTo(recommendations.latest(asker, room).runId());
        var run = recommendations.byRun(asker, room, UUID.fromString(runId));
        assertThat(run.appliedConstraints().maxTransfers()).isEqualTo(10);
        assertThat(runs()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from room_messages where room_id = ? and meta is not null", Integer.class, room)).isEqualTo(1);
    }

    @Test void anAnswerWithNoResultsAnErrorOrNoRunHasNullMeta() throws Exception {
        doAnswer(call -> {
            ChatReplyRequest request = call.getArgument(0);
            request.tools().run("run_recommendation", "{\"maxMinutes\":1}");
            return new GeneratedText("조건에 맞는 역이 없어요.");
        }).when(ai).replyToChat(any());
        JsonNode newest = mapper.readTree(ask("@링크로 1분 안쪽으로 추천해줘")).get("data").get("items").get(0);
        assertThat(newest.get("senderType").asString()).isEqualTo("AI");
        assertThat(newest.get("meta").isNull()).isTrue();

        jdbc.update("delete from room_messages where room_id = ?", room);
        depart(friend, null);
        newest = mapper.readTree(ask("@링크로 추천해줘")).get("data").get("items").get(0);
        assertThat(newest.get("senderType").asString()).isEqualTo("AI");
        assertThat(newest.get("meta").isNull()).isTrue();
    }
}
