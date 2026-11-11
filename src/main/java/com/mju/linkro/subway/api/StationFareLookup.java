package com.mju.linkro.subway.api;

import java.util.OptionalInt;

/**
 * Fare between two stations from the Seoul Metro fare table (#109): set by the pair of stations, whatever route is
 * ridden. Adult transit-card base fare.
 */
public interface StationFareLookup {

    /** Empty when the pair is not in the table (or the table could not be loaded). */
    OptionalInt fareBetween(long fromStationId, long toStationId);

    /** False for a table that failed to load or has no pair: callers then use their own estimate without per-pair logs. */
    default boolean loaded() {
        return true;
    }
}
