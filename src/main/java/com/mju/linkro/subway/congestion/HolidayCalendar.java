package com.mju.linkro.subway.congestion;

import com.mju.linkro.subway.api.DayType;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.PropertySource;
import org.springframework.stereotype.Component;

/**
 * Day type of a service day (rule B): Sunday and public holidays SUNDAY, Saturday SATURDAY, otherwise WEEKDAY.
 * Holidays come from {@code linkro.subway.holidays} (subway-holidays.properties, overridable like any setting).
 */
@Component
@PropertySource(value = "classpath:subway-holidays.properties", encoding = "UTF-8")
public class HolidayCalendar {
    private final Set<LocalDate> holidays;

    public HolidayCalendar(@Value("${linkro.subway.holidays:}") String holidays) {
        this.holidays = Arrays.stream(holidays.split(",")).map(String::strip).filter(value -> !value.isEmpty())
                .map(LocalDate::parse).collect(Collectors.toUnmodifiableSet());
    }

    public DayType dayTypeOf(LocalDate serviceDate) {
        if (serviceDate.getDayOfWeek() == DayOfWeek.SUNDAY || holidays.contains(serviceDate)) return DayType.SUNDAY;
        return serviceDate.getDayOfWeek() == DayOfWeek.SATURDAY ? DayType.SATURDAY : DayType.WEEKDAY;
    }

    public boolean isHoliday(LocalDate date) {
        return holidays.contains(date);
    }
}
