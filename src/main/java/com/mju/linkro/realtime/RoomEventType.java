package com.mju.linkro.realtime;

/** The {@code type} of a WebSocket room event (API chapter 11). */
public enum RoomEventType {
    MESSAGE_CREATED,
    MEMBER_JOINED,
    MEMBER_LEFT,
    OWNER_CHANGED,
    DEPARTURE_UPDATED,
    ROOM_UPDATED,
    RECOMMENDATION_CREATED,
    STATION_PROPOSAL_CREATED,
    STATION_PROPOSAL_DELETED,
    STATION_PROPOSAL_VOTE_CHANGED,
    SELECTED_STATION_CHANGED
}
