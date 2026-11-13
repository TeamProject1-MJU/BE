package com.mju.linkro.subway.timetable;

import com.mju.linkro.subway.api.DayType;
import com.mju.linkro.subway.congestion.HolidayCalendar;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Objects;

/**
 * Turns a Seoul date and time into the timetable's day type and second of the service day (#94).
 *
 * <p>A service day starts at 03:00: the last trains reach their terminal by about 01:15 and the first ones leave
 * after 05:00, so nothing runs in between. 00:00~02:59 belongs to the day before and counts past 86400 (00:30 on
 * Saturday is Friday's 88200), like the timetable. Day types come from {@link HolidayCalendar} (Sunday and public
 * holidays SUNDAY, Saturday SATURDAY, otherwise WEEKDAY) of the service date.
 */
public class TimetableClock {
    static final LocalTime SERVICE_DAY_START = LocalTime.of(3, 0);

    /** {@code secondOfServiceDay} is what {@link TrainTimetable} lookups take. */
    public record ServiceTime(LocalDate serviceDate, DayType dayType, int secondOfServiceDay) {}

    private final HolidayCalendar calendar;

    public TimetableClock(HolidayCalendar calendar) {
        this.calendar = calendar;
    }

    public ServiceTime serviceTimeOf(LocalDateTime dateTime) {
        Objects.requireNonNull(dateTime, "dateTime");
        int second = dateTime.toLocalTime().toSecondOfDay();
        LocalDate date = dateTime.toLocalDate();
        if (dateTime.toLocalTime().isBefore(SERVICE_DAY_START)) {
            date = date.minusDays(1);
            second += 86_400;
        }
        return new ServiceTime(date, calendar.dayTypeOf(date), second);
    }

    /** When a service date starts: 03:00 of that date. */
    public LocalDateTime startOf(LocalDate serviceDate) {
        return serviceDate.atTime(SERVICE_DAY_START);
    }

    /** Day type of a service date. */
    public DayType dayTypeOf(LocalDate serviceDate) {
        return calendar.dayTypeOf(serviceDate);
    }
}
