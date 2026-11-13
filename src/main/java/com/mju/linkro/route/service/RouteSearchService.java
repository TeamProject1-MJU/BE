package com.mju.linkro.route.service;

import com.mju.linkro.common.exception.BusinessException;
import com.mju.linkro.common.exception.ErrorCode;
import com.mju.linkro.room.domain.MeetingRoom;
import com.mju.linkro.room.service.RoomAccess;
import com.mju.linkro.route.dto.RouteSearchRequest;
import com.mju.linkro.route.dto.RouteSearchResponse;
import com.mju.linkro.route.dto.RouteSearchResponse.*;
import com.mju.linkro.route.kakao.KakaoRouteClient;
import com.mju.linkro.route.service.RouteDraft.StepDraft;
import com.mju.linkro.subway.api.*;
import com.mju.linkro.subway.congestion.CongestionResolver;
import com.mju.linkro.subway.congestion.DbCongestionLookup;
import com.mju.linkro.subway.congestion.ServiceSlots;
import com.mju.linkro.subway.congestion.repository.CongestionStatsRepository;
import com.mju.linkro.subway.timetable.TimetableClock;
import com.mju.linkro.subway.timetable.TrainTimetable;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * API 6.1. Kakao first, INTERNAL (our Dijkstra) when Kakao has no key, fails or gives no usable subway route.
 * Congestion of every SUBWAY stop is read from congestion_stats, like GET /congestion, whatever
 * {@code linkro.subway.congestion} is. No transaction is held while Kakao is called.
 *
 * <p>With the train timetable loaded (graph module, {@code linkro.subway.timetable.enabled}), each route is ridden on it
 * at its departure; when no train runs it then, the departure moves to the first train (#120, {@link FirstTrainTimer}).
 *
 * <p>Room appointment mode (#122): with a room whose appointment has not passed and the timetable router, the route is
 * the one recommendations use ({@link TimetableRouteFinder#latestDepartures}, the same bean and so the same station
 * access setting) and Kakao is not called. When the timetable has no route arriving in time, the flow above is used.
 *
 * <p>Solo mode (#130): with the timetable router and no room, a room without an appointment or one whose appointment has
 * passed, the route is the earliest-arrival route recommendations give without an appointment
 * ({@link TimetableRouteFinder#departingAt}) from the request time, with the wait before leaving (summary.waitSec), and
 * Kakao is not called. When the timetable has no route, also from the next service day, the flow above is used.
 */
@Slf4j
@Service
public class RouteSearchService {
    private static final DateTimeFormatter OFFSET_TIME = DateTimeFormatter.ISO_OFFSET_DATE_TIME;
    private static final DateTimeFormatter NOTICE_TIME = DateTimeFormatter.ofPattern("M월 d일 HH:mm");
    static final String APPOINTMENT_PASSED_NOTICE = "약속 시각이 이미 지나 지금 출발 기준으로 안내합니다.";

    private final RoomAccess rooms;
    private final StationLookup stations;
    private final KakaoRouteClient kakao;
    private final KakaoRouteMapper mapper;
    private final InternalRouteBuilder internal;
    private final CongestionLookup congestion;
    private final Clock clock;
    /** Null without the train timetable: departures are never moved. */
    private final FirstTrainTimer firstTrains;
    /** Null without the timetable router: room appointments use the flow without it. */
    private final TimetableRouteFinder timetableRoutes;
    /** How far ahead of now arriveAt may be ({@code linkro.route.arrive-at-max-days}). */
    private final Duration arriveAtMaxAhead;

    @Autowired
    public RouteSearchService(RoomAccess rooms, StationLookup stations, KakaoRouteClient kakao, KakaoRouteMapper mapper,
                              InternalRouteBuilder internal, CongestionStatsRepository stats, CongestionResolver resolver,
                              Optional<TrainTimetable> timetable, Optional<TimetableClock> timetableClock,
                              Optional<TimetableRouteFinder> timetableRoutes,
                              @Value("${linkro.route.arrive-at-max-days:30}") int arriveAtMaxDays) {
        this(rooms, stations, kakao, mapper, internal, new DbCongestionLookup(stats, resolver), Clock.systemUTC(),
                timetable.isPresent() && timetableClock.isPresent() ? new FirstTrainTimer(timetable.get(), timetableClock.get()) : null,
                timetableRoutes.orElse(null), Duration.ofDays(arriveAtMaxDays));
    }

    RouteSearchService(RoomAccess rooms, StationLookup stations, KakaoRouteClient kakao, KakaoRouteMapper mapper,
                       InternalRouteBuilder internal, CongestionLookup congestion, Clock clock) {
        this(rooms, stations, kakao, mapper, internal, congestion, clock, null, null);
    }

    RouteSearchService(RoomAccess rooms, StationLookup stations, KakaoRouteClient kakao, KakaoRouteMapper mapper,
                       InternalRouteBuilder internal, CongestionLookup congestion, Clock clock, FirstTrainTimer firstTrains) {
        this(rooms, stations, kakao, mapper, internal, congestion, clock, firstTrains, null);
    }

    RouteSearchService(RoomAccess rooms, StationLookup stations, KakaoRouteClient kakao, KakaoRouteMapper mapper,
                       InternalRouteBuilder internal, CongestionLookup congestion, Clock clock, FirstTrainTimer firstTrains,
                       TimetableRouteFinder timetableRoutes) {
        this(rooms, stations, kakao, mapper, internal, congestion, clock, firstTrains, timetableRoutes, Duration.ofDays(30));
    }

    RouteSearchService(RoomAccess rooms, StationLookup stations, KakaoRouteClient kakao, KakaoRouteMapper mapper,
                       InternalRouteBuilder internal, CongestionLookup congestion, Clock clock, FirstTrainTimer firstTrains,
                       TimetableRouteFinder timetableRoutes, Duration arriveAtMaxAhead) {
        if (arriveAtMaxAhead.isNegative() || arriveAtMaxAhead.isZero()) {
            throw new IllegalArgumentException("linkro.route.arrive-at-max-days must be positive");
        }
        this.rooms = rooms;
        this.stations = stations;
        this.kakao = kakao;
        this.mapper = mapper;
        this.internal = internal;
        this.congestion = congestion;
        this.clock = clock;
        this.firstTrains = firstTrains;
        this.timetableRoutes = timetableRoutes;
        this.arriveAtMaxAhead = arriveAtMaxAhead;
    }

    public RouteSearchResponse search(UUID userId, RouteSearchRequest request) {
        Instant now = clock.instant();
        RouteSearchRequest.Valid query = request.validate(now, arriveAtMaxAhead);
        // roomId is optional; when sent, ROOM_NOT_FOUND (404) / NOT_ROOM_MEMBER (403) as in the room APIs.
        MeetingRoom room = query.roomId() == null ? null : rooms.requireMember(query.roomId(), userId);
        // Both stations are checked before Kakao: the INTERNAL fallback only covers supported stations.
        StationInfo origin = stations.requireSupported(query.originStationId());
        StationInfo destination = stations.requireSupported(query.destinationStationId());

        // Arrival mode (#130): arriveAt wins over the room's appointment; the room is only checked for membership.
        if (query.arriveAt() != null && origin.id() != destination.id()) {
            if (timetableRoutes != null) return arrivalRoute(origin.id(), destination.id(), query.arriveAt());
            log.warn("Route search {} -> {} ignores arriveAt: no timetable router (linkro.subway.module=mock or "
                    + "linkro.subway.timetable.enabled=false)", origin.id(), destination.id());
        }

        boolean passed = appointmentPassed(room, now);
        Optional<TimedRoute> timed = roomAppointmentRoute(room, passed, origin.id(), destination.id());
        if (timed.isPresent()) {
            log.info("Route search {} -> {} uses the timetable route for the room appointment", origin.id(), destination.id());
            return new RouteSearchResponse(RouteSource.INTERNAL,
                    List.of(timedRoute("r1", timed.get(), origin.id(), destination.id(), null, false, null)), false, null);
        }

        // Solo mode (#130): no room, a room without an appointment, or one whose appointment has passed.
        if (soloMode(room, passed, origin.id(), destination.id())) {
            Instant requested = soloRequestTime(room, query.departAt(), now);
            Optional<SoloRoute> solo = soloRoute(origin.id(), destination.id(), requested);
            if (solo.isPresent()) {
                log.info("Route search {} -> {} uses INTERNAL: timetable route from the request time{}", origin.id(), destination.id(),
                        solo.get().nextServiceDay() ? " (next service day)" : "");
                return new RouteSearchResponse(RouteSource.INTERNAL,
                        List.of(soloTimedRoute(solo.get(), origin.id(), destination.id(), requested)),
                        passed, passed ? APPOINTMENT_PASSED_NOTICE : null);
            }
        }

        RouteSource source = RouteSource.KAKAO;
        List<RouteDraft> drafts = List.of();
        String fallback;
        // Expected fallbacks (no key, cooldown, same station, no usable subway route) are INFO; Kakao failures WARN.
        boolean expected = true;
        if (origin.id() == destination.id()) {
            fallback = "same station";
        } else {
            KakaoRouteClient.Result result = kakao.search(origin.longitude(), origin.latitude(), destination.longitude(), destination.latitude());
            if (result instanceof KakaoRouteClient.Found found) {
                drafts = mapper.map(found.body(), origin.id(), destination.id());
                fallback = drafts.isEmpty() ? "no usable subway route" : null;
            } else {
                KakaoRouteClient.Reason reason = ((KakaoRouteClient.Failed) result).reason();
                fallback = reason.name();
                expected = reason.expected();
            }
        }
        if (drafts.isEmpty()) {
            if (expected) {
                log.info("Route search {} -> {} uses INTERNAL: {}", origin.id(), destination.id(), fallback);
            } else {
                log.warn("Route search {} -> {} uses INTERNAL: {}", origin.id(), destination.id(), fallback);
            }
            source = RouteSource.INTERNAL;
            drafts = List.of(internal.build(origin.id(), destination.id()));
        }
        List<String> types = routeTypes(drafts);
        // Only a room appointment that has not passed is compared with arrivals.
        Instant appointment = room == null || passed ? null : room.getAppointmentAt();
        List<Route> routes = new ArrayList<>();
        for (int i = 0; i < drafts.size(); i++) {
            RouteDraft draft = drafts.get(i);
            routes.add(route("r" + (i + 1), types.get(i), draft, departure(room, query.departAt(), draft.totalTimeSec(), now),
                    appointment));
        }
        return new RouteSearchResponse(source, routes, passed, passed ? APPOINTMENT_PASSED_NOTICE : null);
    }

    /**
     * Room appointment mode (#122): the latest-departure route recommendations give the member from {@code originId} to
     * {@code destinationId} for the room's appointment. Empty, so the flow without it is used, when there is no timetable
     * router, no room or appointment, the appointment has passed, both stations are the same, or no train arrives in time
     * on the appointment's service day.
     */
    private Optional<TimedRoute> roomAppointmentRoute(MeetingRoom room, boolean appointmentPassed, long originId, long destinationId) {
        if (timetableRoutes == null || room == null || room.getAppointmentAt() == null || appointmentPassed || originId == destinationId) {
            return Optional.empty();
        }
        return arriveByRoute(originId, destinationId, room.getAppointmentAt());
    }

    /**
     * The latest-departure route from {@code originId} arriving at {@code destinationId} by {@code target}: the one
     * recommendations give a member for an appointment at {@code target} (room appointment mode and arrival mode). Empty
     * when no train of that service day arrives in time.
     */
    private Optional<TimedRoute> arriveByRoute(long originId, long destinationId, Instant target) {
        return Optional.ofNullable(timetableRoutes.latestDepartures(destinationId, target, Set.of(originId), 0).get(originId));
    }

    /**
     * Arrival mode (#130): the latest-departure route arriving by {@code arriveAt}, shaped like room appointment mode, with
     * slackSeconds = arriveAt − arrival. Kakao is not called.
     *
     * @throws BusinessException NO_SERVICE_TIME when no train of any line arrives in the window before {@code arriveAt}
     *         (the recommendation check, {@link TimetableRouteFinder#noTrainArrivesBefore}); ARRIVAL_UNREACHABLE when
     *         trains run but none from this origin arrives in time
     */
    private RouteSearchResponse arrivalRoute(long originId, long destinationId, Instant arriveAt) {
        if (timetableRoutes.noTrainArrivesBefore(arriveAt)) throw new BusinessException(ErrorCode.NO_SERVICE_TIME);
        TimedRoute timed = arriveByRoute(originId, destinationId, arriveAt)
                .orElseThrow(() -> new BusinessException(ErrorCode.ARRIVAL_UNREACHABLE));
        log.info("Route search {} -> {} uses INTERNAL: timetable route arriving by the requested time", originId, destinationId);
        int slack = (int) Duration.between(timed.arriveAt(), arriveAt).toSeconds();
        return new RouteSearchResponse(RouteSource.INTERNAL,
                List.of(timedRoute("r1", timed, originId, destinationId, null, false, slack)), false, null);
    }

    /**
     * Solo mode (#130) applies with the timetable router, two different stations, and no room, a room without an
     * appointment or one whose appointment has passed. An active appointment is room appointment mode, and when that has
     * no route the flow without the timetable router is used, not this.
     */
    private boolean soloMode(MeetingRoom room, boolean appointmentPassed, long originId, long destinationId) {
        return timetableRoutes != null && originId != destinationId
                && (room == null || room.getAppointmentAt() == null || appointmentPassed);
    }

    /**
     * When a solo route leaves from: departAt, or now. A room whose appointment has passed ignores departAt and leaves now
     * (#120), as {@link #departure} does.
     */
    static Instant soloRequestTime(MeetingRoom room, Instant departAt, Instant now) {
        if (room != null && room.getAppointmentAt() != null) return now;
        return departAt != null ? departAt : now;
    }

    /** @param nextServiceDay the route was searched again from the start of the next service day */
    private record SoloRoute(TimedRoute route, boolean nextServiceDay) {}

    /**
     * The earliest-arrival route recommendations give a member without an appointment
     * ({@link TimetableRouteFinder#departingAt}, with its fewer-transfers search #107), leaving at {@code requested}.
     * The router only moves to the next service day after the day's last departure of any edge; when this route's own
     * trains have ended before that, it is searched once more from the start (03:00) of the next service day. Empty
     * when that has no route either, or when there is no {@link FirstTrainTimer} (no timetable clock) to tell that day.
     */
    private Optional<SoloRoute> soloRoute(long originId, long destinationId, Instant requested) {
        Optional<TimedRoute> route = timetableRoutes.departingAt(originId, requested).routeTo(destinationId);
        if (route.isPresent()) return Optional.of(new SoloRoute(route.get(), false));
        if (firstTrains == null) return Optional.empty();
        return timetableRoutes.departingAt(originId, firstTrains.nextServiceDayStart(requested)).routeTo(destinationId)
                .map(next -> new SoloRoute(next, true));
    }

    /**
     * A solo timetable route: the timetable route of {@link #timedRoute} plus the wait from {@code requested} to leaving.
     * noService is the #120 judgement ({@link FirstTrainTimer#boardsFirstTrainAfter}) without moving any time; it is
     * always true for a route of the next service day.
     */
    private Route soloTimedRoute(SoloRoute solo, long originId, long destinationId, Instant requested) {
        TimedRoute timed = solo.route();
        Wait wait = new Wait((int) Duration.between(requested, timed.leaveAt()).toSeconds(), Wait.TIMETABLE,
                (int) Duration.between(requested, timed.arriveAt()).toSeconds());
        RouteDraft path = internal.toDraft(timed.path(), originId, destinationId);
        boolean noService = solo.nextServiceDay() || (firstTrains != null
                && firstTrains.boardsFirstTrainAfter(path, timed.boardAt(), timed.leaveAt(), requested));
        return timedRoute("r1", timed, path, wait, noService, null);
    }

    private Route timedRoute(String routeId, TimedRoute timed, long originId, long destinationId, Wait wait, boolean noService,
                             Integer slackSeconds) {
        return timedRoute(routeId, timed, internal.toDraft(timed.path(), originId, destinationId), wait, noService, slackSeconds);
    }

    /**
     * A timetable route as recommendations show it (RecommendationService#participantTimes): departure = leaveAt,
     * arrival = arriveAt, stop congestion from boardAt (stop offsets count from it), fare from {@link InternalRouteBuilder}'s
     * StationPairFare like the engine. totalTimeSec = arriveAt − leaveAt; walkingMinutes counts walks only, not the
     * platform waits inside its transfer steps. Room appointment mode arrives by the appointment and solo mode has none,
     * so arrivesAfterAppointment is false.
     *
     * @param path         the route as {@link InternalRouteBuilder#toDraft} gives it
     * @param wait         the wait before leaving (solo mode), or null
     * @param slackSeconds requested arrival − arrival (arrival mode), or null
     */
    private Route timedRoute(String routeId, TimedRoute timed, RouteDraft path, Wait wait, boolean noService, Integer slackSeconds) {
        int total = (int) Duration.between(timed.leaveAt(), timed.arriveAt()).toSeconds();
        RouteDraft draft = new RouteDraft(path.steps(), total, path.fare(), path.fareSource(), path.transferCount(),
                path.fromStationId(), path.toStationId());
        int walkSeconds = timed.legs().stream().mapToInt(TimedLeg::walkSec).sum();
        return route(routeId, routeTypes(List.of(draft)).get(0), draft, timed.leaveAt(), timed.boardAt(), timed.arriveAt(),
                walkSeconds, noService, noService ? serviceNotice(timed.boardAt()) : null, false, wait, slackSeconds);
    }

    /**
     * summary.waitSec / waitKind / totalWithWaitSec of a solo timetable route (#130); null fields elsewhere.
     *
     * @param waitSec          leaving − requested time
     * @param totalWithWaitSec arrival − requested time
     */
    record Wait(int waitSec, String kind, int totalWithWaitSec) {
        static final String TIMETABLE = "TIMETABLE";
    }

    /**
     * The room's appointment itself is before now, compared in whole seconds like recommendations (APPOINTMENT_PASSED):
     * departures are then the request time and departAt is ignored. A planned departure (appointment − totalTimeSec)
     * that has passed while the appointment has not shows as {@link #arrivesAfter} instead.
     */
    static boolean appointmentPassed(MeetingRoom room, Instant now) {
        return room != null && room.getAppointmentAt() != null && room.getAppointmentAt().truncatedTo(ChronoUnit.SECONDS)
                .isBefore(now.truncatedTo(ChronoUnit.SECONDS));
    }

    /**
     * The final arrival (moved to the first train or not) is after the appointment, both in whole seconds. False without
     * an appointment; leaving at appointment − totalTimeSec arrives exactly at it and is false.
     */
    static boolean arrivesAfter(Instant arrival, Instant appointment) {
        return appointment != null
                && arrival.truncatedTo(ChronoUnit.SECONDS).isAfter(appointment.truncatedTo(ChronoUnit.SECONDS));
    }

    /**
     * 6.1: with a room that has an appointment, appointment − totalTimeSec unless that has passed; otherwise departAt,
     * or the request time.
     */
    static Instant departure(MeetingRoom room, Instant departAt, int totalTimeSec, Instant now) {
        if (room != null && room.getAppointmentAt() != null) {
            Instant planned = room.getAppointmentAt().minusSeconds(totalTimeSec);
            return planned.isBefore(now) ? now : planned;
        }
        return departAt != null ? departAt : now;
    }

    /**
     * FASTEST, then FEWER_TRANSFERS, then CHEAPEST: each label goes to the first route that is best for it, unless
     * that route already has a label. A route has at most one label; the others are null.
     */
    static List<String> routeTypes(List<RouteDraft> drafts) {
        String[] types = new String[drafts.size()];
        label(types, drafts, Comparator.comparingInt(RouteDraft::totalTimeSec), "FASTEST");
        label(types, drafts, Comparator.comparingInt(RouteDraft::transferCount), "FEWER_TRANSFERS");
        label(types, drafts, Comparator.comparingInt(RouteDraft::fare), "CHEAPEST");
        return Arrays.asList(types);
    }

    private static void label(String[] types, List<RouteDraft> drafts, Comparator<RouteDraft> order, String type) {
        RouteDraft best = drafts.stream().min(order).orElseThrow();
        for (int i = 0; i < drafts.size(); i++) {
            if (order.compare(drafts.get(i), best) == 0) {
                if (types[i] == null) types[i] = type;
                return;
            }
        }
    }

    /** @param appointment the room's appointment when it has not passed, else null */
    private Route route(String routeId, String routeType, RouteDraft draft, Instant requested, Instant appointment) {
        FirstTrainTimer.Timing timing = firstTrains == null ? null : firstTrains.time(draft, requested).orElse(null);
        boolean noService = timing != null && timing.noService();
        Instant departure = noService ? timing.departure() : requested;
        String serviceNotice = noService ? serviceNotice(timing.boardAt()) : null;
        Instant arrival = departure.plusSeconds(draft.totalTimeSec());
        return route(routeId, routeType, draft, departure, departure, arrival, draft.walkingSeconds(), noService, serviceNotice,
                arrivesAfter(arrival, appointment), null, null);
    }

    /**
     * The response route. {@code congestionFrom} is the time the stop offsets of the draft count from: the departure for
     * Kakao and fixed-cost routes, the first boarding for timetable routes.
     */
    private Route route(String routeId, String routeType, RouteDraft draft, Instant departure, Instant congestionFrom,
                        Instant arrival, int walkingSeconds, boolean noService, String serviceNotice, boolean arrivesAfterAppointment,
                        Wait wait, Integer slackSeconds) {
        List<PathStop> stops = draft.subwayStops();
        List<StopCongestion> perStop = List.of();
        CongestionResult summary = CongestionResult.unsupported();
        if (!stops.isEmpty()) {
            CongestionResult result = congestion.congestionOf(
                    new RoutePath(List.of(), stops, draft.totalTimeSec(), draft.transferCount(), 0, draft.fromStationId(), draft.toStationId()),
                    congestionFrom);
            summary = result;
            perStop = result.stops();
        }
        Iterator<StopCongestion> next = perStop.iterator();
        List<Step> steps = new ArrayList<>();
        for (StepDraft step : draft.steps()) {
            List<Stop> stopList = new ArrayList<>();
            List<Station> stationList = new ArrayList<>();
            for (int i = 0; i < step.stops().size(); i++) {
                PathStop stop = step.stops().get(i);
                stopList.add(new Stop(stop.stationId(), stop.stationName(), i + 1));
                StopCongestion value = next.hasNext() ? next.next() : null;
                stationList.add(value == null
                        ? new Station(stop.stationId(), stop.stationName(), null, null, false, congestion(CongestionResult.unsupported()))
                        : new Station(stop.stationId(), stop.stationName(), value.slotStart().toString(), value.slotDate().toString(),
                        value.firstTrain(), congestion(value.congestion())));
            }
            steps.add(new Step(step.type(), step.guidance(), step.durationSec(), step.distanceM(), step.line(), null,
                    step.directionCode(), stopList, step.path(), stationList));
        }
        int walkingMinutes = (int) Math.round(walkingSeconds / 60.0);
        return new Route(routeId, routeType, new Summary(draft.totalTimeSec(), offsetTime(arrival), draft.fare(), draft.fareSource(),
                draft.transferCount(), walkingMinutes, congestion(summary), offsetTime(departure), noService, serviceNotice,
                arrivesAfterAppointment, wait == null ? null : wait.waitSec(), wait == null ? null : wait.kind(),
                wait == null ? null : wait.totalWithWaitSec(), slackSeconds), steps);
    }

    private static String offsetTime(Instant at) {
        return at.truncatedTo(ChronoUnit.SECONDS).atZone(ServiceSlots.ZONE).toOffsetDateTime().format(OFFSET_TIME);
    }

    static String serviceNotice(Instant boardAt) {
        return "요청한 시각에는 이 경로를 운행하는 열차가 없어 첫차(" + boardAt.atZone(ServiceSlots.ZONE).format(NOTICE_TIME)
                + " 출발) 기준으로 안내합니다.";
    }

    /** pct is already the display value (CongestionResolver.display: integer, capped by level); no rounding here. */
    private static Congestion congestion(CongestionResult result) {
        if (!result.supported()) return new Congestion(false, null, null, null, null);
        return new Congestion(true, result.source(), result.pct().intValue(), result.level(), result.levelLabel());
    }
}
