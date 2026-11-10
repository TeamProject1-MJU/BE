package com.mju.linkro.subway.arrival;

import com.mju.linkro.common.exception.BusinessException;
import com.mju.linkro.common.exception.ErrorCode;
import com.mju.linkro.subway.api.DirectionCode;
import com.mju.linkro.subway.arrival.SeoulArrivalClient.Arrival;
import com.mju.linkro.subway.arrival.SeoulArrivalClient.Fetch;
import com.mju.linkro.subway.arrival.SeoulArrivalClient.NoData;
import com.mju.linkro.subway.arrival.SeoulArrivalClient.Rows;
import com.mju.linkro.subway.arrival.dto.ArrivalQuery;
import com.mju.linkro.subway.arrival.dto.ArrivalResponse;
import com.mju.linkro.subway.arrival.dto.ArrivalResponse.Reason;
import com.mju.linkro.subway.arrival.dto.ArrivalResponse.Train;
import com.mju.linkro.subway.congestion.ServiceSlots;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoField;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Next trains at one station on one line (docs/arrivals-86.md). One upstream answer holds every line of a station,
 * so it is cached per query name: {@code cache-ttl} (15 s) for rows, {@code failure-cache-ttl} (5 s) for no data and
 * errors, with one upstream call per name at a time. The line (subwayId) is picked after the cache, and remaining
 * seconds are recomputed from each row's recptnDt on every request.
 */
@Slf4j
@Service
public class ArrivalService {
    static final long MAX_AGE_SECONDS = 300;
    /** Sort weight of one station when only stopsAway is known; never sent. */
    static final int SECONDS_PER_STOP = 120;
    private static final java.util.regex.Pattern STOPS_AWAY = java.util.regex.Pattern.compile("^\\[(\\d+)\\]번째 전역");
    private static final DateTimeFormatter RECEIVED = new DateTimeFormatterBuilder()
            .appendPattern("yyyy-MM-dd HH:mm:ss").optionalStart().appendFraction(ChronoField.NANO_OF_SECOND, 0, 9, true)
            .optionalEnd().toFormatter();

    private final ArrivalStationRepository stations;
    private final ArrivalStationNames names;
    private final SeoulArrivalClient client;
    private final Duration ttl;
    private final Duration failureTtl;
    private final int maxStopsAway;
    private final Clock clock;
    private final ConcurrentHashMap<String, Entry> cache = new ConcurrentHashMap<>();

    @Autowired
    public ArrivalService(ArrivalStationRepository stations, ArrivalStationNames names, SeoulArrivalClient client,
                          @Value("${linkro.arrival.cache-ttl:15s}") Duration ttl,
                          @Value("${linkro.arrival.failure-cache-ttl:5s}") Duration failureTtl,
                          @Value("${linkro.arrival.max-stops-away:10}") int maxStopsAway) {
        this(stations, names, client, ttl, failureTtl, maxStopsAway, Clock.systemUTC());
    }

    ArrivalService(ArrivalStationRepository stations, ArrivalStationNames names, SeoulArrivalClient client,
                   Duration ttl, Duration failureTtl, int maxStopsAway, Clock clock) {
        this.stations = stations;
        this.names = names;
        this.client = client;
        this.ttl = ttl;
        this.failureTtl = failureTtl;
        this.maxStopsAway = maxStopsAway;
        this.clock = clock;
    }

    /** One upstream answer for a query name: every row of every line, no data, or a failure. */
    private sealed interface Answer permits Found, Empty, Failed {}

    private record Found(List<Arrival> arrivals, Instant receivedAt) implements Answer {}

    private record Empty(Instant receivedAt) implements Answer {}

    private record Failed() implements Answer {}

    private record Entry(CompletableFuture<Answer> answer, Instant expiresAt) {
        boolean expired(Instant now) {
            return !now.isBefore(expiresAt);
        }
    }

