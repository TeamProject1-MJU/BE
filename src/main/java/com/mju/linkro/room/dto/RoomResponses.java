package com.mju.linkro.room.dto;

import com.mju.linkro.room.domain.*;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.*;

public final class RoomResponses {
    private RoomResponses() {}
    @Schema(name = "RoomJoinedData", requiredProperties = {"roomId"})
    public record Joined(UUID roomId) {}

    @JsonInclude(JsonInclude.Include.ALWAYS)
    @Schema(name = "RoomLeftData", requiredProperties = {"roomDeleted", "newOwnerId"})
    public record Left(boolean roomDeleted,
                       @Schema(types = {"string", "null"}, format = "uuid", description = "승계된 방장의 사용자 ID. 승계 없으면 null") UUID newOwnerId) {}

    public static final String CREATED_SCHEMA = "RoomCreatedData";
    public static final String SUMMARY_SCHEMA = "RoomSummaryData";
    public static final String DETAIL_SCHEMA = "RoomDetailData";
    public static final String MEMBER_SCHEMA = "RoomMemberData";
    public static final String INVITE_SCHEMA = "RoomInviteCodeData";
    public static final String STATION_SCHEMA = "RoomStationData";
    public static final String LINE_SCHEMA = "RoomLineData";

    @Schema(name = CREATED_SCHEMA, requiredProperties = {"id", "roomCode", "name", "maxMembers", "status", "ownerId", "appointmentAt"})
    public record Created(UUID id, String roomCode, String name, int maxMembers,
                          RoomStatus status, UUID ownerId,
                          @Schema(types = {"string", "null"}, format = "date-time") Instant appointmentAt) {}
    @Schema(name = SUMMARY_SCHEMA, requiredProperties = {"id", "name", "status", "myRole", "memberCount", "maxMembers", "appointmentAt", "selectedStation"})
    public record Summary(UUID id, String name, RoomStatus status, RoomRole myRole,
                          long memberCount, int maxMembers,
                          @Schema(types = {"string", "null"}, format = "date-time") Instant appointmentAt,
                          @Schema(description = "미확정이면 null") Station selectedStation) {}
    @Schema(name = DETAIL_SCHEMA, requiredProperties = {"id", "name", "status", "maxMembers", "appointmentAt", "ownerId", "myRole", "selectedStation", "confirmedAt", "members"})
    public record Detail(UUID id, String name, RoomStatus status, int maxMembers,
                         @Schema(types = {"string", "null"}, format = "date-time") Instant appointmentAt,
                         UUID ownerId, RoomRole myRole,
                         @Schema(description = "미확정이면 null") Station selectedStation,
                         @Schema(types = {"string", "null"}, format = "date-time") Instant confirmedAt,
                         List<Member> members) {}
    @Schema(name = MEMBER_SCHEMA, requiredProperties = {"userId", "nickname", "role", "joinedAt", "departureStation"})
    public record Member(UUID userId, String nickname, RoomRole role, Instant joinedAt,
                         @Schema(description = "미설정이면 null") Station departureStation) {}
    @Schema(name = INVITE_SCHEMA, requiredProperties = {"roomCode"})
    public record InviteCode(String roomCode) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    @Schema(name = STATION_SCHEMA, requiredProperties = {"id", "name", "isSupported", "lines"})
    public record Station(long id, String name, boolean isSupported, List<Line> lines) {}
    @Schema(name = LINE_SCHEMA, requiredProperties = {"lineId", "lineName", "color"})
    public record Line(long lineId, String lineName, String color) {}
}
