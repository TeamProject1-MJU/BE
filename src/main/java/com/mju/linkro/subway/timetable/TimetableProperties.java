package com.mju.linkro.subway.timetable;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Timetable routing settings ({@code linkro.subway.timetable.*}, #96).
 *
 * <p>{@code enabled} (default true) loads the timetable and routes recommendations on real trains. false is the
 * emergency way back to the fixed-cost graph (travel_seconds + dwell + transfer wait): nothing of the timetable is
 * loaded and every member is ESTIMATE. {@code stationAccessSeconds} is the time from leaving to standing on the first
 * platform; there is no per-station data, so it defaults to 0.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "linkro.subway.timetable")
public class TimetableProperties {
    private boolean enabled = true;
    private int stationAccessSeconds = 0;
}
