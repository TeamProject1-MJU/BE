package com.mju.linkro.room.repository;

import com.mju.linkro.room.dto.StationProposalResponses.*;
import java.util.*;
import org.springframework.jdbc.core.namedparam.*;
import org.springframework.stereotype.Repository;

@Repository
public class StationProposalRepository {
    private final NamedParameterJdbcTemplate jdbc;
    public StationProposalRepository(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    public boolean resultBelongsToRoom(UUID resultId, UUID roomId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists(select 1 from recommendation_results r
                join recommendation_runs run on run.id=r.run_id where r.id=:id and run.room_id=:room)
                """, Map.of("id", resultId, "room", roomId), Boolean.class));
    }

    public boolean exists(UUID roomId, long stationId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from station_proposals where room_id=:room and station_id=:station)",
                Map.of("room", roomId, "station", stationId), Boolean.class));
    }

    public long count(UUID roomId) {
        return jdbc.queryForObject("select count(*) from station_proposals where room_id=:room", Map.of("room", roomId), Long.class);
    }

    /** JDBC executes the insert immediately: constraint failures happen before the service returns. */
    public Created insert(UUID id, UUID roomId, long stationId, UUID userId, UUID resultId) {
        jdbc.update("insert into station_proposals(id,room_id,station_id,proposed_by,source_result_id) values (:id,:room,:station,:user,:result)",
                new MapSqlParameterSource().addValue("id", id).addValue("room", roomId).addValue("station", stationId)
                        .addValue("user", userId).addValue("result", resultId));
        return jdbc.queryForObject("""
                select p.id,s.id as station_id,s.name,p.proposed_by,u.nickname,p.source_result_id,p.created_at
                from station_proposals p join stations s on s.id=p.station_id join users u on u.id=p.proposed_by
                where p.id=:id and p.room_id=:room
                """, Map.of("id", id, "room", roomId), (rs,n) -> new Created(rs.getObject("id", UUID.class),
                new Station(rs.getLong("station_id"), rs.getString("name")),
                new Author(rs.getObject("proposed_by", UUID.class), rs.getString("nickname")),
                rs.getObject("source_result_id", UUID.class), rs.getTimestamp("created_at").toInstant()));
    }

    public List<Item> list(UUID roomId, UUID userId, Long selected) {
        return jdbc.query("""
                select p.id,p.station_id,s.name,p.proposed_by,u.nickname,p.source_result_id,p.created_at,
                       count(v.id) as vote_count,coalesce(bool_or(v.user_id=:user),false) as voted_by_me
                from station_proposals p join stations s on s.id=p.station_id join users u on u.id=p.proposed_by
                left join station_proposal_votes v on v.proposal_id=p.id and v.room_id=p.room_id
                where p.room_id=:room
                group by p.id,s.name,u.nickname order by p.created_at desc,p.id desc
                """, Map.of("room", roomId, "user", userId), (rs,n) -> new Item(rs.getObject("id", UUID.class),
                new Station(rs.getLong("station_id"), rs.getString("name")),
                new Author(rs.getObject("proposed_by", UUID.class), rs.getString("nickname")),
                rs.getObject("source_result_id", UUID.class), Objects.equals(selected, rs.getLong("station_id")),
                rs.getLong("vote_count"), rs.getBoolean("voted_by_me"), rs.getTimestamp("created_at").toInstant()));
    }

    public Optional<UUID> author(UUID roomId, UUID proposalId) {
        return jdbc.query("select proposed_by from station_proposals where id=:id and room_id=:room",
                Map.of("id", proposalId, "room", roomId), (rs,n) -> rs.getObject(1, UUID.class)).stream().findFirst();
    }

    public Optional<String> stationName(UUID roomId, UUID proposalId) {
        return jdbc.query("select s.name from station_proposals p join stations s on s.id=p.station_id where p.id=:id and p.room_id=:room",
                Map.of("id", proposalId, "room", roomId), (rs,n) -> rs.getString(1)).stream().findFirst();
    }

    public void delete(UUID roomId, UUID proposalId) {
        jdbc.update("delete from station_proposals where id=:id and room_id=:room", Map.of("id", proposalId, "room", roomId));
    }
}
