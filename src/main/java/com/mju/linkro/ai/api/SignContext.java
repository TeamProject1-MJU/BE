package com.mju.linkro.ai.api;

import com.mju.linkro.subway.api.DirectionCode;

/** Either headsign or direction is present. A direction code does not identify a train terminal. */
public record SignContext(String stationName, String lineName, DirectionCode direction, String headsign) {
    public boolean directionOnly() { return headsign == null; }
}
