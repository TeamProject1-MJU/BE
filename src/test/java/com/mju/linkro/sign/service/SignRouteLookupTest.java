package com.mju.linkro.sign.service;

import com.mju.linkro.common.exception.BusinessException;
import com.mju.linkro.common.exception.ErrorCode;
import com.mju.linkro.subway.api.DirectionCode;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import static org.assertj.core.api.Assertions.*;

class SignRouteLookupTest {
    @Test
    void validatesActualBoardingEdgesIncludingBranchAndOneWayLoop() {
        var db = new EmbeddedDatabaseBuilder().generateUniqueName(true).setType(EmbeddedDatabaseType.H2).build();
        try {
            var jdbc = new org.springframework.jdbc.core.JdbcTemplate(db);
            jdbc.execute("create table stations(id bigint primary key, is_supported boolean)");
            jdbc.execute("create table station_lines(id bigint primary key, station_id bigint, line_id bigint)");
            jdbc.execute("create table station_connections(from_station_line_id bigint, to_station_line_id bigint, connection_type varchar, direction varchar)");
            jdbc.update("insert into stations values(1,true),(2,true),(3,true),(4,true),(5,false)");
            jdbc.update("insert into station_lines values(11,1,1),(12,2,1),(21,1,2),(22,2,2),(23,3,2),(61,3,6),(62,4,6),(63,5,6)");
            jdbc.update("""
                    insert into station_connections values
                    (11,12,'RIDE','UP'),(12,11,'RIDE','DOWN'),
                    (21,22,'RIDE','INNER'),(22,21,'RIDE','OUTER'),
                    (21,23,'RIDE','UP'),(23,21,'RIDE','DOWN'),
                    (61,62,'RIDE','LOOP'),(62,63,'RIDE','DOWN'),(11,21,'TRANSFER',null)
                    """);
            var lookup = new SignRouteLookup(new NamedParameterJdbcTemplate(db));
            lookup.requireDirection(1,1,DirectionCode.UP);
            lookup.requireDirection(2,1,DirectionCode.DOWN);
            lookup.requireDirection(1,2,DirectionCode.INNER);
            lookup.requireDirection(2,2,DirectionCode.OUTER);
            lookup.requireDirection(1,2,DirectionCode.UP); // branch arm
            lookup.requireDirection(3,2,DirectionCode.DOWN);
            lookup.requireDirection(3,6,DirectionCode.LOOP);
            for (long[] invalid : new long[][]{{999,1},{1,99},{4,1},{4,6},{5,6}}) {
                assertThatThrownBy(() -> lookup.requireDirection(invalid[0],invalid[1],DirectionCode.UP))
                        .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));
            }
            assertThatThrownBy(() -> lookup.requireDirection(3,6,DirectionCode.DOWN)).isInstanceOf(BusinessException.class);
            assertThatThrownBy(() -> lookup.requireDirection(4,6,DirectionCode.DOWN)).isInstanceOf(BusinessException.class);
        } finally {
            db.shutdown();
        }
    }
}
