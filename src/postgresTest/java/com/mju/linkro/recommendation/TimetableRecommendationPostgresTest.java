package com.mju.linkro.recommendation;

import com.mju.linkro.common.exception.BusinessException;
import com.mju.linkro.common.exception.ErrorCode;
import com.mju.linkro.recommendation.dto.RecommendationResponse;
import com.mju.linkro.recommendation.dto.RecommendationResponse.Leg;
import com.mju.linkro.recommendation.dto.RecommendationResponse.ParticipantTime;
import com.mju.linkro.recommendation.dto.RecommendationResponse.Result;
import com.mju.linkro.recommendation.service.Candidate;
import com.mju.linkro.recommendation.service.Candidate.Member;
import com.mju.linkro.recommendation.service.RecommendationEngine;
import com.mju.linkro.recommendation.service.RecommendationEngine.Evaluation;
import com.mju.linkro.recommendation.service.RecommendationService;
import com.mju.linkro.recommendation.dto.RecommendationResponse.AppliedConstraints;
import com.mju.linkro.subway.api.TimetableRouteFinder;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Recommendations on real trains (#96) with the V1~V15 seed and the bundled timetable: no member ever falls back to
 * fixed costs during service hours, every leg is a real train of the timetable file, and appointments no train can
 * make leave the stations out.
 */
@Testcontainers
@org.springframework.context.annotation.Import(FixedRecommendationClock.class)
@SpringBootTest(properties = {
        "spring.config.import=", "spring.jpa.hibernate.ddl-auto=validate", "spring.jpa.open-in-view=false",
        "spring.jpa.show-sql=false", "auth.kakao.app-id=1234", "auth.jwt.access-token-ttl=15m",
        "auth.jwt.secret=dGVzdC1vbmx5LXNlY3JldC0wMTIzNDU2Nzg5YWJjZGVmZ2hpams=",
        "linkro.subway.module=graph", "linkro.ai.module=mock"})
@Timeout(300)
class TimetableRecommendationPostgresTest {
    static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    static final LocalDate THURSDAY = LocalDate.of(2026, 11, 12);
    static final LocalDate SATURDAY = LocalDate.of(2026, 11, 14);

    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired RecommendationService service;
    @Autowired RecommendationEngine engine;
    @Autowired TimetableRouteFinder timetable;
    @Autowired JdbcTemplate jdbc;
    @Autowired com.mju.linkro.subway.api.StationFareLookup stationFares;
    @Autowired WebApplicationContext context;
    @Autowired JsonMapper mapper;

    /** (daytype label, line, train code, station) -> rows "arrive,depart,origin,terminal" of the bundled file. */
    static Map<String, List<int[]>> raw;
    static Map<String, List<String>> rawTerminals;

    @BeforeAll
    static void readRawTimetable() throws IOException {
        raw = new HashMap<>();
        rawTerminals = new HashMap<>();
        try (var in = TimetableRecommendationPostgresTest.class.getClassLoader().getResourceAsStream("train-timetable.csv.gz");
             var reader = new BufferedReader(new InputStreamReader(new GZIPInputStream(in), StandardCharsets.UTF_8))) {
            reader.readLine();
            String line;
            while ((line = reader.readLine()) != null) {
                String[] f = line.split(",");
                if (!"일반".equals(f[4])) continue;
                String key = String.join("|", f[2], f[0], f[5], f[1]);
                raw.computeIfAbsent(key, k -> new ArrayList<>()).add(new int[]{Integer.parseInt(f[6]), Integer.parseInt(f[7])});
                rawTerminals.computeIfAbsent(key, k -> new ArrayList<>()).add(f[9]);
            }
        }
    }

    @BeforeEach
    void clean() {
        jdbc.update("delete from recommendation_results");
        jdbc.update("delete from recommendation_runs");
        jdbc.update("delete from meeting_members");
        jdbc.update("delete from meeting_rooms");
        jdbc.update("delete from users");
    }

    long station(String name) {
        return jdbc.queryForObject("select id from stations where name = ?", Long.class, name);
    }

    /** A room with one member per departure station; the first member owns it. */
    UUID[] room(Instant appointment, String... departures) {
        UUID room = UUID.randomUUID();
        UUID[] users = new UUID[departures.length + 1];
        users[0] = room;
        for (int i = 0; i < departures.length; i++) {
            UUID user = UUID.randomUUID();
            jdbc.update("insert into users (id, kakao_id, nickname) values (?, ?, ?)", user, user.toString(), "멤버" + i);
            users[i + 1] = user;
        }
        jdbc.update("insert into meeting_rooms (id, room_code, name, created_by, appointment_at) values (?, ?, '약속', ?, ?)",
                room, room.toString().substring(0, 6).toUpperCase(), users[1], appointment == null ? null : java.sql.Timestamp.from(appointment));
        for (int i = 0; i < departures.length; i++) {
            jdbc.update("insert into meeting_members (id, room_id, user_id, role, departure_station_id) values (?, ?, ?, ?, ?)",
                    UUID.randomUUID(), room, users[i + 1], i == 0 ? "OWNER" : "MEMBER", station(departures[i]));
        }
        return users;
    }

    static Instant at(LocalDate date, int hour, int minute) {
        return date.atTime(hour, minute).atZone(SEOUL).toInstant();
    }

    static String dayLabel(LocalDate serviceDate) {
        return switch (serviceDate.getDayOfWeek()) {
            case SATURDAY -> "토요일";
            case SUNDAY -> "일요일·공휴일";
            default -> "평일";
        };
    }

    /** Seconds of the service day (03:00 boundary) for an ISO time of the response. */
    static int serviceSecond(String isoTime, LocalDate serviceDate) {
        return (int) (OffsetDateTime.parse(isoTime).toEpochSecond() - serviceDate.atStartOfDay(SEOUL).toEpochSecond());
    }

    /** Every leg is a train of the file: it leaves its first station and reaches its last one at the stated times. */
    static void assertLegsMatchTheFile(List<Leg> legs, LocalDate serviceDate) {
        for (Leg leg : legs) {
            String day = dayLabel(serviceDate);
            String from = String.join("|", day, leg.lineName(), leg.trainCode(), leg.fromStationName());
            String to = String.join("|", day, leg.lineName(), leg.trainCode(), leg.toStationName());
            int board = serviceSecond(leg.boardAt(), serviceDate);
            int alight = serviceSecond(leg.alightAt(), serviceDate);
            assertThat(raw.get(from)).as("rows of %s", from).isNotNull().anySatisfy(row -> assertThat(row[1]).isEqualTo(board));
            assertThat(raw.get(to)).as("rows of %s", to).isNotNull()
                    .anySatisfy(row -> assertThat(row[0] > 0 ? row[0] : row[1]).isEqualTo(alight));
            assertThat(rawTerminals.get(from)).contains(leg.terminal());
        }
    }

    @Test
    void serviceHoursNeverFallBack() {
        List<String[]> groups = List.of(new String[]{"강남", "홍대입구"}, new String[]{"노원", "사당"},
                new String[]{"까치산", "마천", "응암"}, new String[]{"서울역", "잠실", "불암산"});
        List<Instant> times = List.of(at(THURSDAY, 8, 0), at(THURSDAY, 13, 0), at(THURSDAY, 22, 0), at(SATURDAY, 14, 0));
        for (Instant time : times) {
            LocalDate serviceDate = time.atZone(SEOUL).toLocalDate();
            for (String[] group : groups) {
                UUID[] users = room(time, group);
                RecommendationResponse response = service.create(users[1], users[0], null);
                assertThat(response.results()).as("%s at %s", Arrays.toString(group), time).isNotEmpty();
                for (Result result : response.results()) {
                    for (ParticipantTime trip : result.participantTimes()) {
                        assertThat(trip.source()).isEqualTo("TIMETABLE");
                        assertThat(trip.boardingTrain()).isNotNull();
                        assertThat(trip.legs()).isNotEmpty();
                        // No station access time is configured: leave when the first train leaves.
                        assertThat(trip.departureAt()).isEqualTo(trip.boardingTrain().departureAt());
                        assertThat(trip.legs().get(0).trainCode()).isEqualTo(trip.boardingTrain().trainCode());
                        // Arrival by the appointment; travel = arrival − departure, the rest is slack.
                        Instant leave = OffsetDateTime.parse(trip.departureAt()).toInstant();
                        Instant arrive = OffsetDateTime.parse(trip.arriveAt()).toInstant();
                        assertThat(arrive).isEqualTo(OffsetDateTime.parse(trip.legs().get(trip.legs().size() - 1).alightAt()).toInstant())
                                .isBeforeOrEqualTo(time);
                        assertThat(trip.travelMinutes()).isEqualTo((int) ((arrive.getEpochSecond() - leave.getEpochSecond() + 59) / 60));
                        assertThat(trip.slackSeconds()).isEqualTo((int) (time.getEpochSecond() - arrive.getEpochSecond()));
                        assertLegsMatchTheFile(trip.legs(), serviceDate);
                    }
                }
                List<Member> members = new ArrayList<>();
                for (int i = 0; i < group.length; i++) members.add(new Member(users[i + 1], "멤버" + i, station(group[i])));
                Evaluation evaluation = engine.evaluate(members, AppliedConstraints.none(), time, Instant.now());
                assertThat(evaluation.source()).isEqualTo(RecommendationEngine.TIMETABLE);
                assertThat(evaluation.fallbackMembers()).isZero();
            }
        }
    }

    @Test
    void storedRunsReadBackTheTrainFields() {
        UUID[] users = room(at(THURSDAY, 19, 0), "강남", "사당");
        RecommendationResponse created = service.create(users[1], users[0], null);
        assertThat(service.latest(users[1], users[0])).isEqualTo(created);
        assertThat(jdbc.queryForObject("select count(*) from recommendation_results, jsonb_array_elements(trips) trip "
                + "where trip ->> 'source' = 'TIMETABLE' and jsonb_array_length(trip -> 'legs') > 0 and trip ? 'boardingTrain'", Integer.class))
                .isEqualTo(created.results().size() * 2);
    }

    @Test
    void withoutAnAppointmentMembersTakeTheNextTrains() {
        UUID[] users = room(null, "강남", "사당");
        RecommendationResponse response = service.create(users[1], users[0], null);
        assertThat(response.results()).isNotEmpty().allSatisfy(result -> assertThat(result.participantTimes())
                .allSatisfy(trip -> assertThat(trip.source()).isEqualTo("TIMETABLE")));
    }

    @Test
    void afterTheLastTrainMembersArriveOnTheLastTrainsAndWait() {
        // Friday 01:30 still belongs to Thursday's service day: members take trains that arrive before the last ones
        // stop (about 01:15) and wait; the wait is slack, not travel. Nothing falls back.
        Instant late = at(THURSDAY.plusDays(1), 1, 30);
        List<Member> members = List.of(new Member(UUID.randomUUID(), "a", station("강남")), new Member(UUID.randomUUID(), "b", station("노원")));
        Evaluation evaluation = engine.evaluate(members, AppliedConstraints.none(), late, Instant.now());
        assertThat(evaluation.source()).isEqualTo(RecommendationEngine.TIMETABLE);
        assertThat(evaluation.fallbackMembers()).isZero();
        assertThat(evaluation.candidates()).isNotEmpty();
        for (Candidate candidate : evaluation.candidates().values()) {
            assertThat(candidate.trips()).allSatisfy(trip -> {
                assertThat(trip.timed().arriveAt()).isBeforeOrEqualTo(at(THURSDAY.plusDays(1), 1, 15));
                // Minutes are the ride itself, not the wait until 01:30.
                assertThat(trip.minutes()).isEqualTo((int) ((trip.timed().arriveAt().getEpochSecond() - trip.timed().leaveAt().getEpochSecond() + 59) / 60));
            });
        }
    }

    @Test
    void noTrainArrivingInTheHourBeforeIsRefusedWithoutARun() {
        // Last arrivals: weekday nights 25:14 (2호선 도림천), Saturday nights 24:18:30. No-service window: 60 minutes.
        LocalDate friday = THURSDAY.plusDays(1);
        LocalDate sunday = SATURDAY.plusDays(1);
        Map<Instant, Boolean> refused = new LinkedHashMap<>();
        refused.put(at(friday, 1, 30), false);  // 00:30~01:30 has Thursday's last trains
        refused.put(at(friday, 2, 30), true);
        refused.put(at(friday, 4, 0), true);
        refused.put(at(friday, 5, 30), false);  // Friday's first trains arrive from about 05:13
        refused.put(at(sunday, 1, 0), false);   // 00:00~01:00 has Saturday's last train (00:18:30)
        refused.put(at(sunday, 4, 0), true);
        refused.forEach((appointment, expected) -> {
            UUID[] users = room(appointment, "강남", "사당");
            if (expected) {
                assertThatThrownBy(() -> service.create(users[1], users[0], null)).as("%s", appointment)
                        .isInstanceOfSatisfying(BusinessException.class, exception -> {
                            assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.NO_SERVICE_TIME);
                            assertThat(exception.getErrorCode().getStatus().value()).isEqualTo(422);
                            assertThat(exception.getErrorCode().getMessage()).isEqualTo("해당 시간대에는 지하철이 운행하지 않습니다. 약속 시간을 바꿔 주세요.");
                        });
                assertThat(jdbc.queryForObject("select count(*) from recommendation_runs where room_id = ?", Integer.class, users[0]))
                        .as("%s", appointment).isZero();
            } else {
                assertThat(service.create(users[1], users[0], null).runId()).as("%s", appointment).isNotNull();
            }
        });
    }

    @Test
    void justAfterTheFirstTrainsTheEmptyResultSaysSo() {
        // #115: 강남·노원 at 05:29~05:45 (first trains arrive from about 05:13): no station is reachable, every one because of
        // the first trains. Weekday, Saturday and Sunday service days.
        String firstTrain = "첫차 시각이 가까워 약속 시각까지 도착할 수 있는 역이 없습니다. 약속 시간을 늦춰 주세요.";
        for (LocalDate date : List.of(THURSDAY.plusDays(1), SATURDAY, SATURDAY.plusDays(1))) {
            for (int[] time : new int[][]{{5, 29}, {5, 30}, {5, 45}}) {
                Instant appointment = at(date, time[0], time[1]);
                UUID[] users = room(appointment, "강남", "노원");
                RecommendationResponse response = service.create(users[1], users[0], null);
                assertThat(response.results()).as("%s", appointment).isEmpty();
                assertThat(response.message()).as("%s", appointment).isEqualTo(firstTrain);
                // GET builds it again from the stored criteria.
                assertThat(service.latest(users[1], users[0]).message()).as("%s", appointment).isEqualTo(firstTrain);
            }
        }
        // Other empty results keep their message: at 06:30 some stations are left out by the trains, the rest by 1 minute.
        UUID[] users = room(at(THURSDAY, 6, 30), "강남", "노원");
        RecommendationResponse response = service.create(users[1], users[0],
                new com.mju.linkro.recommendation.dto.RecommendationRequest(null,
                        new com.mju.linkro.recommendation.dto.RecommendationRequest.Filter(1, null, null, null, null)));
        assertThat(response.results()).isEmpty();
        assertThat(response.message()).matches("약속 시각까지 열차로 도착할 수 없는 역 \\d+곳을 제외했습니다\\. 조건에 맞는 중간역이 없습니다\\. 조건을 완화해 보세요\\.");
    }

    @Test
    void aPastAppointmentIsRefusedBeforeTheNoServiceCheck() {
        // Tuesday 04:00 (a NO_SERVICE_TIME hour) is before the fixed "now" (Wednesday 09:00): APPOINTMENT_PASSED comes first.
        UUID[] users = room(at(THURSDAY.minusDays(2), 4, 0), "강남", "사당");
        assertThatThrownBy(() -> service.create(users[1], users[0], null)).isInstanceOfSatisfying(BusinessException.class, exception -> {
            assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.APPOINTMENT_PASSED);
            assertThat(exception.getErrorCode().getStatus().value()).isEqualTo(422);
        });
        assertThat(jdbc.queryForObject("select count(*) from recommendation_runs where room_id = ?", Integer.class, users[0])).isZero();
    }

    /** Short description of a route: "code from HH:mm:ss -> to HH:mm:ss (wait s)" per leg. */
    static List<String> legs(com.mju.linkro.subway.api.TimedRoute route) {
        var hms = java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss").withZone(SEOUL);
        return route.legs().stream().map(leg -> leg.trainCode() + " " + leg.fromStationName() + " " + hms.format(leg.boardAt())
                + " -> " + leg.toStationName() + " " + hms.format(leg.alightAt()) + " (" + leg.waitSec() + ")").toList();
    }

    @Test
    void eungamLoopToLoopNeedsTheChangeTime() {
        // #104: 6053 reaches 응암 at 08:02:30 and goes on to 새절; 6059 leaves for 역촌 at the same second. With the
        // 120 s change time the next loop train, 6061 at 08:06:30, is the first one.
        long gusan = station("구산");
        long yeokchon = station("역촌");
        var forward = timetable.departingAt(gusan, at(THURSDAY, 8, 0)).routeTo(yeokchon).orElseThrow();
        assertThat(legs(forward)).containsExactly("6053 구산 08:00:30 -> 응암 08:02:30 (0)", "6061 응암 08:06:30 -> 역촌 08:08:10 (240)");
        assertThat(forward.path().transferCount()).isZero();
        // Backward (by 09:00) already waited 210 s at 응암: unchanged.
        var backward = timetable.latestDepartures(yeokchon, at(THURSDAY, 9, 0), Set.of(gusan), 0).get(gusan);
        assertThat(legs(backward)).containsExactly("6077 구산 08:49:00 -> 응암 08:51:00 (0)", "6085 응암 08:54:30 -> 역촌 08:56:10 (210)");
    }

    @Test
    void forwardRoutesTakeTheSameTrainWithoutTheExtraTransfer() {
        // #107: the forward search alone took 3068K to 연신내 and walked 151 s to 6057 (1 transfer). 6057 also leaves 불광
        // and arrives at the same 08:16:10, so the route is 6057 straight from 불광.
        long bulgwang = station("불광");
        long dmc = station("디지털미디어시티");
        var forward = timetable.departingAt(bulgwang, at(THURSDAY, 8, 0)).routeTo(dmc).orElseThrow();
        assertThat(legs(forward)).containsExactly("6057 불광 08:02:30 -> 디지털미디어시티 08:16:10 (0)");
        assertThat(forward.path().transferCount()).isZero();
    }

    @Test
    void routesAwayFromTheEungamLoopChangeAreUnchanged() {
        // Same results as before #104 (checked on the same seed and timetable).
        long bulgwang = station("불광");
        long dmc = station("디지털미디어시티");
        var backward = timetable.latestDepartures(dmc, at(THURSDAY, 9, 0), Set.of(bulgwang), 0).get(bulgwang);
        assertThat(legs(backward)).containsExactly("6077 불광 08:42:30 -> 디지털미디어시티 08:56:10 (0)");
        // 새절 -> 응암 (응암행) -> 역촌 is main -> loop, not affected: still a 30 s wait.
        long saejeol = station("새절");
        var mainToLoop = timetable.latestDepartures(station("역촌"), at(THURSDAY, 9, 0), Set.of(saejeol), 0).get(saejeol);
        assertThat(legs(mainToLoop)).containsExactly("6072 새절 08:52:40 -> 응암 08:54:00 (0)", "6085 응암 08:54:30 -> 역촌 08:56:10 (30)");
    }

    @Test
    void faresComeFromTheFareTableByStationPair() {
        // #109: 불광 -> 합정 1,550원 and 노원 -> 사당 1,850원 whatever route is ridden (map app and fare table agree).
        long bulgwang = station("불광");
        long hapjeong = station("합정");
        long nowon = station("노원");
        long sadang = station("사당");
        assertThat(stationFares.fareBetween(bulgwang, hapjeong)).hasValue(1550);
        assertThat(stationFares.fareBetween(nowon, sadang)).hasValue(1850);
        List<Member> members = List.of(new Member(UUID.randomUUID(), "a", bulgwang), new Member(UUID.randomUUID(), "b", nowon));
        Evaluation evaluation = engine.evaluate(members, AppliedConstraints.none(), at(THURSDAY, 13, 0), Instant.now());
        assertThat(evaluation.candidates().get(hapjeong).trips().get(0).fareWon()).isEqualTo(1550);
        assertThat(evaluation.candidates().get(sadang).trips().get(1).fareWon()).isEqualTo(1850);
        // Every trip: the table fare for (departure, candidate); distance and legs stay the route's.
        evaluation.candidates().values().forEach(candidate -> candidate.trips().forEach(trip -> {
            assertThat(trip.fareWon()).isEqualTo(stationFares.fareBetween(trip.member().departureStationId(), candidate.stationId()).orElseThrow());
            assertThat(trip.path()).isSameAs(trip.timed().path());
        }));

        // All ordered pairs of the 240 supported stations are in the table (까치산 2호선 code 200 is not in the file, but
        // 까치산's 5호선 code covers the station).
        List<Long> ids = jdbc.queryForList("select id from stations where is_supported order by id", Long.class);
        assertThat(ids).hasSize(240);
        long missing = ids.stream().flatMap(from -> ids.stream().filter(to -> !to.equals(from))
                .filter(to -> stationFares.fareBetween(from, to).isEmpty())).count();
        assertThat(missing).isZero();
        // Pairs with several code combinations keep the lowest fare.
        assertThat(stationFares.fareBetween(station("남태령"), station("삼각지"))).hasValue(1550);
        assertThat(stationFares.fareBetween(station("종로3가"), station("연신내"))).hasValue(1550);
        assertThat(stationFares.fareBetween(station("청량리"), station("연신내"))).hasValue(1650);
    }

    @Test
    void openApiDocumentsTheTrainFields() throws Exception {
        var mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        JsonNode schemas = mapper.readTree(mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8)).get("components").get("schemas");
        JsonNode trip = schemas.get("RecommendationParticipantTime").get("properties");
        assertThat(trip.has("departureAt") && trip.has("segments") && trip.has("travelMinutes")).isTrue();
        assertThat(trip.has("boardingTrain") && trip.has("legs") && trip.has("source")).isTrue();
        assertThat(trip.has("arriveAt") && trip.has("slackSeconds")).isTrue();
        assertThat(schemas.get("RecommendationBoardingTrain").get("properties").has("trainCode")).isTrue();
        assertThat(schemas.get("RecommendationLeg").get("properties").has("transferWaitSec")).isTrue();
    }
}