    public ArrivalResponse arrivals(long stationId, ArrivalQuery query) {
        var station = stations.station(stationId).orElseThrow(() -> new BusinessException(ErrorCode.STATION_NOT_FOUND));
        Optional<ArrivalStationRepository.Node> node = stations.node(stationId, query.lineId());
        Optional<String> subwayId = node.flatMap(found -> SeoulSubwayIds.subwayId(found.lineCode()));
        if (!station.supported() || subwayId.isEmpty()) return ArrivalResponse.unavailable(Reason.UNSUPPORTED_STATION, null);
        if (!client.enabled()) return ArrivalResponse.unavailable(Reason.DISABLED, null);

        String lineCode = node.get().lineCode();
        String queryName = names.queryName(lineCode, station.name());
        Answer answer = answer(queryName);
        if (answer instanceof Failed) return ArrivalResponse.unavailable(Reason.UPSTREAM_ERROR, null);
        if (answer instanceof Empty empty) return ArrivalResponse.unavailable(Reason.NO_DATA, format(empty.receivedAt()));
        Found found = (Found) answer;

        Predicate<Arrival> direction = direction(queryName, node.get(), query);
        Instant now = clock.instant();
        List<Train> trains = new ArrayList<>();
        for (Arrival arrival : found.arrivals()) {
            // The cached answer has every line of the station: pick this line here.
            if (!subwayId.get().equals(arrival.subwayId()) || !direction.test(arrival)) continue;
            train(arrival, now).ifPresent(trains::add);
        }
        trains.sort(Comparator.comparingLong(ArrivalService::sortKey));
        return new ArrivalResponse(true, null, format(found.receivedAt()), List.copyOf(trains.subList(0, Math.min(query.limit(), trains.size()))));
    }

    /** secondsUntil, else stopsAway x 120 s (sorting only), else last; ties keep the upstream order. */
    private static long sortKey(Train train) {
        if (train.secondsUntil() != null) return train.secondsUntil();
        if (train.stopsAway() != null) return (long) train.stopsAway() * SECONDS_PER_STOP;
        return Long.MAX_VALUE;
    }

    /**
     * nextStationId first: on lines whose station id can be built, statnTid must be that id; elsewhere the RIDE edge
     * to it gives the direction. Then directionCode. updnLine is compared except for LOOP (the loop runs as 하행).
     * Stations in {@link SeoulSubwayIds#NO_DIRECTION_FILTER} and requests without both keep every train of the line.
     * Both the edge direction and directionCode are in seed terms, so they go through
     * {@link SeoulSubwayIds#seoulDirection} before the comparison (no line is flipped since V14, #89).
     */
    private Predicate<Arrival> direction(String queryName, ArrivalStationRepository.Node node, ArrivalQuery query) {
        if (SeoulSubwayIds.NO_DIRECTION_FILTER.contains(queryName)) return arrival -> true;
        DirectionCode direction = query.directionCode();
        if (query.nextStationId() != null) {
            Optional<ArrivalStationRepository.Node> next = stations.node(query.nextStationId(), query.lineId());
            if (next.isPresent()) {
                Optional<String> nextId = SeoulSubwayIds.stationId(next.get().lineCode(), next.get().stationCode());
                if (nextId.isPresent()) return arrival -> nextId.get().equals(arrival.statnTid());
                Optional<DirectionCode> edge = stations.rideDirection(node.id(), next.get().id());
                if (edge.isPresent()) direction = edge.get();
            }
        }
        if (direction == null || direction == DirectionCode.LOOP) return arrival -> true;
        DirectionCode wanted = SeoulSubwayIds.seoulDirection(node.lineCode(), direction);
        return arrival -> wanted == SeoulSubwayIds.direction(arrival.updnLine());
    }

