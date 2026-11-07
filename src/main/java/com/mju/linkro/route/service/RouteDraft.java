package com.mju.linkro.route.service;

import com.mju.linkro.route.dto.RouteSearchResponse.FareSource;
import com.mju.linkro.route.dto.RouteSearchResponse.Line;
import com.mju.linkro.subway.api.DirectionCode;
import com.mju.linkro.subway.api.PathStop;
import java.util.List;

/** A route before congestion: from Kakao or from our graph, in the same shape. */
record RouteDraft(List<StepDraft> steps, int totalTimeSec, int fare, FareSource fareSource, int transferCount,
                  long fromStationId, long toStationId) {

    static final String SUBWAY = "SUBWAY";
    static final String WALKING = "WALKING";

    /**
     * @param stops SUBWAY: boarding to alighting stop, each with its graph node, train direction and seconds from
     *              departure (the congestion lookup key). WALKING: empty.
     * @param path  [longitude, latitude] points, or null
     */
    record StepDraft(String type, String guidance, int durationSec, int distanceM, Line line, DirectionCode directionCode,
                     List<PathStop> stops, List<List<Double>> path) {}

    int walkingSeconds() {
        return steps.stream().filter(step -> WALKING.equals(step.type())).mapToInt(StepDraft::durationSec).sum();
    }

    /** All SUBWAY stops in travel order; the congestion lookup runs on these. */
    List<PathStop> subwayStops() {
        return steps.stream().flatMap(step -> step.stops().stream()).toList();
    }

    static String rideGuidance(String lineName, int stationCount, String alighting) {
        return lineName + " 탑승, " + stationCount + "개 역 이동 후 " + alighting + " 하차";
    }
}
