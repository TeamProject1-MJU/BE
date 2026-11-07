package com.mju.linkro.subway.congestion;

import com.mju.linkro.subway.api.CongestionResult;
import com.mju.linkro.subway.api.DayType;
import com.mju.linkro.subway.api.DirectionCode;
import com.mju.linkro.subway.congestion.repository.CongestionStatsRepository.SeriesKey;
import com.mju.linkro.subway.congestion.repository.CongestionStatsRepository.Stat;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Picks the row for one (node, direction) at one slot (rules C~E of docs/congestion-api-79.md), from series already
 * read in one query. Shared by {@code congestionOf} and GET /congestion.
 */
@Component
public class CongestionResolver {
    private final HolidayCalendar calendar;

    public CongestionResolver(HolidayCalendar calendar) {
        this.calendar = calendar;
    }

    /**
     * What was looked up and the value found. {@code stat} is null for gray (supported: false); then the slot fields
     * still describe the requested slot. {@code slotDate} is null only when no date was given.
     */
    public record Resolution(LocalDate slotDate, DayType dayType, LocalTime slot, boolean firstTrain, Stat stat) {
        public CongestionResult result() {
            return stat == null ? CongestionResult.unsupported()
                    : CongestionResult.of(display(stat.pct()).doubleValue(), stat.level(), stat.label(), stat.source());
        }
    }

    public DayType dayTypeOf(LocalDate serviceDate) {
        return calendar.dayTypeOf(serviceDate);
    }

    /** Series needed for a pass on a known service day: that day, and the next one for the first-train fallback. */
    public List<SeriesKey> keys(long stationLineId, DirectionCode direction, LocalDate serviceDate) {
        return List.of(new SeriesKey(stationLineId, direction, calendar.dayTypeOf(serviceDate)),
                new SeriesKey(stationLineId, direction, calendar.dayTypeOf(serviceDate.plusDays(1))));
    }

    /**
     * @param serviceDate null when only a day type is known (GET /congestion without date); then there is no
     *                    next-day fallback
     */
    public Resolution resolve(long stationLineId, DirectionCode direction, LocalDate serviceDate, DayType dayType,
                              LocalTime slot, boolean firstTrain, Map<SeriesKey, Map<LocalTime, Stat>> series) {
        Map<LocalTime, Stat> day = series.getOrDefault(new SeriesKey(stationLineId, direction, dayType), Map.of());
        Resolution requested = new Resolution(serviceDate, dayType, slot, firstTrain, null);
        Stat stat = day.get(slot);
        if (stat != null) {
            return new Resolution(serviceDate, dayType, slot, firstTrain, stat);
        }
        int ordinal = ServiceSlots.ordinal(slot);
        boolean laterSlot = day.keySet().stream().anyMatch(other -> ServiceSlots.ordinal(other) > ordinal);
        if (laterSlot || serviceDate == null) {
            // A gap inside the service day stays gray; without a date the next day is unknown.
            return requested;
        }
        LocalDate next = serviceDate.plusDays(1);
        DayType nextType = calendar.dayTypeOf(next);
        Stat first = series.getOrDefault(new SeriesKey(stationLineId, direction, nextType), Map.of()).get(ServiceSlots.FIRST_TRAIN);
        return first == null ? requested : new Resolution(next, nextType, ServiceSlots.FIRST_TRAIN, true, first);
    }

    /** Rule E: one decimal, rounded down, so the shown value never crosses into the next level. */
    public static BigDecimal display(BigDecimal pct) {
        return pct.setScale(1, RoundingMode.DOWN);
    }
}
