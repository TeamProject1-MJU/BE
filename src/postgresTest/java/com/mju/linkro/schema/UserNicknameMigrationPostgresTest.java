package com.mju.linkro.schema;

import com.github.f4b6a3.uuid.UuidCreator;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Production migrations only: no substitute stations/recommendation tables or H2 SQL. */
@Testcontainers
class UserNicknameMigrationPostgresTest {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");

    private String url;

    @BeforeEach
    void isolatedDatabase() throws SQLException {
        String database = "schema_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = connection.createStatement()) {
            statement.execute("create database " + database);
        }
        url = "jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432) + "/" + database;
    }

    @Test
    void emptyDatabaseMigratesValidatesAndRerunDoesNotChangeHistory() throws SQLException {
        var flyway = flyway(null);
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(3);
        flyway.validate();
        var history = rows("select version, checksum, success from flyway_schema_history order by installed_rank");
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        assertThat(rows("select version, checksum, success from flyway_schema_history order by installed_rank")).isEqualTo(history);
        assertThat(rows("select tablename from pg_tables where schemaname = 'public' order by tablename"))
            .containsExactly(List.of("flyway_schema_history"), List.of("refresh_tokens"), List.of("users"));
    }

    @Test
    void upgradeFromV1V2PreservesUserTokenAndMigrationChecksums() throws SQLException {
        flyway("2").migrate();
        UUID userId = insertUser(" 가 ");
        UUID tokenId = UuidCreator.getTimeOrderedEpoch();
        try (var connection = connect(); var statement = connection.prepareStatement(
            "insert into refresh_tokens(id, user_id, family_id, token_hash, expires_at) values (?, ?, ?, ?, now() + interval '14 days')")) {
            statement.setObject(1, tokenId);
            statement.setObject(2, userId);
            statement.setObject(3, UuidCreator.getTimeOrderedEpoch());
            statement.setString(4, "a".repeat(64));
            statement.executeUpdate();
        }
        var users = rows("select * from users");
        var tokens = rows("select * from refresh_tokens");
        var checksums = rows("select version, checksum from flyway_schema_history order by installed_rank");
        var flyway = flyway(null);
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
        flyway.validate();
        assertThat(rows("select * from users")).isEqualTo(users);
        assertThat(rows("select * from refresh_tokens")).isEqualTo(tokens);
        assertThat(rows("select version, checksum from flyway_schema_history where version in ('1', '2') order by installed_rank"))
            .isEqualTo(checksums);
        assertThat(flyway.migrate().migrationsExecuted).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void invalidLegacyNicknameFailsAtomicallyWithoutRewritingData(String nickname) throws SQLException {
        flyway("2").migrate();
        UUID userId = insertUser(nickname);
        var before = rows("select * from users");
        assertThatThrownBy(() -> flyway(null).migrate()).satisfies(exception -> {
            Throwable cause = exception;
            while (cause.getCause() != null) { cause = cause.getCause(); }
            assertThat(cause).isInstanceOf(SQLException.class);
            assertThat(((SQLException) cause).getSQLState()).isEqualTo("23514");
            assertThat(cause.getMessage()).contains("ck_users_nickname_trimmed_length");
        });
        assertThat(rows("select * from users")).isEqualTo(before);
        assertThat(rows("select version from flyway_schema_history order by installed_rank"))
            .containsExactly(List.of("1"), List.of("2"));
        assertThat(rows("select conname from pg_constraint where conname = 'ck_users_nickname_trimmed_length'")).isEmpty();
        // An explicit, test-only correction demonstrates recovery. The migration never changes data.
        try (var connection = connect(); var statement = connection.prepareStatement("update users set nickname = ? where id = ?")) {
            statement.setString(1, "검토완료");
            statement.setObject(2, userId);
            statement.executeUpdate();
        }
        assertThat(flyway(null).migrate().migrationsExecuted).isEqualTo(1);
        flyway(null).validate();
    }

    @ParameterizedTest
    @ValueSource(strings = {"가", " 가 ", "가나다라마바사아자차카타파하가나다라마바", "🚇🚇🚇🚇🚇🚇🚇🚇🚇🚇🚇🚇🚇🚇🚇🚇🚇🚇🚇🚇"})
    void acceptsSignupBoundaryAndUnicodeCodePoints(String nickname) throws SQLException {
        flyway(null).migrate();
        insertUser(nickname);
        assertThat(rows("select nickname from users")).containsExactly(List.of(nickname));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "     "})
    void rejectsBlankNicknamesWithNamedCheck(String nickname) {
        flyway(null).migrate();
        assertSqlFailure(() -> insertUser(nickname), "23514", "ck_users_nickname_trimmed_length");
    }

    @Test
    void preservesNotNullAndVarcharLimit() {
        flyway(null).migrate();
        assertSqlFailure(() -> insertUser(null), "23502", "nickname");
        assertSqlFailure(() -> insertUser("가".repeat(21)), "22001", "character varying(20)");
    }

    @Test
    void updateAlsoEnforcesCheckAndRejectedUpdatePreservesValue() throws SQLException {
        flyway(null).migrate();
        insertUser("정상");
        assertSqlFailure(() -> {
            try (var connection = connect(); var statement = connection.createStatement()) {
                statement.executeUpdate("update users set nickname = '   '");
            }
        }, "23514", "ck_users_nickname_trimmed_length");
        assertThat(rows("select nickname from users")).containsExactly(List.of("정상"));
    }

    private Flyway flyway(String target) {
        var configuration = Flyway.configure().dataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword())
            .locations("classpath:db/migration").cleanDisabled(true);
        // Keep the V3 regression suite fixed; RoomSchemaMigrationPostgresTest covers latest.
        configuration.target(target == null ? "3" : target);
        return configuration.load();
    }

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(url, POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private UUID insertUser(String nickname) throws SQLException {
        UUID id = UuidCreator.getTimeOrderedEpoch();
        try (var connection = connect(); var statement = connection.prepareStatement(
            "insert into users(id, kakao_id, nickname) values (?, ?, ?)")) {
            statement.setObject(1, id);
            statement.setString(2, id.toString());
            statement.setString(3, nickname);
            statement.executeUpdate();
        }
        return id;
    }

    private List<List<Object>> rows(String sql) throws SQLException {
        var rows = new ArrayList<List<Object>>();
        try (var connection = connect(); var statement = connection.createStatement(); var result = statement.executeQuery(sql)) {
            while (result.next()) {
                var row = new ArrayList<Object>();
                for (int column = 1; column <= result.getMetaData().getColumnCount(); column++) {
                    row.add(result.getObject(column));
                }
                rows.add(row);
            }
        }
        return rows;
    }

    private static void assertSqlFailure(SqlAction action, String state, String constraintOrDetail) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(SQLException.class, exception -> {
            assertThat(exception.getSQLState()).isEqualTo(state);
            assertThat(exception.getMessage()).contains(constraintOrDetail);
        });
    }

    @FunctionalInterface
    private interface SqlAction {
        void run() throws SQLException;
    }
}
