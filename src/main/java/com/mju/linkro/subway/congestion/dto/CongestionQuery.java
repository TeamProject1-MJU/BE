package com.mju.linkro.subway.congestion.dto;

import com.mju.linkro.common.exception.BusinessException;
import com.mju.linkro.common.exception.ErrorCode;
import com.mju.linkro.subway.api.DayType;
import com.mju.linkro.subway.api.DirectionCode;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Validated query of GET /congestion. Every invalid field is reported at once as VALIDATION_ERROR (400).
 *
 * @param dayType day type of the service day; required without {@code date}, ignored (format still checked) with it
 * @param date    calendar date of {@code time}; null when absent
 */
public record CongestionQuery(long stationId, long lineId, DirectionCode direction, DayType dayType, LocalTime time,
                              LocalDate date) {
    private static final Pattern TIME = Pattern.compile("\\d{2}:\\d{2}");
    private static final Pattern DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");

    public static CongestionQuery of(String stationId, String lineId, String direction, String dayType, String time,
                                     String date) {
        Map<String, String> errors = new LinkedHashMap<>();
        Long station = id(stationId, "stationId", errors);
        Long line = id(lineId, "lineId", errors);
        DirectionCode directionCode = constant(DirectionCode.class, direction, "direction", errors);
        LocalDate dateValue = date(date, errors);
        DayType dayTypeValue = constant(DayType.class, dayType, "dayType", errors);
        if (dayType == null && dateValue == null && !errors.containsKey("date")) {
            errors.put("dayType", "date가 없으면 dayType이 필요합니다.");
        }
        LocalTime timeValue = time(time, errors);
        if (!errors.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, errors);
        }
        return new CongestionQuery(station, line, directionCode, dayTypeValue, timeValue, dateValue);
    }

    private static Long id(String raw, String field, Map<String, String> errors) {
        if (raw == null || raw.isBlank()) {
            errors.put(field, field + "가 필요합니다.");
            return null;
        }
        try {
            long value = Long.parseLong(raw.strip());
            if (value > 0) return value;
        } catch (NumberFormatException ignored) {
            // reported below
        }
        errors.put(field, field + "는 1 이상의 정수여야 합니다.");
        return null;
    }

    private static <E extends Enum<E>> E constant(Class<E> type, String raw, String field, Map<String, String> errors) {
        if (raw == null) {
            if (type == DirectionCode.class) errors.put(field, field + "가 필요합니다.");
            return null;
        }
        String names = String.join("/", Arrays.stream(type.getEnumConstants()).map(Enum::name).toList());
        try {
            return Enum.valueOf(type, raw.strip());
        } catch (IllegalArgumentException exception) {
            errors.put(field, field + "는 " + names + " 중 하나여야 합니다.");
            return null;
        }
    }

    private static LocalTime time(String raw, Map<String, String> errors) {
        if (raw == null || !TIME.matcher(raw.strip()).matches()) {
            errors.put("time", "time은 HH:mm 형식이어야 합니다.");
            return null;
        }
        try {
            return LocalTime.parse(raw.strip());
        } catch (DateTimeParseException exception) {
            errors.put("time", "time은 HH:mm 형식이어야 합니다.");
            return null;
        }
    }

    private static LocalDate date(String raw, Map<String, String> errors) {
        if (raw == null || raw.isBlank()) return null;
        try {
            if (DATE.matcher(raw.strip()).matches()) return LocalDate.parse(raw.strip());
        } catch (DateTimeParseException ignored) {
            // reported below
        }
        errors.put("date", "date는 yyyy-MM-dd 형식의 날짜여야 합니다.");
        return null;
    }
}
