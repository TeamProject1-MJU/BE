package com.mju.linkro.subway.congestion.service;

import com.mju.linkro.common.exception.BusinessException;
import com.mju.linkro.common.exception.ErrorCode;
import com.mju.linkro.subway.api.DayType;
import com.mju.linkro.subway.congestion.CongestionResolver;
import com.mju.linkro.subway.congestion.CongestionResolver.Resolution;
import com.mju.linkro.subway.congestion.ServiceSlots;
import com.mju.linkro.subway.congestion.dto.CongestionQuery;
import com.mju.linkro.subway.congestion.dto.CongestionResponse;
import com.mju.linkro.subway.congestion.repository.CongestionStatsRepository;
import com.mju.linkro.subway.congestion.repository.CongestionStatsRepository.SeriesKey;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * API 6.2. Always reads congestion_stats, whatever {@code linkro.subway.congestion} is: the switch only chooses the
 * route lookup ({@code CongestionLookup}).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CongestionQueryService {
    private final CongestionStatsRepository stats;
    private final CongestionResolver resolver;

    public CongestionResponse congestion(CongestionQuery query) {
        boolean supported = stats.stationSupported(query.stationId())
                .orElseThrow(() -> new BusinessException(ErrorCode.STATION_NOT_FOUND));
        LocalDate serviceDate;
        DayType dayType;
        LocalTime slot;
        boolean firstTrain;
        if (query.date() != null) {
            ServiceSlots.ServiceSlot pass = ServiceSlots.slotOf(LocalDateTime.of(query.date(), query.time()));
            serviceDate = pass.serviceDate();
            dayType = resolver.dayTypeOf(serviceDate);
            slot = pass.slot();
            firstTrain = pass.firstTrain();
        } else {
            ServiceSlots.SlotTime pass = ServiceSlots.slotOf(query.time());
            serviceDate = null;
            dayType = query.dayType();
            slot = pass.slot();
            firstTrain = pass.firstTrain();
        }
        Resolution requested = new Resolution(serviceDate, dayType, slot, firstTrain, null);
        var node = stats.node(query.stationId(), query.lineId());
        if (node.isEmpty()) {
            // Stations outside the supported range are gray (6.2); a supported station off that line is a bad lineId.
            if (!supported) return response(query, requested);
            throw new BusinessException(ErrorCode.NOT_FOUND, Map.of("lineId", "이 역은 해당 노선에 없습니다."));
        }
        long id = node.get().stationLineId();
        List<SeriesKey> keys = serviceDate != null ? resolver.keys(id, query.direction(), serviceDate)
                : List.of(new SeriesKey(id, query.direction(), dayType));
        return response(query, resolver.resolve(id, query.direction(), serviceDate, dayType, slot, firstTrain, stats.series(keys)));
    }

    private static CongestionResponse response(CongestionQuery query, Resolution found) {
        var stat = found.stat();
        return new CongestionResponse(query.stationId(), query.lineId(), query.direction(), found.dayType(),
                found.slot().toString(), found.slotDate() == null ? null : found.slotDate().toString(), found.firstTrain(),
                stat != null, stat == null ? null : CongestionResolver.display(stat.pct(), stat.level()),
                stat == null ? null : stat.level(), stat == null ? null : stat.label(), stat == null ? null : stat.source());
    }
}
