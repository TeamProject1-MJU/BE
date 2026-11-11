package com.mju.linkro.subway.station.repository;

import com.mju.linkro.subway.api.StationInfo;
import com.mju.linkro.subway.station.dto.StationResponses.*;
import com.mju.linkro.subway.station.dto.StationSearchCondition;
import java.util.*;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** Read-only JDBC queries over the V10/V11 station tables; no JPA entities. */
@Repository
public class StationReadRepository {
    private final NamedParameterJdbcTemplate jdbc;
    public StationReadRepository(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    public List<Line> lines() {
        return jdbc.query("select id, line_code, name, color from subway_lines order by sort_order, id",
                (rs, n) -> new Line(rs.getLong("id"), rs.getString("line_code"), rs.getString("name"), rs.getString("color")));
    }

    public Optional<String> lineName(long lineId) {
        return jdbc.queryForList("select name from subway_lines where id = :id", Map.of("id", lineId), String.class)
                .stream().findFirst();
    }

    /** stations table only, so callers inside a room transaction touch no other table. */
    public Optional<StationInfo> info(long stationId) {
        return jdbc.query("select id, name, latitude, longitude, is_supported from stations where id = :id",
                Map.of("id", stationId), (rs, n) -> new StationInfo(rs.getLong("id"), rs.getString("name"),
                        rs.getDouble("latitude"), rs.getDouble("longitude"), rs.getBoolean("is_supported")))
                .stream().findFirst();
    }

    public Optional<Station> station(long stationId) {
        return stations(List.of(stationId)).stream().findFirst();
    }

    public StationPage search(StationSearchCondition condition) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        String where = where(condition, params);
        long total = Objects.requireNonNull(jdbc.queryForObject("select count(*) from stations s" + where, params, Long.class));
        // Name order uses "C" (code points, i.e. 가나다 for Hangul syllables) so it does not depend on the DB collation.
        String order = condition.keyword() == null ? " order by s.id"
                : " order by case when lower(s.name) in (lower(:exact), lower(:exactStem)) then 0"
                + " when s.name ilike :prefix escape '\\' or s.name ilike :prefixStem escape '\\' then 1 else 2 end, s.name collate \"C\", s.id";
        params.addValue("limit", condition.size()).addValue("offset", condition.offset());
        List<Long> ids = jdbc.queryForList("select s.id from stations s" + where + order + " limit :limit offset :offset",
                params, Long.class);
        return new StationPage(stations(ids), condition.page(), condition.size(), total);
    }

    private static String where(StationSearchCondition condition, MapSqlParameterSource params) {
        List<String> clauses = new ArrayList<>();
        if (condition.keyword() != null) {
            // Without a stem, the stem patterns repeat the keyword so the SQL stays the same shape.
            String keyword = StationSearchCondition.escapeLike(condition.keyword());
            String stem = condition.keywordStem() == null ? keyword : StationSearchCondition.escapeLike(condition.keywordStem());
            params.addValue("contains", "%" + keyword + "%").addValue("containsStem", "%" + stem + "%")
                    .addValue("prefix", keyword + "%").addValue("prefixStem", stem + "%")
                    .addValue("exact", condition.keyword())
                    .addValue("exactStem", condition.keywordStem() == null ? condition.keyword() : condition.keywordStem());
            clauses.add("(s.name ilike :contains escape '\\' or s.name ilike :containsStem escape '\\')");
        }
        if (condition.bbox() != null) {
            var bbox = condition.bbox();
            params.addValue("minLng", bbox.minLng()).addValue("maxLng", bbox.maxLng())
                    .addValue("minLat", bbox.minLat()).addValue("maxLat", bbox.maxLat());
            clauses.add("s.longitude between :minLng and :maxLng and s.latitude between :minLat and :maxLat");
        }
        if (condition.lineId() != null) {
            params.addValue("lineId", condition.lineId());
            clauses.add("exists (select 1 from station_lines f where f.station_id = s.id and f.line_id = :lineId)");
        }
        return clauses.isEmpty() ? "" : " where " + String.join(" and ", clauses);
    }

    /** Stations in the given id order, each with all of its lines (sort_order, id). */
    private List<Station> stations(List<Long> ids) {
        if (ids.isEmpty()) return List.of();
        Map<Long, Station> byId = new HashMap<>();
        jdbc.query("""
                select s.id, s.name, s.latitude, s.longitude, s.is_supported,
                       l.id as line_id, l.name as line_name, l.color, sl.station_code
                from stations s left join station_lines sl on sl.station_id = s.id
                left join subway_lines l on l.id = sl.line_id
                where s.id in (:ids) order by s.id, l.sort_order, l.id
                """, Map.of("ids", ids), rs -> {
            long id = rs.getLong("id");
            Station station = byId.get(id);
            if (station == null) {
                station = new Station(id, rs.getString("name"), rs.getDouble("latitude"), rs.getDouble("longitude"),
                        rs.getBoolean("is_supported"), new ArrayList<>());
                byId.put(id, station);
            }
            if (rs.getObject("line_id") != null) station.lines().add(new StationLine(rs.getLong("line_id"),
                    rs.getString("line_name"), rs.getString("color"), rs.getString("station_code")));
        });
        return ids.stream().map(byId::get).filter(Objects::nonNull)
                .map(s -> new Station(s.id(), s.name(), s.latitude(), s.longitude(), s.isSupported(), List.copyOf(s.lines())))
                .toList();
    }
}
