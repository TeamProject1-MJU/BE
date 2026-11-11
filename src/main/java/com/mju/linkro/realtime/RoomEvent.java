package com.mju.linkro.realtime;

import java.time.Instant;
import java.util.UUID;

/** The common envelope sent to {@code /topic/rooms/{roomId}}: {@code { type, roomId, payload, at }}. */
public record RoomEvent(RoomEventType type, UUID roomId, Object payload, Instant at) {}
