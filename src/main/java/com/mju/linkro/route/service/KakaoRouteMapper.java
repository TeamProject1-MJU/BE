package com.mju.linkro.route.service;

import com.mju.linkro.route.dto.RouteSearchResponse.FareSource;
import com.mju.linkro.route.dto.RouteSearchResponse.Line;
import com.mju.linkro.route.kakao.KakaoRouteResponse;
import com.mju.linkro.route.service.RouteDraft.StepDraft;
import com.mju.linkro.subway.api.*;
import com.mju.linkro.subway.graph.SubwayGraphData.Edge;
import com.mju.linkro.subway.graph.SubwayGraphData.Node;
import com.mju.linkro.subway.graph.SubwayGraphData.Station;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import com.mju.linkro.subway.fare.StationPairFare;
import org.springframework.stereotype.Component;

/**
 * Kakao routes -> our stations, lines and directions (6.1 "정차역 매핑과 방향", docs/kakao-route-analysis.md).
 *
 * <p>A route is kept only when it is a SUBWAY route made of SUBWAY and WALKING steps, every SUBWAY step is on
 * 1~8호선, every stop matches one of our nodes, every consecutive stop pair is a RIDE edge (which gives the
 * direction), and it starts at the origin and ends at the destination station. Any other route is dropped; when no
 * route is left the caller falls back to INTERNAL.
 */
@Component
public class KakaoRouteMapper {
    private static final Pattern LINE = Pattern.compile("([1-8])호선");

    private final RouteReference reference;
    private final StationPairFare fares;

    @org.springframework.beans.factory.annotation.Autowired
    public KakaoRouteMapper(RouteReference reference, FareCalculator fares, Optional<StationFareLookup> fareTable) {
        this.reference = reference;
        this.fares = new StationPairFare(fares, fareTable);
    }

    /** Without a fare table: Kakao routes without a fare use our ridden distance. */
    public KakaoRouteMapper(RouteReference reference, FareCalculator fares) {
        this(reference, fares, Optional.empty());
    }

    public List<RouteDraft> map(KakaoRouteResponse body, long originStationId, long destinationStationId) {
        if (body == null || !"OK".equals(body.status()) || body.routes() == null) return List.of();
        RouteReference.Index index = reference.index();
        List<RouteDraft> drafts = new ArrayList<>();
        for (KakaoRouteResponse.Route route : body.routes()) {
            map(route, index, originStationId, destinationStationId).ifPresent(drafts::add);
        }
        return drafts;
    }

    private Optional<RouteDraft> map(KakaoRouteResponse.Route route, RouteReference.Index index, long origin, long destination) {
        var properties = route.properties();
        if (properties == null || !"SUBWAY".equals(properties.type()) || route.steps() == null || route.steps().isEmpty()) {
            return Optional.empty();
        }
        List<StepDraft> steps = new ArrayList<>();
        int offset = 0;
        int rideMeters = 0;
        // Alighting station of the SUBWAY step before, as Kakao writes it and as we name it; null before the first ride.
        String alightedKakao = null;
        String alightedOurs = null;
        for (KakaoRouteResponse.Step step : route.steps()) {
            var p = step.properties();
            if (p == null) return Optional.empty();
            int time = p.time() == null ? 0 : p.time();
            int distance = p.distance() == null ? 0 : p.distance();
            List<List<Double>> path = step.path() == null ? null : step.path().points();
            if ("WALKING".equals(p.type())) {
                steps.add(new StepDraft(RouteDraft.WALKING, walkingGuidance(p.guidance(), alightedKakao, alightedOurs), time, distance,
                        null, null, List.of(), path));
            } else if ("SUBWAY".equals(p.type())) {
                Optional<Ride> ride = ride(p, index, offset, time);
                if (ride.isEmpty()) return Optional.empty();
                Ride r = ride.get();
                rideMeters += r.meters();
                PathStop last = r.stops().get(r.stops().size() - 1);
                steps.add(new StepDraft(RouteDraft.SUBWAY, RouteDraft.rideGuidance(r.line().name(), r.stops().size() - 1, last.stationName()),
                        time, distance, r.line(), r.stops().get(0).direction(), r.stops(), path));
                alightedKakao = p.stops().get(p.stops().size() - 1).name().strip();
                alightedOurs = last.stationName();
            } else {
                // BUS or anything else: subway only.
                return Optional.empty();
            }
            offset += time;
        }
        List<PathStop> stops = steps.stream().flatMap(step -> step.stops().stream()).toList();
        if (stops.isEmpty() || stops.get(0).stationId() != origin || stops.get(stops.size() - 1).stationId() != destination) {
            return Optional.empty();
        }
        int total = properties.totalTime() != null ? properties.totalTime() : offset;
        int transfers = properties.transfers() != null ? properties.transfers()
                : (int) steps.stream().filter(step -> RouteDraft.SUBWAY.equals(step.type())).count() - 1;
        Integer kakaoFare = properties.fare() == null ? null : properties.fare().value();
        int fare;
        FareSource fareSource;
        if (kakaoFare != null) {
            fare = kakaoFare;
            fareSource = FareSource.KAKAO;
        } else {
            // A subway-only route without fare.value was not seen (#58): the fare-table fare of the station pair (#109),
            // else an estimate from our RIDE distances.
            fare = fares.fareOf(origin, destination, new RoutePath(List.of(), stops, total, transfers, rideMeters, origin, destination));
            fareSource = FareSource.ESTIMATED;
        }
        return Optional.of(new RouteDraft(steps, total, fare, fareSource, transfers, origin, destination));
    }

