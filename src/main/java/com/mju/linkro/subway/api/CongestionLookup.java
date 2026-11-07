package com.mju.linkro.subway.api;

import java.time.Instant;

/** Congestion of a route from congestion_stats (6.2). */
public interface CongestionLookup {

    /**
     * Highest congestion among all stops of the route.
     *
     * <p>Each stop is passed at {@code departAt + offsetSec}. The 30-minute slot and the day type
     * (WEEKDAY/SATURDAY/SUNDAY, holidays as SUNDAY) are computed in Asia/Seoul, not in UTC or the server zone.
     * Slots are 05:30~23:30 and 00:00, 00:30 (39). A pass at 00:00~00:59 belongs to the previous day's service;
     * 01:00~05:29 uses the same day's first-train 05:30 slot; after the last train of the stop it uses the next
     * service day's 05:30 slot (docs/congestion-api-79.md). Stops without data are skipped; when no stop has data
     * the result is {@link CongestionResult#unsupported()}. Implementations may fill {@link CongestionResult#stops()}.
     */
    CongestionResult congestionOf(RoutePath path, Instant departAt);
}
