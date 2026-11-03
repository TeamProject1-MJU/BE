package com.mju.linkro.subway.station.service;

import com.mju.linkro.common.exception.BusinessException;
import com.mju.linkro.common.exception.ErrorCode;
import com.mju.linkro.subway.station.dto.StationResponses.*;
import com.mju.linkro.subway.station.dto.StationSearchCondition;
import com.mju.linkro.subway.station.repository.StationReadRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** API 5.1~5.3. Count and page run in one read-only transaction so they see the same data. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class StationQueryService {
    private final StationReadRepository reads;

    public List<Line> lines() {
        return reads.lines();
    }

    public StationPage search(String keyword, Long lineId, String bbox, Integer page, Integer size) {
        return reads.search(StationSearchCondition.of(keyword, lineId, bbox, page, size));
    }

    public Station station(long stationId) {
        return reads.station(stationId).orElseThrow(() -> new BusinessException(ErrorCode.STATION_NOT_FOUND));
    }
}
