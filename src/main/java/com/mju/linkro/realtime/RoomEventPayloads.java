package com.mju.linkro.realtime;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.mju.linkro.room.domain.RoomStatus;
import com.mju.linkro.room.dto.RoomResponses.Station;
import java.time.Instant;
import java.util.UUID;

/** Payloads of the events that have no REST response object to reuse (API chapter 11). */
public final class RoomEventPayloads {
    private RoomEventPayloads() {}

    public record MemberJoined(UUID userId, String nickname) {}

    /** {@code newOwnerId} is left out when the owner did not change. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record MemberLeft(UUID userId, UUID newOwnerId) {}

    public record OwnerChanged(UUID previousOwnerId, UUID newOwnerId) {}

    public record DepartureUpdated(UUID userId, Station station) {}

    /** The room summary of API 7.2 without {@code myRole}, which differs per member. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record RoomUpdated(UUID id, String name, RoomStatus status, long memberCount, int maxMembers, Instant appointmentAt,
                              Station selectedStation) {}

    public record ProposalDeleted(UUID proposalId) {}

    public record ProposalVoteChanged(UUID proposalId, long voteCount, UUID userId) {}

    /** Both fields are null when the selected station is cleared. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record SelectedStationChanged(Station station, Instant confirmedAt) {}

    public record RecommendationCreated(UUID runId) {}
}
