package com.mju.linkro.subway.api;

import java.time.Instant;
import java.util.List;

/**
 * A route ridden on real trains of the timetable (#96).
 *
 * <p>{@code path} is the same route as a {@link RoutePath}, so fares, distances, transfers, segments and congestion
 * work as for graph routes. Its steps sum to {@code alightAt − boardAt} of the whole route: a TRANSFER step lasts from
 * alighting to the next boarding (walk and wait), and a wait for another train of the same line without a transfer
 * is inside the following RIDE step. Stop offsets are seconds after {@link #boardAt()}.
 *
 * @param leaveAt  when the member leaves: first boarding minus the station access time
 * @param boardAt  departure of the first train
 * @param arriveAt arrival of the last train at the destination
 */
public record TimedRoute(RoutePath path, List<TimedLeg> legs, Instant leaveAt, Instant boardAt, Instant arriveAt) {

    public TimedRoute {
        legs = List.copyOf(legs);
    }
}
