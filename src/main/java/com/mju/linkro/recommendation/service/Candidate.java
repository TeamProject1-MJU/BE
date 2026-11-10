package com.mju.linkro.recommendation.service;

import com.mju.linkro.subway.api.RoutePath;
import java.util.List;
import java.util.UUID;

/**
 * A station every participant can reach, with each participant's route to it (our graph, minimum time).
 * The totals are derived once so the criteria only compare numbers.
 */
public final class Candidate {
    /** @param minutes route time in whole minutes, rounded up so a limit is never exceeded by a partial minute */
    public record Trip(Member member, RoutePath path, int minutes, int fareWon) {}

    public record Member(UUID userId, String nickname, long departureStationId) {}

    private final long stationId;
    private final List<Trip> trips;
    private final int maxMinutes;
    private final int minMinutes;
    private final int sumMinutes;
    private final int totalFareWon;
    private final int totalDistanceM;
    private final int totalTransfers;

    public Candidate(long stationId, List<Trip> trips) {
        this.stationId = stationId;
        this.trips = List.copyOf(trips);
        this.maxMinutes = trips.stream().mapToInt(Trip::minutes).max().orElse(0);
        this.minMinutes = trips.stream().mapToInt(Trip::minutes).min().orElse(0);
        this.sumMinutes = trips.stream().mapToInt(Trip::minutes).sum();
        this.totalFareWon = trips.stream().mapToInt(Trip::fareWon).sum();
        this.totalDistanceM = trips.stream().mapToInt(trip -> trip.path().rideDistanceM()).sum();
        this.totalTransfers = trips.stream().mapToInt(trip -> trip.path().transferCount()).sum();
    }

    public long stationId() { return stationId; }
    public List<Trip> trips() { return trips; }
    public int maxMinutes() { return maxMinutes; }
    public int spreadMinutes() { return maxMinutes - minMinutes; }
    public int sumMinutes() { return sumMinutes; }
    public int totalFareWon() { return totalFareWon; }
    public int totalDistanceM() { return totalDistanceM; }
    public int totalTransfers() { return totalTransfers; }
}