    /**
     * Kakao transfer walks read "{Kakao name}{our name + 역} 환승", e.g. "시청시청역 환승",
     * "동대문역사문화공원(DDP)동대문역사문화공원역 환승", "서울역서울역 환승" (no second 역 after a name ending in 역).
     * When the guidance is exactly that for the alighting station of the ride before, the Kakao name is dropped
     * ("시청역 환승", "동대문역사문화공원역 환승", "서울역 환승"). Anything else, and walks before the first ride, keep
     * Kakao's text.
     *
     * @param kakaoName  alighting station of the ride before as Kakao writes it, or null before the first ride
     * @param ourName    the same station as stations.name, or null before the first ride
     */
    static String walkingGuidance(String guidance, String kakaoName, String ourName) {
        if (guidance == null || kakaoName == null || ourName == null || kakaoName.isEmpty() || ourName.isEmpty()) return guidance;
        String station = ourName.endsWith("역") ? ourName : ourName + "역";
        String transfer = station + " 환승";
        return guidance.equals(kakaoName + transfer) ? transfer : guidance;
    }

    private record Ride(Line line, List<PathStop> stops, int meters) {}

    /** Stops of one SUBWAY step as graph nodes with directions; empty when anything does not match. */
    private Optional<Ride> ride(KakaoRouteResponse.StepProperties p, RouteReference.Index index, int startOffset, int stepTime) {
        if (p.vehicles() == null || p.vehicles().isEmpty() || p.stops() == null || p.stops().size() < 2) return Optional.empty();
        Set<String> codes = new HashSet<>();
        for (var vehicle : p.vehicles()) {
            Matcher matcher = vehicle == null || vehicle.name() == null ? null : LINE.matcher(vehicle.name().strip());
            if (matcher == null || !matcher.matches()) return Optional.empty();
            codes.add(matcher.group(1));
        }
        if (codes.size() != 1) return Optional.empty();
        var line = index.linesByCode().get(codes.iterator().next());
        if (line == null) return Optional.empty();
        List<Node> nodes = new ArrayList<>();
        for (var stop : p.stops()) {
            if (stop == null || stop.name() == null) return Optional.empty();
            Optional<Node> node = index.node(line.code(), stop.name());
            if (node.isEmpty()) return Optional.empty();
            nodes.add(node.get());
        }
        List<DirectionCode> directions = new ArrayList<>();
        int meters = 0;
        for (int i = 0; i + 1 < nodes.size(); i++) {
            Optional<Edge> edge = index.ride(nodes.get(i).id(), nodes.get(i + 1).id());
            if (edge.isEmpty()) return Optional.empty();
            directions.add(edge.get().direction());
            meters += edge.get().meters();
        }
        int last = nodes.size() - 1;
        List<PathStop> stops = new ArrayList<>(nodes.size());
        for (int i = 0; i < nodes.size(); i++) {
            Node node = nodes.get(i);
            Station station = index.stations().get(node.stationId());
            // Kakao gives only the step time: stops are spread evenly over it. The alighting stop keeps the last direction.
            int offset = startOffset + (int) Math.round((double) stepTime * i / last);
            stops.add(new PathStop(station.id(), station.name(), station.latitude(), station.longitude(), node.id(),
                    node.lineId(), directions.get(Math.min(i, last - 1)), offset));
        }
        return Optional.of(new Ride(new Line(line.id(), line.name(), line.color()), stops, meters));
    }
}
