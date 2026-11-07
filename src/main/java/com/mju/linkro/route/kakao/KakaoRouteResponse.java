package com.mju.linkro.route.kakao;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * The fields of the Kakao public-transport route response this server reads (docs/kakao-route-analysis.md). The
 * body only lives in memory for one request: it is never stored, cached or logged (Kakao terms).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record KakaoRouteResponse(String status, List<Route> routes) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Route(RouteProperties properties, List<Step> steps) {}

    /** {@code type} is SUBWAY, BUS or BUS_AND_SUBWAY; time in seconds, distance in metres. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RouteProperties(String type, Integer totalDistance, Integer totalTime, Integer transfers, Fare fare) {}

    /** Subway-only routes carry {@code value}; routes with a bus carry only min/max. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Fare(Integer value) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Step(StepProperties properties, Path path) {}

    /** {@code type} is SUBWAY, BUS or WALKING. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record StepProperties(String type, String guidance, Integer distance, Integer time, List<Stop> stops,
                                 List<Vehicle> vehicles) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Stop(String name) {}

    /** {@code name} such as "2호선". */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Vehicle(String name) {}

    /** [[longitude, latitude], ...] */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Path(List<List<Double>> points) {}
}
