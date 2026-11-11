package com.mju.linkro.subway.fare;

import com.mju.linkro.subway.api.FareCalculator;
import com.mju.linkro.subway.api.RoutePath;
import com.mju.linkro.subway.api.StationFareLookup;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;

/**
 * The one fare rule for recommendations and route search (#109): the fare-table fare of the station pair, whatever
 * route is ridden. Falls back to {@link FareCalculator} on the ridden route:
 * <ul>
 *   <li>the same station (an empty route: the calculator's 0 m fare, 1,550원), no lookup and no log</li>
 *   <li>no fare table, or one that did not load (logged once when loading), without per-pair logs</li>
 *   <li>a loaded table without the pair: WARN once per pair</li>
 * </ul>
 */
@Slf4j
public final class StationPairFare {
    private final FareCalculator fares;
    private final StationFareLookup table;
    private final Set<List<Long>> warned = ConcurrentHashMap.newKeySet();

    public StationPairFare(FareCalculator fares, Optional<StationFareLookup> table) {
        this.fares = fares;
        this.table = table.filter(StationFareLookup::loaded).orElse(null);
    }

    public int fareOf(long fromStationId, long toStationId, RoutePath ridden) {
        if (fromStationId == toStationId || table == null) return fares.fareOf(ridden);
        OptionalInt fare = table.fareBetween(fromStationId, toStationId);
        if (fare.isPresent()) return fare.getAsInt();
        if (warned.add(List.of(fromStationId, toStationId))) {
            log.warn("No fare-table fare for stations {} -> {}; using the ridden distance", fromStationId, toStationId);
        }
        return fares.fareOf(ridden);
    }
}
