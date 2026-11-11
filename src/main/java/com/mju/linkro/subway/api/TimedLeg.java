package com.mju.linkro.subway.api;

import java.time.Instant;

/**
 * One train of a timetable route (#96): boarded at one station, left at another.
 *
 * @param direction     direction of the first RIDE edge on this train (a 6호선 train keeps going from LOOP to DOWN)
 * @param stationCount  RIDE edges ridden on this train
 * @param walkSec       transfer walking before boarding (0 on the first train and for a change on the same platform)
 * @param waitSec       platform wait before boarding: boarding − (previous alighting + walk). 0 on the first train
 */
public record TimedLeg(
        long lineId,
        String lineName,
        String trainCode,
        String terminal,
        DirectionCode direction,
        long fromStationId,
        String fromStationName,
        long toStationId,
        String toStationName,
        Instant boardAt,
        Instant alightAt,
        int stationCount,
        int walkSec,
        int waitSec) {}
