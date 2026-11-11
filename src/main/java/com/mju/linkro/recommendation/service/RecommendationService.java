package com.mju.linkro.recommendation.service;

import com.github.f4b6a3.uuid.UuidCreator;
import com.mju.linkro.common.exception.BusinessException;
import com.mju.linkro.common.exception.ErrorCode;
import com.mju.linkro.recommendation.dto.RecommendationRequest;
import com.mju.linkro.recommendation.dto.RecommendationResponse;
import com.mju.linkro.recommendation.dto.RecommendationResponse.*;
import com.mju.linkro.recommendation.event.RecommendationCreatedEvent;
import com.mju.linkro.recommendation.repository.RecommendationRepository;
import com.mju.linkro.recommendation.repository.RecommendationRepository.ResultRow;
import com.mju.linkro.recommendation.repository.RecommendationRepository.RunRow;
import com.mju.linkro.recommendation.service.Candidate.Member;
import com.mju.linkro.recommendation.service.Candidate.Trip;
import com.mju.linkro.recommendation.service.ConstraintResolver.Resolved;
import com.mju.linkro.recommendation.service.ExplanationWriter.Explanation;
import com.mju.linkro.recommendation.service.ExplanationWriter.Facts;
import com.mju.linkro.recommendation.service.RecommendationEngine.Evaluation;
import com.mju.linkro.room.domain.MeetingRoom;
import com.mju.linkro.room.repository.RoomReadRepository;
import com.mju.linkro.room.repository.RoomReadRepository.MemberRow;
import com.mju.linkro.room.service.RoomAccess;
import com.mju.linkro.subway.api.CongestionLookup;
import com.mju.linkro.subway.api.CongestionResult;
import com.mju.linkro.subway.api.PathStep;
import com.mju.linkro.subway.api.RoutePath;
import com.mju.linkro.subway.api.StationInfo;
import com.mju.linkro.subway.api.StationLookup;
import com.mju.linkro.subway.api.StepType;
import com.mju.linkro.subway.api.TimedLeg;
import com.mju.linkro.subway.api.TimedRoute;
import com.mju.linkro.subway.congestion.ServiceSlots;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Middle-station recommendation (API 8.1~8.3). Deliberately not transactional: the route calculation and the AI calls
 * hold no database connection, and the run is saved by one short transaction in {@link RecommendationRepository#save}.
 */
@Slf4j
@Service
public class RecommendationService {
    static final String NO_RESULT_MESSAGE = "조건에 맞는 중간역이 없습니다. 조건을 완화해 보세요.";
    static final String PARSE_FAILED_MESSAGE = "자연어 조건을 해석하지 못해 적용하지 못했습니다";
    /** %d: stations left out because a member cannot arrive by the appointment on the timetable's trains (#96). */
    static final String UNREACHABLE_BY_TRAIN_MESSAGE = "약속 시각까지 열차로 도착할 수 없는 역 %d곳을 제외했습니다";

    private static final DateTimeFormatter OFFSET_TIME = DateTimeFormatter.ISO_OFFSET_DATE_TIME;
    private static final TypeReference<List<StoredTrip>> TRIPS = new TypeReference<>() {};
    private static final TypeReference<List<NearbyAlternative>> NEARBY = new TypeReference<>() {};

    private final RoomAccess rooms;
    private final RoomReadRepository roomReads;
    private final RecommendationRepository repository;
    private final ConstraintResolver constraints;
    private final RecommendationEngine engine;
    private final ExplanationWriter explanations;
    private final StationLookup stations;
    private final CongestionLookup congestion;
    private final ApplicationEventPublisher events;
    private final JsonMapper json;
    private final Clock clock;

    @Autowired
    public RecommendationService(RoomAccess rooms, RoomReadRepository roomReads, RecommendationRepository repository,
                                 ConstraintResolver constraints, RecommendationEngine engine, ExplanationWriter explanations,
                                 StationLookup stations, CongestionLookup congestion, ApplicationEventPublisher events,
                                 JsonMapper json) {
        this(rooms, roomReads, repository, constraints, engine, explanations, stations, congestion, events, json, Clock.systemUTC());
    }

    RecommendationService(RoomAccess rooms, RoomReadRepository roomReads, RecommendationRepository repository,
                          ConstraintResolver constraints, RecommendationEngine engine, ExplanationWriter explanations,
                          StationLookup stations, CongestionLookup congestion, ApplicationEventPublisher events,
                          JsonMapper json, Clock clock) {
        this.rooms = rooms;
        this.roomReads = roomReads;
        this.repository = repository;
        this.constraints = constraints;
        this.engine = engine;
        this.explanations = explanations;
        this.stations = stations;
        this.congestion = congestion;
        this.events = events;
        this.json = json;
        this.clock = clock;
    }

    /** API 8.1. */
    public RecommendationResponse create(UUID userId, UUID roomId, RecommendationRequest body) {
        RecommendationRequest request = RecommendationRequest.orEmpty(body);
        MeetingRoom room = rooms.requireMember(roomId, userId);
        List<MemberRow> rows = roomReads.members(List.of(roomId));
        request.validate(userIds(rows));

        // Both checks come before any calculation and leave no run behind.
        if (rows.size() < 2) {
            throw new BusinessException(ErrorCode.TOO_FEW_MEMBERS);
        }
        List<String> missing = rows.stream().filter(row -> row.stationId() == null)
                .map(row -> row.userId().toString()).toList();
        if (!missing.isEmpty()) {
            throw new BusinessException(ErrorCode.DEPARTURE_MISSING, Map.of("missingUserIds", missing));
        }
        List<Member> members = rows.stream()
                .map(row -> new Member(row.userId(), row.nickname(), row.stationId())).toList();

        Resolved resolved = constraints.resolve(request, members);
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        Evaluation evaluation = engine.evaluate(members, resolved.applied(), room.getAppointmentAt(), now);
        UUID runId = UuidCreator.getTimeOrderedEpoch();
        // Everything is computed first, then one AI call explains all criteria of this recommendation.
        record Drafted(Criterion criterion, Candidate best, StationInfo station, List<ParticipantTime> trips,
                       List<NearbyAlternative> nearby, Facts facts) {}
        List<Drafted> drafted = new ArrayList<>();
        for (Criterion criterion : Criterion.values()) {
            Optional<Candidate> best = evaluation.best(criterion);
            if (best.isEmpty()) {
                continue;
            }
            StationInfo station = stations.require(best.get().stationId());
            List<ParticipantTime> trips = participantTimes(best.get(), room.getAppointmentAt(), now);
            drafted.add(new Drafted(criterion, best.get(), station, trips, nearby(best.get(), evaluation),
                    facts(criterion, trips, best.get())));
        }
        Map<Criterion, Explanation> explained = explanations.explain(
                drafted.stream().map(item -> new ExplanationWriter.Item(item.facts(), item.station().name())).toList());
        List<ResultRow> results = new ArrayList<>();
        for (Drafted item : drafted) {
            Explanation explanation = explained.get(item.criterion());
            results.add(new ResultRow(UuidCreator.getTimeOrderedEpoch(), runId, item.criterion().name(), item.station().id(),
                    metric(item.criterion(), item.best()), write(item.trips().stream().map(StoredTrip::of).toList()), write(item.nearby()),
                    explanation.text(), explanation.source()));
        }

        RunCriteria criteria = new RunCriteria(request.text(), request.filter(), resolved.applied(),
                resolved.parseFailed(), room.getAppointmentAt() == null ? null : room.getAppointmentAt().toString(),
                rows.stream().map(row -> new RunCriteria.Participant(row.userId(), row.stationId())).toList(),
                evaluation.unreachableByTrain());
        RunRow run = new RunRow(runId, roomId, userId, write(criteria), now);
        repository.save(run, results);
        publish(new RecommendationCreatedEvent(roomId, runId, userId, now));

        String dropped = resolved.dropped().isEmpty() ? null : "적용하지 못한 조건: " + String.join(", ", resolved.dropped()) + ".";
        // The response is read back like GET does, so the stale fields come from the room as it is now.
        MeetingRoom current = rooms.requireMember(roomId, userId);
        return render(run, results, true, current, roomReads.members(List.of(roomId)), dropped);
    }

    /** API 8.2: the room's latest run, or null (data: null) when it has none. */
    public RecommendationResponse latest(UUID userId, UUID roomId) {
        MeetingRoom room = rooms.requireMember(roomId, userId);
        return repository.latest(roomId)
                .map(run -> render(run, repository.results(run.id()), true, room, roomReads.members(List.of(roomId)), null))
                .orElse(null);
    }

    /** API 8.3: a run of this room. isStale and staleReasons are only computed for the latest run. */
    public RecommendationResponse byRun(UUID userId, UUID roomId, UUID runId) {
        MeetingRoom room = rooms.requireMember(roomId, userId);
        // A run of another room is the same RUN_NOT_FOUND as a missing one, so run ids cannot be probed (IDOR).
        RunRow run = repository.find(runId).filter(found -> found.roomId().equals(roomId))
                .orElseThrow(() -> new BusinessException(ErrorCode.RUN_NOT_FOUND));
        boolean latest = repository.latest(roomId).map(RunRow::id).filter(runId::equals).isPresent();
        return render(run, repository.results(runId), latest, room, roomReads.members(List.of(roomId)), null);
    }

    private RecommendationResponse render(RunRow run, List<ResultRow> rows, boolean latest, MeetingRoom room,
                                          List<MemberRow> members, String extraMessage) {
        RunCriteria criteria = read(run.criteriaJson(), RunCriteria.class);
        Boolean stale = null;
        List<String> reasons = null;
        if (latest) {
            reasons = StaleCheck.reasons(criteria, room.getAppointmentAt(), members);
            stale = !reasons.isEmpty();
        }
        List<Result> results = rows.stream().sorted(Comparator.comparing(row -> Criterion.valueOf(row.criterion())))
                .map(row -> {
                    List<ParticipantTime> trips = read(row.tripsJson(), TRIPS).stream().map(this::withStationName).toList();
                    StationInfo station = stations.require(row.stationId());
                    return new Result(row.id().toString(), row.criterion(),
                            new RecommendedStation(station.id(), station.name(), station.latitude(), station.longitude(), station.supported()),
                            metrics(trips), trips, read(row.nearbyJson(), NEARBY), row.reason(), row.reasonSource());
                }).toList();
        List<String> messages = new ArrayList<>();
        if (criteria.conditionParseFailed()) {
            messages.add(PARSE_FAILED_MESSAGE);
        }
        if (extraMessage != null) {
            messages.add(extraMessage);
        }
        if (results.isEmpty()) {
            if (criteria.unreachableByTrain() != null && criteria.unreachableByTrain() > 0) {
                messages.add(UNREACHABLE_BY_TRAIN_MESSAGE.formatted(criteria.unreachableByTrain()));
            }
            messages.add(NO_RESULT_MESSAGE);
        }
        // Each message is a sentence of its own; only the last one is returned without the joining period.
        for (int i = 0; i < messages.size() - 1; i++) {
            if (!messages.get(i).endsWith(".")) {
                messages.set(i, messages.get(i) + ".");
            }
        }
        return new RecommendationResponse(run.id().toString(), time(run.createdAt()), stale, reasons,
                criteria.conditionText(), criteria.appliedConstraints() == null ? AppliedConstraints.none() : criteria.appliedConstraints(),
                messages.isEmpty() ? null : String.join(" ", messages), results);
    }

    /** trips store only the departure station id; the name is looked up here. */
    private ParticipantTime withStationName(StoredTrip stored) {
        StationInfo origin = stations.require(stored.stationId());
        return stored.toParticipantTime(new StationRef(origin.id(), origin.name()));
    }

    private List<ParticipantTime> participantTimes(Candidate best, Instant appointmentAt, Instant now) {
        List<ParticipantTime> times = new ArrayList<>();
        for (Trip trip : best.trips()) {
            RoutePath path = trip.path();
            TimedRoute timed = trip.timed();
            Instant departAt;
            Instant firstStop;
            if (timed != null) {
                // Real trains: leave in time for the first train; stop offsets count from its departure.
                departAt = timed.leaveAt();
                firstStop = timed.boardAt();
            } else {
                // 8.1: departure = appointment − travel minutes; without an appointment the request time is the departure.
                departAt = appointmentAt == null ? now : appointmentAt.minus(trip.minutes(), ChronoUnit.MINUTES);
                firstStop = departAt;
            }
            CongestionResult result = congestion.congestionOf(path, firstStop);
            Congestion shown = result.supported() ? new Congestion(result.pct().intValue(), result.level(), result.levelLabel()) : null;
            StationInfo origin = stations.require(trip.member().departureStationId());
            times.add(new ParticipantTime(trip.member().userId(), trip.member().nickname(),
                    new StationRef(origin.id(), origin.name()), trip.minutes(), path.transferCount(), trip.fareWon(),
                    path.rideDistanceM(), time(departAt), shown, segments(path), boardingTrain(timed), legs(timed), trip.source()));
        }
        return times;
    }

    private static BoardingTrain boardingTrain(TimedRoute timed) {
        if (timed == null) {
            return null;
        }
        TimedLeg first = timed.legs().get(0);
        return new BoardingTrain(first.lineId(), first.lineName(), first.fromStationId(), first.fromStationName(),
                first.direction().name(), time(first.boardAt()), first.terminal(), first.trainCode());
    }

    private static List<Leg> legs(TimedRoute timed) {
        if (timed == null) {
            return List.of();
        }
        return timed.legs().stream().map(leg -> new Leg(leg.lineId(), leg.lineName(), leg.trainCode(), leg.terminal(),
                leg.direction().name(), leg.fromStationName(), leg.toStationName(), time(leg.boardAt()), time(leg.alightAt()),
                leg.stationCount(), leg.walkSec(), leg.waitSec())).toList();
    }

    private static List<Segment> segments(RoutePath path) {
        return path.steps().stream().filter(step -> step.type() == StepType.RIDE)
                .map((PathStep step) -> new Segment(step.lineId(), step.lineName(), step.color(), step.direction().name(),
                        step.fromStationName(), step.toStationName(), step.stationCount())).toList();
    }

    /** Stations next to the best one that are also candidates (everyone can reach them and the constraints hold). */
    private List<NearbyAlternative> nearby(Candidate best, Evaluation evaluation) {
        List<NearbyAlternative> nearby = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (NearbyStations.Neighbour neighbour : NearbyStations.classify(repository.neighbours(best.stationId()))) {
            Candidate candidate = evaluation.candidates().get(neighbour.stationId());
            if (candidate != null && seen.add(neighbour.stationId() + ":" + neighbour.lineId() + ":" + neighbour.relation())) {
                nearby.add(new NearbyAlternative(new StationRef(neighbour.stationId(), neighbour.name()), neighbour.lineId(),
                        neighbour.relation(), "최적역에서 1정거장", candidate.maxMinutes(), candidate.totalFareWon()));
            }
        }
        return nearby;
    }

    /** Metrics of 8.1, calculated from the stored participantTimes. */
    static Metrics metrics(List<ParticipantTime> trips) {
        int count = trips.size();
        int max = trips.stream().mapToInt(ParticipantTime::travelMinutes).max().orElse(0);
        int min = trips.stream().mapToInt(ParticipantTime::travelMinutes).min().orElse(0);
        double average = trips.stream().mapToInt(ParticipantTime::travelMinutes).sum() / (double) Math.max(count, 1);
        long fare = trips.stream().mapToLong(ParticipantTime::fareWon).sum();
        return new Metrics(Math.round(average * 10) / 10.0, max - min, max, (int) Math.round(fare / (double) Math.max(count, 1)),
                trips.stream().mapToInt(ParticipantTime::transferCount).sum());
    }

    private static Facts facts(Criterion criterion, List<ParticipantTime> trips, Candidate best) {
        return new Facts(criterion, trips.size(), metrics(trips), best.totalFareWon(), best.totalDistanceM());
    }

    /** recommendation_results.metric_value: TIME minutes, FARE won, DISTANCE metres. */
    private static BigDecimal metric(Criterion criterion, Candidate best) {
        return BigDecimal.valueOf(switch (criterion) {
            case TIME -> best.maxMinutes();
            case FARE -> best.totalFareWon();
            case DISTANCE -> best.totalDistanceM();
        });
    }

    private void publish(RecommendationCreatedEvent event) {
        try {
            events.publishEvent(event);
        } catch (RuntimeException exception) {
            // The run is saved; a failing listener must not turn a good recommendation into an error.
            log.error("RECOMMENDATION_CREATED listener failed for run {}", event.runId(), exception);
        }
    }

    private static Set<UUID> userIds(List<MemberRow> rows) {
        Set<UUID> ids = new HashSet<>();
        rows.forEach(row -> ids.add(row.userId()));
        return ids;
    }

    private static String time(Instant instant) {
        return instant.truncatedTo(ChronoUnit.SECONDS).atZone(ServiceSlots.ZONE).toOffsetDateTime().format(OFFSET_TIME);
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Cannot write recommendation JSON", exception);
        }
    }

    private <T> T read(String text, Class<T> type) {
        try {
            return json.readValue(text, type);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Cannot read recommendation JSON", exception);
        }
    }

    private <T> T read(String text, TypeReference<T> type) {
        try {
            return json.readValue(text, type);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Cannot read recommendation JSON", exception);
        }
    }
}
