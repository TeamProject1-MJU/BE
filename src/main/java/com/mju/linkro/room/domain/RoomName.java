package com.mju.linkro.room.domain;

/** Shared name invariant. Matches PostgreSQL btrim + char_length, not Java strip/UTF-16 length. */
public final class RoomName {
    private RoomName() {}

    public static String normalize(String name) {
        if (name == null) throw new IllegalArgumentException("Room name must not be null");
        int start = 0, end = name.length();
        while (start < end && name.charAt(start) == ' ') start++;
        while (end > start && name.charAt(end - 1) == ' ') end--;
        String normalized = name.substring(start, end);
        int length = normalized.codePointCount(0, normalized.length());
        if (length < 1 || length > 30 || normalized.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Room name must contain 1 to 30 code points after btrim");
        }
        return normalized;
    }
}
