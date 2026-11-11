package com.mju.linkro.room.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.*;

public final class StationProposalResponses {
    private StationProposalResponses() {}
    @Schema(name = "ProposalStation", requiredProperties = {"id", "name"})
    public record Station(long id, String name) {}
    @Schema(name = "ProposalAuthor", requiredProperties = {"userId", "nickname"})
    public record Author(UUID userId, String nickname) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    @Schema(name = "StationProposalCreated", requiredProperties = {"id", "station", "proposedBy", "resultId", "createdAt"})
    public record Created(UUID id, Station station, Author proposedBy, @Schema(types = {"string", "null"}, format = "uuid") UUID resultId, Instant createdAt) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    @Schema(name = "StationProposalItem", requiredProperties = {"id", "station", "proposedBy", "resultId", "isSelected", "voteCount", "votedByMe", "createdAt"})
    public record Item(UUID id, Station station, Author proposedBy, @Schema(types = {"string", "null"}, format = "uuid") UUID resultId,
                       boolean isSelected, long voteCount, boolean votedByMe, Instant createdAt) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    @Schema(name = "StationProposalListing", requiredProperties = {"selectedStation", "maxProposals", "items"})
    public record Listing(RoomResponses.Station selectedStation, int maxProposals, List<Item> items) {}
    public record CreatedEnvelope(boolean success, Created data) {}
    public record ListEnvelope(boolean success, Listing data) {}
}
