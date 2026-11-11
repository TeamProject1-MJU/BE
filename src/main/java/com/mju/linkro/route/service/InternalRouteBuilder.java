package com.mju.linkro.route.service;

import com.mju.linkro.route.dto.RouteSearchResponse.FareSource;
import com.mju.linkro.route.dto.RouteSearchResponse.Line;
import com.mju.linkro.route.service.RouteDraft.StepDraft;
import com.mju.linkro.subway.api.*;
import java.util.ArrayList;
import java.util.List;
import com.mju.linkro.subway.fare.StationPairFare;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * INTERNAL route: our minimum-time route (SubwayRouteFinder) with our estimated fare. RIDE steps become SUBWAY steps
 * with stops and a path from our station coordinates; TRANSFER steps become WALKING steps.
 */
@Component
public class InternalRouteBuilder {
    private final SubwayRouteFinder finder;
    private final StationPairFare fares;

    public InternalRouteBuilder(SubwayRouteFinder finder, FareCalculator fares, Optional<StationFareLookup> fareTable) {
        this.finder = finder;
        this.fares = new StationPairFare(fares, fareTable);
    }

    /** @throws com.mju.linkro.common.exception.BusinessException ROUTE_NOT_FOUND (404) and the finder's other errors */
    public RouteDraft build(long originStationId, long destinationStationId) {
        RoutePath path = finder.shortestPath(originStationId, destinationStationId);
        List<StepDraft> steps = new ArrayList<>();
        int index = 0;
        for (PathStep step : path.steps()) {
            if (step.type() == StepType.RIDE) {
                // A RIDE of n stations covers stops [index, index + n]; its last stop is the next step's start.
                List<PathStop> stops = path.stops().subList(index, index + step.stationCount() + 1);
                List<List<Double>> points = stops.stream().map(stop -> List.of(stop.longitude(), stop.latitude())).toList();
                steps.add(new StepDraft(RouteDraft.SUBWAY, RouteDraft.rideGuidance(step.lineName(), step.stationCount(), step.toStationName()),
                        step.durationSec(), step.distanceM(), new Line(step.lineId(), step.lineName(), step.color()), step.direction(),
                        List.copyOf(stops), points));
                index += step.stationCount();
            } else {
                // A TRANSFER adds one stop: the node boarded next.
                steps.add(new StepDraft(RouteDraft.WALKING, step.toStationName() + "역 " + step.lineName() + " 환승",
                        step.durationSec(), step.distanceM(), null, null, List.of(), null));
                index += 1;
            }
        }
        // Fare-table fare of the station pair (#109), as for recommendations; the ridden distance without it.
        return new RouteDraft(steps, path.totalSeconds(), fares.fareOf(originStationId, destinationStationId, path),
                FareSource.ESTIMATED, path.transferCount(),
                originStationId, destinationStationId);
    }
}
