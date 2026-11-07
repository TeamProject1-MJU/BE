package com.mju.linkro.subway.congestion;

import com.mju.linkro.subway.api.*;
import com.mju.linkro.subway.congestion.CongestionResolver.Resolution;
import com.mju.linkro.subway.congestion.ServiceSlots.ServiceSlot;
import com.mju.linkro.subway.congestion.repository.CongestionStatsRepository;
import com.mju.linkro.subway.congestion.repository.CongestionStatsRepository.SeriesKey;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * {@link CongestionLookup} over congestion_stats. Every stop is resolved from one query that reads all slots of the
 * route's (node, direction, day type) series, so the query count does not grow with the number of stops.
 */
public class DbCongestionLookup implements CongestionLookup {
    private final CongestionStatsRepository stats;
    private final CongestionResolver resolver;

    public DbCongestionLookup(CongestionStatsRepository stats, CongestionResolver resolver) {
        this.stats = stats;
        this.resolver = resolver;
    }

    private record Pass(PathStop stop, ServiceSlot slot) {}

    @Override
    public CongestionResult congestionOf(RoutePath path, Instant departAt) {
        Objects.requireNonNull(departAt, "departAt");
        List<Pass> passes = path.stops().stream().map(stop -> new Pass(stop,
                ServiceSlots.slotOf(LocalDateTime.ofInstant(departAt.plusSeconds(stop.offsetSec()), ServiceSlots.ZONE)))).toList();
        Set<SeriesKey> keys = new LinkedHashSet<>();
        passes.stream().filter(pass -> pass.stop().direction() != null).forEach(pass ->
                keys.addAll(resolver.keys(pass.stop().stationLineId(), pass.stop().direction(), pass.slot().serviceDate())));
        var series = stats.series(keys);

        List<StopCongestion> stops = new ArrayList<>(passes.size());
        Resolution highest = null;
        for (Pass pass : passes) {
            PathStop stop = pass.stop();
            ServiceSlot slot = pass.slot();
            if (stop.direction() == null) {
                // A stop on no RIDE (the only stop of an empty route) has no lookup key.
                stops.add(new StopCongestion(stop.stationId(), stop.stationLineId(), stop.lineId(), null,
                        slot.serviceDate(), slot.slot(), slot.firstTrain(), CongestionResult.unsupported()));
                continue;
            }
            Resolution found = resolver.resolve(stop.stationLineId(), stop.direction(), slot.serviceDate(),
                    resolver.dayTypeOf(slot.serviceDate()), slot.slot(), slot.firstTrain(), series);
            stops.add(new StopCongestion(stop.stationId(), stop.stationLineId(), stop.lineId(), stop.direction(),
                    found.slotDate(), found.slot(), found.firstTrain(), found.result()));
            if (found.stat() != null && (highest == null || found.stat().pct().compareTo(highest.stat().pct()) > 0)) {
                highest = found;
            }
        }
        // Gray stops are left out of the maximum; with none supported the summary is gray too.
        CongestionResult summary = highest == null ? CongestionResult.unsupported() : highest.result();
        return summary.withStops(stops);
    }
}
