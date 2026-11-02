package com.mju.linkro.room.dto;

import com.mju.linkro.room.domain.*;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.*;

public final class RoomResponses {
    private RoomResponses() {}
    public record Created(UUID id, String roomCode, String name, int maxMembers,
                          RoomStatus status, UUID ownerId,
                          @Schema(types = {"string", "null"}, format = "date-time") Instant appointmentAt) {}
    public record Summary(UUID id, String name, RoomStatus status, RoomRole myRole,
                          long memberCount, int maxMembers,
                          @Schema(types = {"string", "null"}, format = "date-time") Instant appointmentAt,
                          @Schema(description = "미확정이면 null") Station selectedStation) {}
    public record Detail(UUID id, String name, RoomStatus status, int maxMembers,
                         @Schema(types = {"string", "null"}, format = "date-time") Instant appointmentAt,
                         UUID ownerId, RoomRole myRole,
                         @Schema(description = "미확정이면 null") Station selectedStation,
                         @Schema(types = {"string", "null"}, format = "date-time") Instant confirmedAt,
                         List<Member> members) {}
    public record Member(UUID userId, String nickname, RoomRole role, Instant joinedAt,
                         @Schema(description = "미설정이면 null") Station departureStation) {}
    public record InviteCode(String roomCode) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Station(long id, String name, boolean isSupported, List<Line> lines) {}
    public record Line(long lineId, String lineName, String color) {}
}
