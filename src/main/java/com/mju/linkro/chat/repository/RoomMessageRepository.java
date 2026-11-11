package com.mju.linkro.chat.repository;

import com.mju.linkro.chat.domain.SenderType;
import com.mju.linkro.chat.dto.ChatResponses.Message;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * JDBC access to room_messages (V8). Ordering and the cursor comparison are always done by PostgreSQL on the uuid
 * column: UUIDv7 ids sort by time there, while {@code java.util.UUID.compareTo} does not (API 10.2).
 */
@Repository
public class RoomMessageRepository {
    private static final String SELECT = """
            select m.id, m.sender_type, m.sender_id, u.nickname, m.content, m.created_at, cast(m.meta as text) as meta
            from room_messages m left join users u on u.id = m.sender_id
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final RowMapper<Message> rows;

    public RoomMessageRepository(NamedParameterJdbcTemplate jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.rows = (rs, n) -> new Message(rs.getObject("id", UUID.class), SenderType.valueOf(rs.getString("sender_type")),
                rs.getObject("sender_id", UUID.class), rs.getString("nickname"), rs.getString("content"),
                rs.getTimestamp("created_at").toInstant(), meta(json, rs));
    }

    /** Inserts and returns the stored row in one statement. A missing room or sender fails on the foreign key. */
    public Message insert(UUID id, UUID roomId, SenderType type, UUID senderId, String content) {
        return jdbc.queryForObject("""
                with saved as (
                    insert into room_messages (id, room_id, sender_id, sender_type, content)
                    values (:id, :room, :sender, :type, :content)
                    returning id, sender_type, sender_id, content, created_at, meta)
                select s.id, s.sender_type, s.sender_id, u.nickname, s.content, s.created_at, cast(s.meta as text) as meta
                from saved s left join users u on u.id = s.sender_id
                """, new MapSqlParameterSource().addValue("id", id).addValue("room", roomId)
                .addValue("sender", senderId).addValue("type", type.name()).addValue("content", content), rows);
    }

    /** The user's current nickname, for the wording of SYSTEM messages. */
    public Optional<String> nickname(UUID userId) {
        return jdbc.query("select nickname from users where id = :id", new MapSqlParameterSource("id", userId),
                (rs, n) -> rs.getString(1)).stream().findFirst();
    }

    /** Newest first. {@code before} is the exclusive upper bound id, or null for the newest page. */
    public List<Message> page(UUID roomId, UUID before, int limit) {
        return jdbc.query(SELECT + " where m.room_id = :room and (cast(:before as uuid) is null or m.id < :before)"
                        + " order by m.id desc limit :limit",
                new MapSqlParameterSource().addValue("room", roomId).addValue("before", before).addValue("limit", limit), rows);
    }

    /** USER and AI messages sent before {@code before}, oldest first: the conversation an @linkro question is part of. */
    public List<Message> conversationBefore(UUID roomId, UUID before, int limit) {
        if (limit <= 0) return List.of();
        List<Message> newestFirst = jdbc.query(SELECT + " where m.room_id = :room and m.id < :before"
                        + " and m.sender_type in ('USER', 'AI') order by m.id desc limit :limit",
                new MapSqlParameterSource().addValue("room", roomId).addValue("before", before).addValue("limit", limit), rows);
        List<Message> oldestFirst = new ArrayList<>(newestFirst);
        Collections.reverse(oldestFirst);
        return oldestFirst;
    }

    private static JsonNode meta(JsonMapper json, ResultSet rs) throws SQLException {
        String text = rs.getString("meta");
        if (text == null) return null;
        try {
            return json.readTree(text);
        } catch (JacksonException exception) {
            throw new IllegalStateException("room_messages.meta is not JSON", exception);
        }
    }
}
