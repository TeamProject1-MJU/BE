package com.mju.linkro.subway.congestion.holiday;

import com.mju.linkro.subway.api.DayType;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RefreshableHolidayCalendarTest {

    @Test
    void aYearIsReplacedAsAWholeAndOtherYearsKeepTheirDefault() {
        var calendar = new RefreshableHolidayCalendar("2026-12-25,2027-01-01,2027-12-25");
        assertThat(calendar.dayTypeOf(LocalDate.of(2027, 12, 24))).isEqualTo(DayType.WEEKDAY); // Friday

        calendar.replaceYear(2027, List.of(LocalDate.of(2027, 12, 24)));

        assertThat(calendar.dayTypeOf(LocalDate.of(2027, 12, 24))).isEqualTo(DayType.SUNDAY);
        assertThat(calendar.isHoliday(LocalDate.of(2027, 1, 1))).isFalse();
        assertThat(calendar.isHoliday(LocalDate.of(2026, 12, 25))).isTrue();
        assertThat(calendar.isRefreshed(2027)).isTrue();
        assertThat(calendar.isRefreshed(2026)).isFalse();
        assertThat(calendar.dayTypeOf(LocalDate.of(2027, 1, 2))).isEqualTo(DayType.SATURDAY);
        assertThatThrownBy(() -> calendar.replaceYear(2027, List.of(LocalDate.of(2028, 1, 1)))).isInstanceOf(IllegalArgumentException.class);
    }
}
