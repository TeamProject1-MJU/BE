package com.mju.linkro.subway.api;

/** Basic station row (stations table only). {@code supported} is stations.is_supported. */
public record StationInfo(long id, String name, double latitude, double longitude, boolean supported) {}
