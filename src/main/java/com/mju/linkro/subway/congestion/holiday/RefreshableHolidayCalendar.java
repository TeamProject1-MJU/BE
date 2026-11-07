package com.mju.linkro.subway.congestion.holiday;

import com.mju.linkro.subway.api.DayType;
import com.mju.linkro.subway.congestion.HolidayCalendar;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * {@link HolidayCalendar} whose holidays can be replaced one whole year at a time by {@link HolidayRefresher}.
 * A year without a refreshed list uses subway-holidays.properties. Replacing swaps an immutable map, so readers see
 * either the old or the new year, never a mix. Kept in memory only.
 */
@Primary
@Component
public class RefreshableHolidayCalendar extends HolidayCalendar {
    private final Map<Integer, Set<LocalDate>> defaults;
    private final AtomicReference<Map<Integer, Set<LocalDate>>> refreshed = new AtomicReference<>(Map.of());

    public RefreshableHolidayCalendar(@Value("${linkro.subway.holidays:}") String holidays) {
        super(holidays);
        this.defaults = Arrays.stream(holidays.split(",")).map(String::strip).filter(value -> !value.isEmpty())
                .map(LocalDate::parse)
                .collect(Collectors.groupingBy(LocalDate::getYear, Collectors.toUnmodifiableSet()));
    }

    @Override
    public DayType dayTypeOf(LocalDate serviceDate) {
        if (serviceDate.getDayOfWeek() == DayOfWeek.SUNDAY || isHoliday(serviceDate)) return DayType.SUNDAY;
        return serviceDate.getDayOfWeek() == DayOfWeek.SATURDAY ? DayType.SATURDAY : DayType.WEEKDAY;
    }

    @Override
    public boolean isHoliday(LocalDate date) {
        return holidays(date.getYear()).contains(date);
    }

    /** The list in use for a year: the refreshed one if any, else the properties default. */
    public Set<LocalDate> holidays(int year) {
        Set<LocalDate> latest = refreshed.get().get(year);
        return latest != null ? latest : defaults.getOrDefault(year, Set.of());
    }

    public boolean isRefreshed(int year) {
        return refreshed.get().containsKey(year);
    }

    /** Replaces a whole year at once. Every date must be in that year. */
    public void replaceYear(int year, Collection<LocalDate> holidays) {
        Set<LocalDate> copy = Set.copyOf(holidays);
        if (copy.stream().anyMatch(date -> date.getYear() != year)) {
            throw new IllegalArgumentException("holiday outside " + year);
        }
        refreshed.updateAndGet(current -> {
            Map<Integer, Set<LocalDate>> next = new HashMap<>(current);
            next.put(year, copy);
            return Map.copyOf(next);
        });
    }
}
