package com.mju.linkro.subway.station.service;

import com.mju.linkro.common.exception.BusinessException;
import com.mju.linkro.common.exception.ErrorCode;
import com.mju.linkro.subway.api.StationInfo;
import com.mju.linkro.subway.api.StationLookup;
import com.mju.linkro.subway.station.repository.StationReadRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** No @Transactional on purpose: it joins the caller's transaction and keeps its isolation level. */
@Service
@RequiredArgsConstructor
public class JdbcStationLookup implements StationLookup {
    private final StationReadRepository reads;

    @Override
    public StationInfo require(long stationId) {
        return reads.info(stationId).orElseThrow(() -> new BusinessException(ErrorCode.STATION_NOT_FOUND));
    }

    @Override
    public StationInfo requireSupported(long stationId) {
        StationInfo station = require(stationId);
        if (!station.supported()) {
            throw new BusinessException(ErrorCode.STATION_UNSUPPORTED);
        }
        return station;
    }
}
