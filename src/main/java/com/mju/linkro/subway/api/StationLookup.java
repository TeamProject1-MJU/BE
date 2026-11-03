package com.mju.linkro.subway.api;

/**
 * Station existence and support checks for other domains (e.g. departure station, candidate station).
 *
 * <p>Read-only and reads only the stations table, without joining lines. It opens no transaction and does not
 * change the isolation level: inside a caller's transaction it uses that transaction's connection, so it is safe
 * to call while a room lock is held.
 */
public interface StationLookup {

    /** @throws com.mju.linkro.common.exception.BusinessException STATION_NOT_FOUND (404) */
    StationInfo require(long stationId);

    /**
     * @throws com.mju.linkro.common.exception.BusinessException STATION_NOT_FOUND (404), or STATION_UNSUPPORTED
     *         (422) when {@code is_supported = false}
     */
    StationInfo requireSupported(long stationId);
}
