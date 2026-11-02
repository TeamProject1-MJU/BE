package com.mju.linkro.subway.api;

import java.time.Instant;

/** Congestion of a route from congestion_stats (6.2). */
public interface CongestionLookup {

    /**
     * Highest congestion among all stops of the route.
     *
     * <p>Each stop is passed at {@code departAt + offsetSec}. The 30-minute slot and the day type
     * (WEEKDAY/SATURDAY/SUNDAY, holidays as SUNDAY) are computed in Asia/Seoul, not in UTC or the server zone.
     * Times outside 06:00~23:00 use the next first-train 06:00 slot (6.2). Stops without data are skipped;
     * when no stop has data the result is {@link CongestionResult#unsupported()}.
     */
    CongestionResult congestionOf(RoutePath path, Instant departAt);
}
