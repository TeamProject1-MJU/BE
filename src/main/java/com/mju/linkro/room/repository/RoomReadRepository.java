package com.mju.linkro.room.repository;

import com.mju.linkro.room.domain.RoomRole;
import com.mju.linkro.room.dto.RoomResponses.*;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** Small read adapter over the formal tables; no duplicate station domain or schema. */
@Repository
public class RoomReadRepository {
    private final NamedParameterJdbcTemplate jdbc;
    public RoomReadRepository(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    public record MemberRow(UUID roomId, UUID userId, String nickname, RoomRole role,
                            Instant joinedAt, Long stationId) {}

    public List<MemberRow> members(Collection<UUID> roomIds) {
        if (roomIds.isEmpty()) return List.of();
        return jdbc.query("""
                select m.room_id, m.user_id, u.nickname, m.role, m.joined_at, m.departure_station_id
                from meeting_members m join users u on u.id=m.user_id
                where m.room_id in (:ids) order by m.room_id, m.joined_at, m.id
                """, Map.of("ids", roomIds), (rs, n) -> new MemberRow(
                rs.getObject("room_id", UUID.class), rs.getObject("user_id", UUID.class),
                rs.getString("nickname"), RoomRole.valueOf(rs.getString("role")),
                rs.getTimestamp("joined_at").toInstant(), (Long) rs.getObject("departure_station_id")));
    }

    public Map<Long, Station> stations(Collection<Long> stationIds) {
        if (stationIds.isEmpty()) return Map.of();
        Map<Long, Station> result = new LinkedHashMap<>();
        jdbc.query("""
                select s.id, s.name, s.is_supported, l.id as line_id, l.name as line_name, l.color
                from stations s left join station_lines sl on sl.station_id=s.id
                left join subway_lines l on l.id=sl.line_id
                where s.id in (:ids) order by s.id, l.sort_order, l.id
                """, Map.of("ids", stationIds), rs -> {
            long id = rs.getLong("id");
            Station station = result.get(id);
            if (station == null) {
                station = new Station(id, rs.getString("name"), rs.getBoolean("is_supported"), new ArrayList<>());
                result.put(id, station);
            }
            if (rs.getObject("line_id") != null) station.lines().add(new Line(rs.getLong("line_id"),
                    rs.getString("line_name"), rs.getString("color")));
        });
        result.replaceAll((id, station) -> new Station(id, station.name(), station.isSupported(), List.copyOf(station.lines())));
        return Map.copyOf(result);
    }
}