    /**
     * Rule 3: rows older than 5 min are dropped. arvlCd 0/1 -> 0 s; 2 -> dropped; otherwise barvlDt minus the row's age,
     * but a barvlDt of 0, empty or not a number is unknown (null), never 0. "[N]번째 전역" gives stopsAway N; rows more
     * than {@code max-stops-away} stops away are dropped.
     */
    private Optional<Train> train(Arrival arrival, Instant now) {
        Instant received;
        try {
            received = LocalDateTime.parse(arrival.recptnDt() == null ? "" : arrival.recptnDt().strip(), RECEIVED)
                    .atZone(ServiceSlots.ZONE).toInstant();
        } catch (DateTimeParseException exception) {
            return Optional.empty();
        }
        long age = Math.max(0, Duration.between(received, now).getSeconds());
        if (age > MAX_AGE_SECONDS) return Optional.empty();
        String code = arrival.arvlCd() == null ? "" : arrival.arvlCd().strip();
        if ("2".equals(code)) return Optional.empty();
        Integer stopsAway = stopsAway(arrival.arvlMsg2());
        if (stopsAway != null && stopsAway > maxStopsAway) return Optional.empty();
        Integer seconds;
        if ("0".equals(code) || "1".equals(code)) {
            seconds = 0;
        } else {
            Integer barvl = number(arrival.barvlDt());
            // barvlDt 0 with arvlCd 99 comes with "[N]번째 전역": the time is unknown, not "arriving now".
            seconds = barvl == null || barvl == 0 ? null : (int) Math.max(0, barvl - age);
        }
        boolean express = "급행".equals(arrival.btrainSttus())
                || (arrival.trainLineNm() != null && arrival.trainLineNm().contains("(급행)"));
        return Optional.of(new Train(arrival.bstatnNm(), SeoulSubwayIds.direction(arrival.updnLine()), seconds, stopsAway,
                arrival.arvlMsg2(), "1".equals(arrival.lstcarAt()), express));
    }

    private static Integer stopsAway(String message) {
        if (message == null) return null;
        var matcher = STOPS_AWAY.matcher(message);
        return matcher.find() ? Integer.valueOf(matcher.group(1)) : null;
    }

    private static Integer number(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return Integer.parseInt(raw.strip());
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    /** Cached answer, or one upstream call shared by every request for the name that arrives meanwhile. */
    private Answer answer(String queryName) {
        Instant now = clock.instant();
        Entry current = cache.get(queryName);
        if (current != null && !current.expired(now)) return join(current);
        CompletableFuture<Answer> mine = new CompletableFuture<>();
        Entry pending = new Entry(mine, Instant.MAX);
        Entry winner = cache.compute(queryName, (k, old) -> old == null || old.expired(now) ? pending : old);
        if (winner != pending) return join(winner);
        Answer answer = call(queryName);
        mine.complete(answer);
        cache.replace(queryName, pending, new Entry(mine, clock.instant().plus(answer instanceof Found ? ttl : failureTtl)));
        return answer;
    }

    private Answer call(String queryName) {
        long started = System.nanoTime();
        try {
            Fetch fetch = client.fetch(queryName);
            long elapsedMs = (System.nanoTime() - started) / 1_000_000;
            if (fetch instanceof NoData noData) {
                log.info("Seoul arrival {}: INFO-200 (no data) in {} ms", queryName, elapsedMs);
                return new Empty(noData.receivedAt());
            }
            Rows rows = (Rows) fetch;
            log.info("Seoul arrival {}: INFO-000, {} rows in {} ms", queryName, rows.arrivals().size(), elapsedMs);
            return new Found(rows.arrivals(), rows.receivedAt());
        } catch (ArrivalUpstreamException exception) {
            // The message is safe by construction (no URL, no key).
            log.warn("Seoul arrival {}: {}", queryName, exception.getMessage());
            return new Failed();
        } catch (RuntimeException exception) {
            log.warn("Seoul arrival {}: failed ({})", queryName, exception.getClass().getSimpleName());
            return new Failed();
        }
    }

    private static Answer join(Entry entry) {
        try {
            return entry.answer().join();
        } catch (CompletionException exception) {
            return new Failed();
        }
    }

    private static String format(Instant instant) {
        return instant.truncatedTo(ChronoUnit.SECONDS).atZone(ServiceSlots.ZONE).toOffsetDateTime()
                .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }
}
