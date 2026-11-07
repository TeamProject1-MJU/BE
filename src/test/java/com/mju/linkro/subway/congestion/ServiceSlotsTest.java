package com.mju.linkro.subway.congestion;

import com.mju.linkro.subway.api.DayType;
import com.mju.linkro.subway.congestion.ServiceSlots.ServiceSlot;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ServiceSlotsTest {

    @ParameterizedTest(name = "{0} {1} -> {2} {3} firstTrain={4}")
    @CsvSource({
            "2026-11-06, 05:29, 2026-11-06, 05:30, true",
            "2026-11-06, 05:30, 2026-11-06, 05:30, false",
            "2026-11-06, 05:59, 2026-11-06, 05:30, false",
            "2026-11-06, 18:40, 2026-11-06, 18:30, false",
            "2026-11-06, 23:59, 2026-11-06, 23:30, false",
            "2026-11-07, 00:00, 2026-11-06, 00:00, false",
            "2026-11-07, 00:29, 2026-11-06, 00:00, false",
            "2026-11-07, 00:30, 2026-11-06, 00:30, false",
            "2026-11-07, 00:59, 2026-11-06, 00:30, false",
            "2026-11-07, 01:00, 2026-11-07, 05:30, true",
            "2026-01-01, 00:10, 2025-12-31, 00:00, false"})
    void ruleA(LocalDate date, LocalTime time, LocalDate serviceDate, LocalTime slot, boolean firstTrain) {
        assertThat(ServiceSlots.slotOf(LocalDateTime.of(date, time))).isEqualTo(new ServiceSlot(serviceDate, slot, firstTrain));
    }

    @Test
    void serviceDayOrderEndsWithMidnightSlots() {
        assertThat(ServiceSlots.ORDER).hasSize(39).startsWith(LocalTime.of(5, 30), LocalTime.of(6, 0))
                .endsWith(LocalTime.of(23, 30), LocalTime.MIDNIGHT, LocalTime.of(0, 30));
        assertThat(ServiceSlots.ordinal(LocalTime.MIDNIGHT)).isGreaterThan(ServiceSlots.ordinal(LocalTime.of(23, 30)));
        assertThatThrownBy(() -> ServiceSlots.ordinal(LocalTime.of(1, 0))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void holidaysAndSundaysAreSunday() {
        HolidayCalendar calendar = new HolidayCalendar(" 2026-12-25 , 2026-06-06 ");
        assertThat(calendar.dayTypeOf(LocalDate.of(2026, 11, 6))).isEqualTo(DayType.WEEKDAY);
        assertThat(calendar.dayTypeOf(LocalDate.of(2026, 11, 7))).isEqualTo(DayType.SATURDAY);
        assertThat(calendar.dayTypeOf(LocalDate.of(2026, 11, 8))).isEqualTo(DayType.SUNDAY);
        assertThat(calendar.dayTypeOf(LocalDate.of(2026, 12, 25))).isEqualTo(DayType.SUNDAY);
        // A holiday on a Saturday is still a holiday.
        assertThat(calendar.dayTypeOf(LocalDate.of(2026, 6, 6))).isEqualTo(DayType.SUNDAY);
        assertThat(new HolidayCalendar("").dayTypeOf(LocalDate.of(2026, 12, 25))).isEqualTo(DayType.WEEKDAY);
    }

    @Test
    void bundledHolidayListIsRead() throws Exception {
        var properties = new java.util.Properties();
        try (var in = getClass().getResourceAsStream("/subway-holidays.properties");
             var reader = new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        HolidayCalendar calendar = new HolidayCalendar(properties.getProperty("linkro.subway.holidays"));
        for (String date : new String[] {"2026-12-25", "2027-01-01", "2027-02-09", "2027-05-03", "2027-07-19", "2027-12-27"}) {
            assertThat(calendar.isHoliday(LocalDate.parse(date))).as(date).isTrue();
        }
        assertThat(calendar.isHoliday(LocalDate.of(2026, 12, 24))).isFalse();
    }
}
