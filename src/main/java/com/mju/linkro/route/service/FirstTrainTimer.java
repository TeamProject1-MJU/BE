package com.mju.linkro.route.service;

import com.mju.linkro.route.service.RouteDraft.StepDraft;
import com.mju.linkro.subway.api.DayType;
import com.mju.linkro.subway.api.PathStop;
import com.mju.linkro.subway.timetable.TimetableClock;
import com.mju.linkro.subway.timetable.TimetableClock.ServiceTime;
import com.mju.linkro.subway.timetable.TrainTimetable;
import com.mju.linkro.subway.timetable.TrainTimetable.Departure;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * Rides a route that is already fixed (Kakao or INTERNAL: stations, lines and fare stay as they are) on the train
 * timetable to tell whether a train runs it at the departure time (#120).
 *
 * <p>Every consecutive stop pair of a SUBWAY step is a RIDE edge. The first pair takes the next train leaving at or after
 * the departure plus the time to the first stop; the next pairs stay on that train when it goes on ({@code rideOf}),
 * otherwise take the next train; after a step, the next SUBWAY step boards after the walks in between. When today's
 * trains cannot finish the route (after the last train of any of its edges), it is ridden again from the first trains
 * of the next service day.
 *
 * <p>No service: the route was ridden on the next service day, or the train boarded is the first train of the boarding
 * edge that day and leaves after the departure. The new departure is that boarding minus the time to the first stop;
 * travel time is not changed (arrivalAt stays departure + totalTimeSec).
 */
final class FirstTrainTimer {
    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

    /**
     * @param departure the departure to use: the given one when the train runs, else the first-train one
     * @param noService true when the departure was moved to the first train
     * @param boardAt   departure of the first train boarded (meaningful with {@code noService})
     */
    record Timing(Instant departure, boolean noService, Instant boardAt) {}

    private final TrainTimetable timetable;
    private final TimetableClock clock;

    FirstTrainTimer(TrainTimetable timetable, TimetableClock clock) {
        this.timetable = Objects.requireNonNull(timetable, "timetable");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Empty when the route has no SUBWAY stop or cannot be ridden on the timetable on either day: nothing is judged. */
    Optional<Timing> time(RouteDraft draft, Instant departure) {
        List<StepDraft> steps = draft.steps();
        Optional<PathStop> firstStop = steps.stream().filter(step -> RouteDraft.SUBWAY.equals(step.type()))
                .flatMap(step -> step.stops().stream()).findFirst();
        if (firstStop.isEmpty()) return Optional.empty();
        int toFirstStop = firstStop.get().offsetSec();
        ServiceTime service = clock.serviceTimeOf(LocalDateTime.ofInstant(departure, ZONE));
        int start = service.secondOfServiceDay() + toFirstStop;

        Ride today = ride(steps, service.dayType(), start);
        if (today != null) {
            if (!today.firstTrainOfEdge() || today.boardSec() <= start) return Optional.of(new Timing(departure, false, departure));
            return Optional.of(moved(service.serviceDate(), today.boardSec(), toFirstStop));
        }
        LocalDate next = service.serviceDate().plusDays(1);
        Ride tomorrow = ride(steps, clock.dayTypeOf(next), 0);
        return tomorrow == null ? Optional.empty() : Optional.of(moved(next, tomorrow.boardSec(), toFirstStop));
    }

    /**
     * The no-service judgement of {@link #time} for a route already ridden on the timetable (#130), without moving
     * anything: its times are timetable times already. True when the first train belongs to a later service day than
     * {@code requested}, or is the first train of the route's first edge on its service day and the route leaves after
     * {@code requested}. False when the first edge is not in the timetable.
     *
     * @param boardAt departure of the first train
     * @param leaveAt when the route leaves (boarding minus the station access time)
     */
    boolean boardsFirstTrainAfter(RouteDraft draft, Instant boardAt, Instant leaveAt, Instant requested) {
        Optional<StepDraft> ride = draft.steps().stream()
                .filter(step -> RouteDraft.SUBWAY.equals(step.type()) && step.stops().size() >= 2).findFirst();
        if (ride.isEmpty()) return false;
        ServiceTime board = clock.serviceTimeOf(LocalDateTime.ofInstant(boardAt, ZONE));
        ServiceTime request = clock.serviceTimeOf(LocalDateTime.ofInstant(requested, ZONE));
        if (board.serviceDate().isAfter(request.serviceDate())) return true;
        if (!leaveAt.isAfter(requested)) return false;
        PathStop from = ride.get().stops().get(0);
        PathStop to = ride.get().stops().get(1);
        OptionalLong edge = timetable.rideEdgeId(from.lineId(), from.stationId(), to.stationId());
        if (edge.isEmpty()) return false;
        TrainTimetable.Rides rides = timetable.rides(edge.getAsLong(), board.dayType());
        // The train boarded leaves at boardAt: it is the day's first one when no train of the edge leaves before it.
        return rides != null && rides.next(board.secondOfServiceDay()) == 0;
    }

    /** Start (03:00) of the service day after the one {@code at} belongs to. */
    Instant nextServiceDayStart(Instant at) {
        LocalDate next = clock.serviceTimeOf(LocalDateTime.ofInstant(at, ZONE)).serviceDate().plusDays(1);
        return clock.startOf(next).atZone(ZONE).toInstant();
    }

    private static Timing moved(LocalDate serviceDate, int boardSec, int toFirstStop) {
        Instant board = serviceDate.atStartOfDay(ZONE).toInstant().plusSeconds(boardSec);
        return new Timing(board.minusSeconds(toFirstStop), true, board);
    }

    /** @param firstTrainOfEdge the first boarding is the first train of its edge on that day type */
    private record Ride(int boardSec, boolean firstTrainOfEdge) {}

    /** The route on trains of one day type from {@code start} (second of the service day), or null when it cannot finish. */
    private Ride ride(List<StepDraft> steps, DayType day, int start) {
        int time = start;
        Integer boardSec = null;
        boolean firstTrain = false;
        for (StepDraft step : steps) {
            if (!RouteDraft.SUBWAY.equals(step.type())) {
                // Walks before the first ride are already in the first stop's offset.
                if (boardSec != null) time += step.durationSec();
                continue;
            }
            Departure previous = null;
            List<PathStop> stops = step.stops();
            for (int i = 0; i + 1 < stops.size(); i++) {
                PathStop from = stops.get(i);
                PathStop to = stops.get(i + 1);
                OptionalLong edge = timetable.rideEdgeId(from.lineId(), from.stationId(), to.stationId());
                if (edge.isEmpty()) return null;
                TrainTimetable.Rides rides = timetable.rides(edge.getAsLong(), day);
                if (rides == null) return null;
                Departure ride = previous == null ? null
                        : timetable.rideOf(edge.getAsLong(), day, previous.train(), time).orElse(null);
                if (ride == null) {
                    int index = rides.next(time);
                    if (index < 0) return null;
                    ride = rides.departure(index);
                    if (boardSec == null) {
                        boardSec = ride.departSec();
                        firstTrain = index == 0;
                    }
                }
                time = ride.arriveSec();
                previous = ride;
            }
        }
        return boardSec == null ? null : new Ride(boardSec, firstTrain);
    }
}
