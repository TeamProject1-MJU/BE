package com.mju.linkro.route.service;

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
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * API 6.1. Kakao first, INTERNAL (our Dijkstra) when Kakao has no key, fails or gives no usable subway route.
 * Congestion of every SUBWAY stop is read from congestion_stats, like GET /congestion, whatever
 * {@code linkro.subway.congestion} is. No transaction is held while Kakao is called.
 */
@Slf4j
@Service
public class RouteSearchService {
    private static final DateTimeFormatter OFFSET_TIME = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    private final RoomAccess rooms;
    private final StationLookup stations;
    private final KakaoRouteClient kakao;
    private final KakaoRouteMapper mapper;
    private final InternalRouteBuilder internal;
    private final CongestionLookup congestion;
    private final Clock clock;

    @Autowired
    public RouteSearchService(RoomAccess rooms, StationLookup stations, KakaoRouteClient kakao, KakaoRouteMapper mapper,
                              InternalRouteBuilder internal, CongestionStatsRepository stats, CongestionResolver resolver) {
        this(rooms, stations, kakao, mapper, internal, new DbCongestionLookup(stats, resolver), Clock.systemUTC());
    }

    RouteSearchService(RoomAccess rooms, StationLookup stations, KakaoRouteClient kakao, KakaoRouteMapper mapper,
                       InternalRouteBuilder internal, CongestionLookup congestion, Clock clock) {
        this.rooms = rooms;
        this.stations = stations;
        this.kakao = kakao;
        this.mapper = mapper;
        this.internal = internal;
        this.congestion = congestion;
        this.clock = clock;
    }

    public RouteSearchResponse search(UUID userId, RouteSearchRequest request) {
        RouteSearchRequest.Valid query = request.validate();
        // roomId is optional; when sent, ROOM_NOT_FOUND (404) / NOT_ROOM_MEMBER (403) as in the room APIs.
        MeetingRoom room = query.roomId() == null ? null : rooms.requireMember(query.roomId(), userId);
        // Both stations are checked before Kakao: the INTERNAL fallback only covers supported stations.
        StationInfo origin = stations.requireSupported(query.originStationId());
        StationInfo destination = stations.requireSupported(query.destinationStationId());
        Instant now = clock.instant();

        RouteSource source = RouteSource.KAKAO;
        List<RouteDraft> drafts = List.of();
        String fallback;
        if (origin.id() == destination.id()) {
            fallback = "same station";
        } else {
            KakaoRouteClient.Result result = kakao.search(origin.longitude(), origin.latitude(), destination.longitude(), destination.latitude());
            if (result instanceof KakaoRouteClient.Found found) {
                drafts = mapper.map(found.body(), origin.id(), destination.id());
                fallback = drafts.isEmpty() ? "no usable subway route" : null;
            } else {
                fallback = ((KakaoRouteClient.Failed) result).reason().name();
            }
        }
        if (drafts.isEmpty()) {
            log.info("Route search {} -> {} uses INTERNAL: {}", origin.id(), destination.id(), fallback);
            source = RouteSource.INTERNAL;
            drafts = List.of(internal.build(origin.id(), destination.id()));
        }
        List<String> types = routeTypes(drafts);
        List<Route> routes = new ArrayList<>();
        for (int i = 0; i < drafts.size(); i++) {
            RouteDraft draft = drafts.get(i);
            routes.add(route("r" + (i + 1), types.get(i), draft, departure(room, query.departAt(), draft.totalTimeSec(), now)));
        }
        return new RouteSearchResponse(source, routes);
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

    private Route route(String routeId, String routeType, RouteDraft draft, Instant departure) {
        List<PathStop> stops = draft.subwayStops();
        List<StopCongestion> perStop = List.of();
        CongestionResult summary = CongestionResult.unsupported();
        if (!stops.isEmpty()) {
            CongestionResult result = congestion.congestionOf(
                    new RoutePath(List.of(), stops, draft.totalTimeSec(), draft.transferCount(), 0, draft.fromStationId(), draft.toStationId()),
                    departure);
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
        String arrivalAt = departure.plusSeconds(draft.totalTimeSec()).truncatedTo(ChronoUnit.SECONDS)
                .atZone(ServiceSlots.ZONE).toOffsetDateTime().format(OFFSET_TIME);
        int walkingMinutes = (int) Math.round(draft.walkingSeconds() / 60.0);
        return new Route(routeId, routeType, new Summary(draft.totalTimeSec(), arrivalAt, draft.fare(), draft.fareSource(),
                draft.transferCount(), walkingMinutes, congestion(summary)), steps);
    }

    /** pct is already the display value (CongestionResolver.display: integer, capped by level); no rounding here. */
    private static Congestion congestion(CongestionResult result) {
        if (!result.supported()) return new Congestion(false, null, null, null, null);
        return new Congestion(true, result.source(), result.pct().intValue(), result.level(), result.levelLabel());
    }
}
