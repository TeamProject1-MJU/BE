package com.mju.linkro.subway.congestion;

import com.mju.linkro.common.exception.BusinessException;
import com.mju.linkro.common.exception.ErrorCode;
import com.mju.linkro.subway.api.DayType;
import com.mju.linkro.subway.api.DirectionCode;
import com.mju.linkro.subway.congestion.dto.CongestionQuery;
import java.time.LocalDate;
import java.time.LocalTime;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CongestionQueryTest {

    @Test
    void validQueries() {
        assertThat(CongestionQuery.of("222", "2", "INNER", "WEEKDAY", "18:40", null))
                .isEqualTo(new CongestionQuery(222, 2, DirectionCode.INNER, DayType.WEEKDAY, LocalTime.of(18, 40), null));
        // With a date, dayType may be left out.
        assertThat(CongestionQuery.of("222", "2", "LOOP", null, "00:10", "2026-11-07").date()).isEqualTo(LocalDate.of(2026, 11, 7));
    }

    @Test
    void everyBadFieldIsReported() {
        assertThatThrownBy(() -> CongestionQuery.of(null, "x", "EAST", "HOLIDAY", "8:00", "2026-02-30"))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR);
                    assertThat(exception.getDetails()).containsOnlyKeys("stationId", "lineId", "direction", "dayType", "time", "date");
                });
        assertThatThrownBy(() -> CongestionQuery.of("1", "2", "UP", null, "08:00", null))
                .isInstanceOfSatisfying(BusinessException.class, exception -> assertThat(exception.getDetails()).containsOnlyKeys("dayType"));
        for (String time : new String[] {"24:00", "08:60", "0800", "08:00:00", ""}) {
            assertThatThrownBy(() -> CongestionQuery.of("1", "2", "UP", "WEEKDAY", time, null)).as(time)
                    .isInstanceOfSatisfying(BusinessException.class, exception -> assertThat(exception.getDetails()).containsOnlyKeys("time"));
        }
    }
}
