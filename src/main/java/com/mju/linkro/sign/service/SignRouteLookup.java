package com.mju.linkro.sign.service;

import com.mju.linkro.common.exception.BusinessException;
import com.mju.linkro.common.exception.ErrorCode;
import com.mju.linkro.subway.api.DirectionCode;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/** Checks actual boarding edges, including branches and one-way loops; never invents a terminal. */
@Component
@RequiredArgsConstructor
public class SignRouteLookup {
    private final NamedParameterJdbcTemplate jdbc;

    public void requireDirection(long stationId, long lineId, DirectionCode direction) {
        Integer count = jdbc.queryForObject("""
                select count(*) from station_lines sl
                join stations s on s.id = sl.station_id
                join station_connections c on c.from_station_line_id = sl.id
                join station_lines next_sl on next_sl.id = c.to_station_line_id
                join stations next_s on next_s.id = next_sl.station_id
                where sl.station_id = :station and sl.line_id = :line and s.is_supported = true
                  and next_sl.line_id = sl.line_id and next_s.is_supported = true
                  and c.connection_type = 'RIDE' and c.direction = :direction
                """, Map.of("station", stationId, "line", lineId, "direction", direction.name()), Integer.class);
        if (count == null || count == 0) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    Map.of("route", "해당 역·노선에서 이용할 수 없는 방향입니다."));
        }
    }
}
