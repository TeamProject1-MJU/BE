package com.mju.linkro.recommendation.service;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.mju.linkro.recommendation.dto.RecommendationResponse.Congestion;
import com.mju.linkro.recommendation.dto.RecommendationResponse.ParticipantTime;
import com.mju.linkro.recommendation.dto.RecommendationResponse.Segment;
import com.mju.linkro.recommendation.dto.RecommendationResponse.StationRef;
import java.util.List;
import java.util.UUID;

/**
 * One element of recommendation_results.trips (ERD v1.13): the participantTimes snapshot with the departure station
 * kept as an id only. The API's departureStation{id, name} is filled in from the id when the row is read.
 *
 * <p>{@code legacyDepartureStation} only reads rows stored before this change (dev data with a full
 * departureStation object); it is never written.
 */
record StoredTrip(
        UUID userId,
        String nickname,
        Long departureStationId,
        int travelMinutes,
        int transferCount,
        int fareWon,
        int distanceM,
        String departureAt,
        Congestion congestion,
        List<Segment> segments,
        @JsonProperty(value = "departureStation", access = JsonProperty.Access.WRITE_ONLY) StationRef legacyDepartureStation) {

    static StoredTrip of(ParticipantTime trip) {
        return new StoredTrip(trip.userId(), trip.nickname(), trip.departureStation().id(), trip.travelMinutes(),
                trip.transferCount(), trip.fareWon(), trip.distanceM(), trip.departureAt(), trip.congestion(), trip.segments(), null);
    }

    long stationId() {
        if (departureStationId != null) {
            return departureStationId;
        }
        if (legacyDepartureStation != null) {
            return legacyDepartureStation.id();
        }
        throw new IllegalStateException("Stored trip has no departure station");
    }

    ParticipantTime toParticipantTime(StationRef departureStation) {
        return new ParticipantTime(userId, nickname, departureStation, travelMinutes, transferCount, fareWon, distanceM,
                departureAt, congestion, segments);
    }
}
