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
 * Next trains at one station on one line (docs/arrivals-86.md). Upstream answers are cached per (query name,
 * subwayId): {@code cache-ttl} (15 s) for rows, {@code failure-cache-ttl} (5 s) for no data and errors, with one
 * upstream call per key at a time. Remaining seconds are recomputed from each row's recptnDt on every request.
 */
@Slf4j
@Service
public class ArrivalService {
    static final long MAX_AGE_SECONDS = 300;
    private static final DateTimeFormatter RECEIVED = new DateTimeFormatterBuilder()
            .appendPattern("yyyy-MM-dd HH:mm:ss").optionalStart().appendFraction(ChronoField.NANO_OF_SECOND, 0, 9, true)
            .optionalEnd().toFormatter();

    private final ArrivalStationRepository stations;
    private final ArrivalStationNames names;
    private final SeoulArrivalClient client;
    private final Duration ttl;
    private final Duration failureTtl;
    private final Clock clock;
    private final ConcurrentHashMap<Key, Entry> cache = new ConcurrentHashMap<>();

    @Autowired
    public ArrivalService(ArrivalStationRepository stations, ArrivalStationNames names, SeoulArrivalClient client,
                          @Value("${linkro.arrival.cache-ttl:15s}") Duration ttl,
                          @Value("${linkro.arrival.failure-cache-ttl:5s}") Duration failureTtl) {
        this(stations, names, client, ttl, failureTtl, Clock.systemUTC());
    }

    ArrivalService(ArrivalStationRepository stations, ArrivalStationNames names, SeoulArrivalClient client,
                   Duration ttl, Duration failureTtl, Clock clock) {
        this.stations = stations;
        this.names = names;
        this.client = client;
        this.ttl = ttl;
        this.failureTtl = failureTtl;
        this.clock = clock;
    }

    private record Key(String queryName, String subwayId) {}

    /** One upstream answer for a key: rows already narrowed to the subwayId, no data, or a failure. */
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
        Answer answer = answer(new Key(queryName, subwayId.get()));
        if (answer instanceof Failed) return ArrivalResponse.unavailable(Reason.UPSTREAM_ERROR, null);
        if (answer instanceof Empty empty) return ArrivalResponse.unavailable(Reason.NO_DATA, format(empty.receivedAt()));
        Found found = (Found) answer;

        Predicate<Arrival> direction = direction(queryName, node.get(), query);
        Instant now = clock.instant();
        List<Train> trains = new ArrayList<>();
        for (Arrival arrival : found.arrivals()) {
            if (!direction.test(arrival)) continue;
            train(arrival, now).ifPresent(trains::add);
        }
        trains.sort(Comparator.comparing(Train::secondsUntil, Comparator.nullsLast(Comparator.naturalOrder())));
        return new ArrivalResponse(true, null, format(found.receivedAt()), List.copyOf(trains.subList(0, Math.min(query.limit(), trains.size()))));
    }

    /**
     * nextStationId first: on lines whose station id can be built, statnTid must be that id; elsewhere the RIDE edge
     * to it gives the direction. Then directionCode. updnLine is compared except for LOOP (the loop runs as 하행).
     * Stations in {@link SeoulSubwayIds#NO_DIRECTION_FILTER} and requests without both keep every train of the line.
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
        DirectionCode wanted = direction;
        return arrival -> wanted == SeoulSubwayIds.direction(arrival.updnLine());
    }

    /** Rule 3: arvlCd 0/1 -> 0 s, 2 -> dropped, otherwise barvlDt minus the row's age; rows older than 5 min dropped. */
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
        Integer seconds;
        if ("0".equals(code) || "1".equals(code)) {
            seconds = 0;
        } else {
            Integer barvl = number(arrival.barvlDt());
            seconds = barvl == null ? null : (int) Math.max(0, barvl - age);
        }
        return Optional.of(new Train(arrival.bstatnNm(), SeoulSubwayIds.direction(arrival.updnLine()), seconds,
                arrival.arvlMsg2(), "1".equals(arrival.lstcarAt() == null ? null : arrival.lstcarAt().strip())));
    }

    private static Integer number(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return Integer.parseInt(raw.strip());
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    /** Cached answer, or one upstream call shared by every request for the key that arrives meanwhile. */
    private Answer answer(Key key) {
        Instant now = clock.instant();
        Entry current = cache.get(key);
        if (current != null && !current.expired(now)) return join(current);
        CompletableFuture<Answer> mine = new CompletableFuture<>();
        Entry pending = new Entry(mine, Instant.MAX);
        Entry winner = cache.compute(key, (k, old) -> old == null || old.expired(now) ? pending : old);
        if (winner != pending) return join(winner);
        Answer answer = call(key);
        mine.complete(answer);
        cache.replace(key, pending, new Entry(mine, clock.instant().plus(answer instanceof Found ? ttl : failureTtl)));
        return answer;
    }

    private Answer call(Key key) {
        long started = System.nanoTime();
        try {
            Fetch fetch = client.fetch(key.queryName());
            long elapsedMs = (System.nanoTime() - started) / 1_000_000;
            if (fetch instanceof NoData noData) {
                log.info("Seoul arrival {} {}: INFO-200 (no data) in {} ms", key.queryName(), key.subwayId(), elapsedMs);
                return new Empty(noData.receivedAt());
            }
            Rows rows = (Rows) fetch;
            List<Arrival> line = rows.arrivals().stream().filter(arrival -> key.subwayId().equals(arrival.subwayId())).toList();
            log.info("Seoul arrival {} {}: INFO-000, {} of {} rows in {} ms", key.queryName(), key.subwayId(), line.size(),
                    rows.arrivals().size(), elapsedMs);
            return new Found(line, rows.receivedAt());
        } catch (ArrivalUpstreamException exception) {
            // The message is safe by construction (no URL, no key).
            log.warn("Seoul arrival {} {}: {}", key.queryName(), key.subwayId(), exception.getMessage());
            return new Failed();
        } catch (RuntimeException exception) {
            log.warn("Seoul arrival {} {}: failed ({})", key.queryName(), key.subwayId(), exception.getClass().getSimpleName());
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
