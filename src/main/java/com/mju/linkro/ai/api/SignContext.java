package com.mju.linkro.ai.api;

import com.mju.linkro.subway.api.DirectionCode;

/** The expected route (API spec 9.1 {@code route}/{@code expected}). {@code direction} may be null; then headsign decides. */
public record SignContext(String stationName, String lineName, DirectionCode direction, String headsign) { }
