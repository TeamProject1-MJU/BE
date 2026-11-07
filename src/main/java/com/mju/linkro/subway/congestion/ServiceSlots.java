package com.mju.linkro.subway.congestion;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 30-minute congestion slots of a service day (rule A of docs/congestion-api-79.md), Asia/Seoul.
 *
 * <p>A service day runs 05:30~23:30 and then 00:00, 00:30 of the next calendar date (39 slots). 01:00~05:29 has no
 * service and maps to the same date's first-train 05:30 slot.
 */
public final class ServiceSlots {
    public static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    public static final LocalTime FIRST_TRAIN = LocalTime.of(5, 30);
    private static final LocalTime NO_SERVICE_FROM = LocalTime.of(1, 0);
    /** Service-day order: 05:30 ... 23:30, 00:00, 00:30. */
    public static final List<LocalTime> ORDER = order();

    /** Slot of a time of day; {@code previousServiceDay} for 00:00~00:59, which belong to the day before. */
    public record SlotTime(LocalTime slot, boolean firstTrain, boolean previousServiceDay) {}

    /** Slot of a date and time: the service day it belongs to and the slot in that day. */
    public record ServiceSlot(LocalDate serviceDate, LocalTime slot, boolean firstTrain) {}

    private ServiceSlots() {}

    public static SlotTime slotOf(LocalTime time) {
        Objects.requireNonNull(time, "time");
        if (time.isBefore(NO_SERVICE_FROM)) {
            return new SlotTime(LocalTime.of(0, time.getMinute() < 30 ? 0 : 30), false, true);
        }
        if (time.isBefore(FIRST_TRAIN)) {
            return new SlotTime(FIRST_TRAIN, true, false);
        }
        return new SlotTime(LocalTime.of(time.getHour(), time.getMinute() < 30 ? 0 : 30), false, false);
    }

    public static ServiceSlot slotOf(LocalDateTime dateTime) {
        SlotTime slot = slotOf(dateTime.toLocalTime());
        LocalDate date = dateTime.toLocalDate();
        return new ServiceSlot(slot.previousServiceDay() ? date.minusDays(1) : date, slot.slot(), slot.firstTrain());
    }

    /** Position in the service day (05:30 is 0, 00:30 is 38). */
    public static int ordinal(LocalTime slot) {
        int index = ORDER.indexOf(slot);
        if (index < 0) throw new IllegalArgumentException("not a congestion slot: " + slot);
        return index;
    }

    private static List<LocalTime> order() {
        List<LocalTime> slots = new ArrayList<>();
        // 05:30 .. 23:30 is 37 slots; counting avoids wrapping past midnight.
        for (int i = 0; i < 37; i++) {
            slots.add(FIRST_TRAIN.plusMinutes(30L * i));
        }
        slots.add(LocalTime.MIDNIGHT);
        slots.add(LocalTime.of(0, 30));
        return List.copyOf(slots);
    }
}
