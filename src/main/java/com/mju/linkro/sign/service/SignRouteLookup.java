package com.mju.linkro.sign.service;

import com.mju.linkro.common.exception.BusinessException;
import com.mju.linkro.common.exception.ErrorCode;
import com.mju.linkro.subway.api.DirectionCode;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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

    /** Whether the sign's station number (e.g. "202") is this station's number on this line. */
    public boolean codeMatches(long stationId, long lineId, String stationCode) {
        String code = stationCode == null ? "" : stationCode.replaceAll("[^0-9A-Za-z]", "");
        if (code.isEmpty()) return false;
        List<String> codes = jdbc.queryForList("""
                select station_code from station_lines where station_id = :station and line_id = :line
                """, Map.of("station", stationId, "line", lineId), String.class);
        return codes.stream().anyMatch(code::equalsIgnoreCase);
    }

    /** One RIDE edge on a line: from a station to the next stop, in a direction. */
    public record RideEdge(long lineId, long fromStationId, long toStationId, String toStationName, DirectionCode direction) {}

    /**
     * Every RIDE edge of every line serving this station, so a caller can walk each line and see other lines too.
     * Edges with a missing or unknown direction are skipped: they are no evidence, and must not fail the check.
     */
    public List<RideEdge> rideEdges(long stationId) {
        return jdbc.query("""
                select sl.line_id, sl.station_id, next_sl.station_id, next_s.name, c.direction from station_connections c
                join station_lines sl on sl.id = c.from_station_line_id
                join station_lines next_sl on next_sl.id = c.to_station_line_id
                join stations next_s on next_s.id = next_sl.station_id
                where c.connection_type = 'RIDE' and next_sl.line_id = sl.line_id
                  and sl.line_id in (select line_id from station_lines where station_id = :station)
                """, Map.of("station", stationId), (rs, row) -> {
                    DirectionCode code = direction(rs.getString(5));
                    return code == null ? null : new RideEdge(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getString(4), code);
                }).stream().filter(Objects::nonNull).toList();
    }

    private static DirectionCode direction(String value) {
        return Arrays.stream(DirectionCode.values()).filter(code -> code.name().equals(value)).findFirst().orElse(null);
    }
}
