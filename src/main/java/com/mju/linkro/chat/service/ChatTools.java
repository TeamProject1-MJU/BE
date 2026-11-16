package com.mju.linkro.chat.service;

import static com.mju.linkro.chat.service.ChatToolSpecs.*;

import com.mju.linkro.ai.api.ChatToolSpec;
import com.mju.linkro.ai.api.ChatToolbox;
import com.mju.linkro.chat.ChatProperties;
import com.mju.linkro.common.exception.BusinessException;
import com.mju.linkro.common.exception.ErrorCode;
import com.mju.linkro.recommendation.dto.RecommendationResponse;
import com.mju.linkro.recommendation.repository.RecommendationRepository;
import com.mju.linkro.recommendation.service.ConstraintResolver;
import com.mju.linkro.recommendation.service.RecommendationService;
import com.mju.linkro.room.domain.MeetingRoom;
import com.mju.linkro.room.repository.RoomReadRepository;
import com.mju.linkro.room.repository.RoomReadRepository.MemberRow;
import com.mju.linkro.room.service.RoomAccess;
import com.mju.linkro.route.dto.RouteSearchRequest;
import com.mju.linkro.route.dto.RouteSearchResponse;
import com.mju.linkro.route.service.RouteSearchService;
import com.mju.linkro.subway.api.TimedLeg;
import com.mju.linkro.subway.api.TimedRoute;
import com.mju.linkro.subway.api.TimetableRouteFinder;
import com.mju.linkro.subway.congestion.dto.CongestionQuery;
import com.mju.linkro.subway.congestion.dto.CongestionResponse;
import com.mju.linkro.subway.congestion.service.CongestionQueryService;
import com.mju.linkro.subway.station.dto.StationResponses;
import com.mju.linkro.subway.station.service.StationQueryService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The read-only tools of an @링크로 answer (#129), run on the services the app already has: room and member reads,
 * {@link RecommendationService#latest}, {@link RouteSearchService#search}, {@link TimetableRouteFinder#latestDepartures},
 * {@link CongestionQueryService} and {@link StationQueryService}. {@link #forAsker} fixes the room and the asking user;
 * neither is ever a tool argument, and every call checks again that the user is still a member.
 *
 * <p>Every call runs on {@code chatToolExecutor} with a timeout and holds no transaction of its own (each service opens
 * its own short one). A failure is a reason code in the result, never an exception. The log has the tool name, the
 * duration and the result code only: no question text, nickname, station name or argument.
 */
@Slf4j
@Component
public class ChatTools {
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_OFFSET_DATE_TIME;
    private static final Set<String> TOOLS = Set.of(GET_ROOM_INFO, LIST_MEMBERS, GET_LATEST_RECOMMENDATION, GET_DEPARTURE_PLAN,
            GET_ROUTE, GET_CONGESTION, SEARCH_STATIONS, COMPARE_MEMBERS_TRAVEL);

    private final RoomAccess access;
    private final RoomReadRepository roomReads;
    private final RecommendationRepository recommendationRepository;
    private final RecommendationService recommendations;
    private final RouteSearchService routes;
    private final ObjectProvider<TimetableRouteFinder> timetable;
    private final CongestionQueryService congestion;
    private final StationQueryService stationQuery;
    private final JsonMapper json;
    private final ThreadPoolExecutor executor;
    private final ChatProperties properties;
    private final Clock clock;

    @Autowired
    ChatTools(RoomAccess access, RoomReadRepository roomReads, RecommendationRepository recommendationRepository,
              RecommendationService recommendations, RouteSearchService routes, ObjectProvider<TimetableRouteFinder> timetable,
              CongestionQueryService congestion, StationQueryService stationQuery, JsonMapper json,
              @Qualifier("chatToolExecutor") ThreadPoolExecutor executor, ChatProperties properties, ObjectProvider<Clock> clock) {
        this(access, roomReads, recommendationRepository, recommendations, routes, timetable, congestion, stationQuery, json,
                executor, properties, clock.getIfAvailable(Clock::systemUTC));
    }

    ChatTools(RoomAccess access, RoomReadRepository roomReads, RecommendationRepository recommendationRepository,
              RecommendationService recommendations, RouteSearchService routes, ObjectProvider<TimetableRouteFinder> timetable,
              CongestionQueryService congestion, StationQueryService stationQuery, JsonMapper json, ThreadPoolExecutor executor,
              ChatProperties properties, Clock clock) {
        this.access = access;
        this.roomReads = roomReads;
        this.recommendationRepository = recommendationRepository;
        this.recommendations = recommendations;
        this.routes = routes;
        this.timetable = timetable;
        this.congestion = congestion;
        this.stationQuery = stationQuery;
        this.json = json;
        this.executor = executor;
        this.properties = properties;
        this.clock = clock;
    }

    /** The tools of one question: only this room, only this user. */
    public ChatToolbox forAsker(UUID roomId, UUID userId) {
        return new Asker(roomId, userId);
    }

    /** A reason code ({@code ChatToolSpecs} lists them in the descriptions); {@code message} only helps the AI word it. */
    private static final class Fail extends RuntimeException {
        final String code;

        Fail(String code, String message) {
            super(message, null, false, false);
            this.code = code;
        }

        Fail(String code) {
            this(code, null);
        }
    }

    private final class Asker implements ChatToolbox {
        private final UUID roomId;
        private final UUID userId;
        private Map<String, List<Long>> stationIdsByName;

        Asker(UUID roomId, UUID userId) {
            this.roomId = roomId;
            this.userId = userId;
        }

        @Override
        public List<ChatToolSpec> specs() {
            return ChatToolSpecs.all();
        }

        @Override
        public String run(String toolName, String argumentsJson) {
            long started = System.nanoTime();
            Map<String, Object> result;
            String logged = TOOLS.contains(toolName) ? toolName : "unknown";
            Future<Map<String, Object>> future = null;
            try {
                if (!TOOLS.contains(toolName)) throw new Fail("NO_DATA", "알 수 없는 도구예요.");
                JsonNode args = arguments(argumentsJson);
                future = executor.submit(() -> execute(toolName, args));
                result = future.get(properties.getAiToolTimeout().toMillis(), TimeUnit.MILLISECONDS);
            } catch (TimeoutException timeout) {
                future.cancel(true);
                result = failure("NO_DATA", "시간이 너무 오래 걸렸어요.");
            } catch (ExecutionException failed) {
                result = failure(failed.getCause());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                if (future != null) future.cancel(true);
                result = failure("NO_DATA", null);
            } catch (RejectedExecutionException busy) {
                result = failure("NO_DATA", "지금은 조회할 수 없어요.");
            } catch (RuntimeException failed) {
                result = failure(failed);
            }
            String code = result.get("ok") == Boolean.TRUE ? "OK" : String.valueOf(result.get("code"));
            log.info("chat tool {} {} {}ms", logged, code, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
            return json.writeValueAsString(result);
        }

        private JsonNode arguments(String argumentsJson) {
            try {
                JsonNode node = json.readTree(argumentsJson == null || argumentsJson.isBlank() ? "{}" : argumentsJson);
                return node.isObject() ? node : json.createObjectNode();
            } catch (RuntimeException invalid) {
                return json.createObjectNode();
            }
        }

        private Map<String, Object> execute(String tool, JsonNode args) {
            MeetingRoom room = access.requireMember(roomId, userId);
            return switch (tool) {
                case GET_ROOM_INFO -> roomInfo(room);
                case LIST_MEMBERS -> listMembers();
                case GET_LATEST_RECOMMENDATION -> latestRecommendation();
                case GET_DEPARTURE_PLAN -> departurePlan(room, args);
                case GET_ROUTE -> route(args);
                case GET_CONGESTION -> congestion(args);
                case SEARCH_STATIONS -> searchStations(args);
                default -> compareMembers(args);
            };
        }

        // ---- get_room_info, list_members ----

        private Map<String, Object> roomInfo(MeetingRoom room) {
            List<MemberRow> members = members();
            return ok("roomName", room.getName(), "appointmentAt", iso(room.getAppointmentAt()),
                    "selectedStation", room.getSelectedStationId() == null ? null : stationName(room.getSelectedStationId()),
                    "memberCount", members.size());
        }

        private Map<String, Object> listMembers() {
            List<MemberRow> members = members();
            Map<Long, String> names = stationNames(members.stream().map(MemberRow::stationId).toList());
            List<Object> list = new ArrayList<>();
            List<String> missing = new ArrayList<>();
            for (MemberRow member : members) {
                list.add(map("nickname", member.nickname(), "role", member.role().name(),
                        "departureStation", member.stationId() == null ? null : names.get(member.stationId())));
                if (member.stationId() == null) missing.add(member.nickname());
            }
            return ok("members", list, "membersWithoutDeparture", missing);
        }

        // ---- get_latest_recommendation ----

        private Map<String, Object> latestRecommendation() {
            RecommendationResponse run = recommendations.latest(userId, roomId);
            if (run == null) throw new Fail("NO_RECOMMENDATION");
            // An old run's times are never handed out (rule 5): the AI only learns that it is out of date.
            if (Boolean.TRUE.equals(run.isStale())) {
                throw new Fail("STALE", "추천 이후 약속·멤버가 바뀌었어요. 다시 추천받아야 해요. 이유: " + run.staleReasons());
            }
            if (run.results().isEmpty()) throw new Fail("NO_RECOMMENDATION", "최근 추천에 조건에 맞는 중간역이 없었어요.");
            // Not being stale does not make the stored times current: they are flagged against the clock, like get_departure_plan.
            Instant now = clock.instant();
            boolean anyPassed = false;
            List<Object> results = new ArrayList<>();
            for (RecommendationResponse.Result result : run.results()) {
                List<Object> members = new ArrayList<>();
                for (RecommendationResponse.ParticipantTime time : result.participantTimes()) {
                    var train = time.boardingTrain();
                    boolean leaveAtPassed = passed(time.departureAt(), now);
                    boolean boardAtPassed = train != null && passed(train.departureAt(), now);
                    anyPassed |= leaveAtPassed || boardAtPassed;
                    members.add(map("nickname", time.nickname(), "departureStation", time.departureStation().name(),
                            "leaveAt", time.departureAt(), "leaveAtPassed", leaveAtPassed, "boardAtPassed", boardAtPassed,
                            "arriveAt", time.arriveAt(),
                            "firstTrain", train == null ? null : map("line", train.lineName(), "station", train.stationName(),
                                    "direction", train.direction(), "terminal", train.terminal(), "boardAt", train.departureAt()),
                            "slackMinutes", time.slackSeconds() == null ? null : Math.max(0, time.slackSeconds()) / 60,
                            "travelMinutes", time.travelMinutes(), "transferCount", time.transferCount(), "fareWon", time.fareWon()));
                }
                var metrics = result.metrics();
                results.add(map("criterion", result.criterion(), "station", result.recommendedStation().name(),
                        "averageMinutes", metrics.averageMinutes(), "maxMinutes", metrics.maxMinutes(),
                        "averageFare", metrics.averageFare(), "totalTransfers", metrics.totalTransfers(), "members", members));
            }
            return ok("isStale", false, "departuresPassed", anyPassed, "createdAt", run.createdAt(), "results", results);
        }

        // ---- get_departure_plan ----

        private Map<String, Object> departurePlan(MeetingRoom room, JsonNode args) {
            Instant appointment = room.getAppointmentAt();
            if (appointment == null) throw new Fail("NO_APPOINTMENT");
            Instant now = clock.instant();
            if (appointment.truncatedTo(ChronoUnit.SECONDS).isBefore(now.truncatedTo(ChronoUnit.SECONDS))) {
                throw new Fail("APPOINTMENT_PASSED");
            }
            long destination = station(text(args, "destinationStation"));
            List<MemberRow> members = members();
            MemberRow member = member(members, text(args, "member"));
            if (member.stationId() == null) throw new Fail("DEPARTURE_MISSING");
            if (member.stationId() == destination) throw new Fail("NO_ROUTE", "출발역과 약속역이 같아요.");
            TimetableRouteFinder finder = timetable.getIfAvailable();
            if (finder == null) throw new Fail("NO_DATA", "열차 시각표를 쓸 수 없어요.");
            // The call recommendations use for a member's trip: leave as late as possible and arrive by the appointment.
            TimedRoute timed = finder.latestDepartures(destination, appointment, Set.of(member.stationId()), 0).get(member.stationId());
            if (timed == null) throw new Fail("NO_SERVICE", "약속 시각까지 도착하는 열차가 없어요.");
            TimedLeg first = timed.legs().get(0);
            return ok("member", member.nickname(), "destinationStation", stationName(destination),
                    "leaveAt", iso(timed.leaveAt()), "leaveAtPassed", timed.leaveAt().isBefore(now),
                    "firstTrain", map("line", first.lineName(), "station", first.fromStationName(),
                            "direction", first.direction().name(), "terminal", first.terminal(), "boardAt", iso(first.boardAt())),
                    "arriveAt", iso(timed.arriveAt()), "appointmentAt", iso(appointment),
                    "transferCount", timed.path().transferCount(),
                    "travelMinutes", minutes(ChronoUnit.SECONDS.between(timed.leaveAt(), timed.arriveAt())),
                    "slackMinutes", Math.max(0, ChronoUnit.SECONDS.between(timed.arriveAt(), appointment)) / 60);
        }

        // ---- get_route, compare_members_travel ----

        private Map<String, Object> route(JsonNode args) {
            long destination = station(text(args, "destinationStation"));
            String originName = text(args, "originStation");
            long origin = originName != null ? station(originName) : askerStation();
            RouteSearchResponse found = routes.search(userId, new RouteSearchRequest(origin, destination, null, roomId.toString()));
            if (found.routes().isEmpty()) throw new Fail("NO_ROUTE");
            RouteSearchResponse.Route route = found.routes().get(0);
            var summary = route.summary();
            List<Object> steps = new ArrayList<>();
            for (RouteSearchResponse.Step step : route.steps()) {
                var stops = step.stops();
                steps.add(map("type", step.type(), "line", step.line() == null ? null : step.line().name(),
                        "from", stops.isEmpty() ? null : stops.get(0).name(), "to", stops.isEmpty() ? null : stops.get(stops.size() - 1).name(),
                        "minutes", minutes(step.durationSec())));
            }
            return ok("routeSource", found.routeSource().name(), "originStation", stationName(origin),
                    "destinationStation", stationName(destination), "totalTimeSec", summary.totalTimeSec(),
                    "travelMinutes", minutes(summary.totalTimeSec()),
                    "waitMinutes", summary.waitSec() == null ? null : minutes(summary.waitSec()),
                    "totalWithWaitMinutes", summary.totalWithWaitSec() == null ? null : minutes(summary.totalWithWaitSec()),
                    "fare", summary.fare(), "fareSource", summary.fareSource().name(),
                    "transferCount", summary.transferCount(), "departureAt", summary.departureAt(), "arrivalAt", summary.arrivalAt(),
                    "noService", summary.noService(), "serviceNotice", summary.serviceNotice(),
                    "appointmentPassed", found.appointmentPassed(), "arrivesAfterAppointment", summary.arrivesAfterAppointment(),
                    "steps", steps);
        }

        private Map<String, Object> compareMembers(JsonNode args) {
            long destination = station(text(args, "destinationStation"));
            List<MemberRow> members = members();
            Map<Long, String> names = stationNames(members.stream().map(MemberRow::stationId).toList());
            List<Object> rows = new ArrayList<>();
            List<String> missing = new ArrayList<>();
            String longest = null;
            int longestSec = -1;
            for (MemberRow member : members) {
                if (member.stationId() == null) {
                    missing.add(member.nickname());
                    continue;
                }
                try {
                    var found = routes.search(userId, new RouteSearchRequest(member.stationId(), destination, null, roomId.toString()));
                    if (found.routes().isEmpty()) throw new Fail("NO_ROUTE");
                    var summary = found.routes().get(0).summary();
                    rows.add(map("nickname", member.nickname(), "departureStation", names.get(member.stationId()),
                            "totalMinutes", minutes(summary.totalTimeSec()), "fare", summary.fare(),
                            "fareSource", summary.fareSource().name(), "transferCount", summary.transferCount(),
                            "routeSource", found.routeSource().name()));
                    if (summary.totalTimeSec() > longestSec) {
                        longestSec = summary.totalTimeSec();
                        longest = member.nickname();
                    }
                } catch (Fail | BusinessException failed) {
                    rows.add(map("nickname", member.nickname(), "departureStation", names.get(member.stationId()),
                            "code", failure(failed).get("code")));
                }
            }
            if (rows.isEmpty()) throw new Fail("DEPARTURE_MISSING");
            return ok("destinationStation", stationName(destination), "members", rows, "membersWithoutDeparture", missing,
                    "longestMember", longest);
        }

        // ---- get_congestion, search_stations ----

        private Map<String, Object> congestion(JsonNode args) {
            long station = station(text(args, "station"));
            String lineName = text(args, "line");
            StationResponses.Line line = lineName == null ? null : stationQuery.lines().stream()
                    .filter(candidate -> lineName.equals(candidate.name()) || lineName.equals(candidate.lineCode())
                            || (lineName + "호선").equals(candidate.name()))
                    .findFirst().orElse(null);
            if (line == null) throw new Fail("NO_DATA", "알 수 없는 노선이에요.");
            String date = text(args, "date");
            CongestionResponse found;
            try {
                found = ChatTools.this.congestion.congestion(CongestionQuery.of(Long.toString(station), Long.toString(line.id()),
                        text(args, "direction"), null, text(args, "time"),
                        date != null ? date : LocalDate.ofInstant(clock.instant(), SEOUL).toString()));
            } catch (BusinessException invalid) {
                if (invalid.getErrorCode() == ErrorCode.VALIDATION_ERROR) {
                    throw new Fail("NO_DATA", "direction은 UP/DOWN/INNER/OUTER/LOOP, time은 HH:mm, date는 yyyy-MM-dd여야 해요.");
                }
                throw invalid;
            }
            if (!found.supported()) throw new Fail("NO_DATA");
            return ok("station", stationName(station), "line", line.name(), "direction", found.direction().name(),
                    "slotStart", found.slotStart(), "slotDate", found.slotDate(), "firstTrain", found.firstTrain(),
                    "pct", found.pct(), "level", found.level(), "levelLabel", found.levelLabel(), "source", found.source().name());
        }

        private Map<String, Object> searchStations(JsonNode args) {
            String query = text(args, "query");
            if (query == null) throw new Fail("STATION_NOT_FOUND");
            List<StationResponses.Station> found;
            try {
                found = stationQuery.search(query, null, null, 0, 5).items();
            } catch (BusinessException invalid) {
                throw new Fail("STATION_NOT_FOUND");
            }
            if (found.isEmpty()) throw new Fail("STATION_NOT_FOUND");
            List<Object> stations = new ArrayList<>();
            for (StationResponses.Station station : found) {
                stations.add(map("name", station.name(), "isSupported", station.isSupported(),
                        "lines", station.lines().stream().map(StationResponses.StationLine::lineName).toList()));
            }
            return ok("stations", stations);
        }

        // ---- shared ----

        /** The supported station for a name, by the rule of {@link ConstraintResolver}: a trailing 역 is ignored. */
        private long station(String name) {
            if (name == null) throw new Fail("STATION_NOT_FOUND");
            if (stationIdsByName == null) stationIdsByName = recommendationRepository.supportedStationIdsByName();
            List<Long> ids = ConstraintResolver.stationIds(stationIdsByName, name);
            if (ids.size() > 1) throw new Fail("STATION_AMBIGUOUS", "같은 이름의 역이 여럿이에요. search_stations로 확인해 보세요.");
            if (ids.size() == 1) return ids.get(0);
            String stem = name.length() > 1 && name.endsWith("역") ? name.substring(0, name.length() - 1) : name;
            try {
                if (stationQuery.search(name, null, null, 0, 50).items().stream()
                        .anyMatch(found -> !found.isSupported() && (found.name().equals(name) || found.name().equals(stem)))) {
                    throw new Fail("STATION_UNSUPPORTED");
                }
            } catch (BusinessException invalid) {
                // a name the search rejects (too long) is just not a station
            }
            throw new Fail("STATION_NOT_FOUND");
        }

        private List<MemberRow> members() {
            return roomReads.members(List.of(roomId));
        }

        private long askerStation() {
            Long station = members().stream().filter(member -> member.userId().equals(userId)).map(MemberRow::stationId)
                    .filter(java.util.Objects::nonNull).findFirst().orElse(null);
            if (station == null) throw new Fail("DEPARTURE_MISSING");
            return station;
        }

        /** The member with this nickname (one of this room only), or the asker when no nickname is given. */
        private MemberRow member(List<MemberRow> members, String nickname) {
            List<MemberRow> found = members.stream()
                    .filter(member -> nickname == null ? member.userId().equals(userId) : member.nickname().equals(nickname)).toList();
            if (found.size() != 1) throw new Fail("MEMBER_NOT_FOUND");
            return found.get(0);
        }

        private String stationName(long id) {
            return stationNames(List.of(id)).get(id);
        }

        private Map<Long, String> stationNames(List<Long> ids) {
            Map<Long, String> names = new LinkedHashMap<>();
            roomReads.stations(ids.stream().filter(java.util.Objects::nonNull).toList())
                    .forEach((id, station) -> names.put(id, station.name()));
            return names;
        }
    }

    private Map<String, Object> failure(Throwable cause) {
        if (cause instanceof Fail fail) return failure(fail.code, fail.getMessage());
        if (cause instanceof BusinessException business) {
            ErrorCode code = business.getErrorCode();
            return failure(switch (code) {
                case ROUTE_NOT_FOUND -> "NO_ROUTE";
                case NO_SERVICE_TIME, ARRIVAL_UNREACHABLE -> "NO_SERVICE";
                case STATION_NOT_FOUND -> "STATION_NOT_FOUND";
                case STATION_UNSUPPORTED -> "STATION_UNSUPPORTED";
                default -> "NO_DATA";
            }, null);
        }
        log.warn("chat tool failed: {}", cause == null ? "?" : cause.getClass().getSimpleName());
        return failure("NO_DATA", null);
    }

    private static Map<String, Object> failure(String code, String message) {
        return map("ok", false, "code", code, "message", message);
    }

    private static Map<String, Object> ok(Object... pairs) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        result.putAll(map(pairs));
        return result;
    }

    /** Pairs of key, value; a null value is left out. */
    private static Map<String, Object> map(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            if (pairs[i + 1] != null) map.put((String) pairs[i], pairs[i + 1]);
        }
        return map;
    }

    private static String text(JsonNode args, String field) {
        JsonNode value = args.path(field);
        if (!value.isString()) return null;
        String text = value.asString().strip();
        return text.isEmpty() ? null : text;
    }

    private static String iso(Instant instant) {
        return instant == null ? null : ISO.format(instant.truncatedTo(ChronoUnit.SECONDS).atZone(SEOUL));
    }

    /** Whole minutes, rounded up. */
    /** An ISO time that is before {@code now}; a missing or unreadable time is not passed. */
    private static boolean passed(String isoTime, Instant now) {
        if (isoTime == null) return false;
        try {
            return java.time.OffsetDateTime.parse(isoTime).toInstant().isBefore(now);
        } catch (java.time.format.DateTimeParseException exception) {
            return false;
        }
    }

    private static long minutes(long seconds) {
        return (seconds + 59) / 60;
    }
}
