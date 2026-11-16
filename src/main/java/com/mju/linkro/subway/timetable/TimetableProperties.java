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
 * platform, the same for every station (no per-station data). It defaults to 60 (#149): 카카오맵, given a departure
 * time, skips a train leaving 30 s after the set minute and takes one leaving 60 s after it (measured at 창동 only, with
 * minute inputs, so anything from 31 to 60 s fits; 60 is an approximation). The first train boarded leaves at least this
 * long after the departure time, and the route leaves this long before boarding. {@code noServiceWindowMinutes}
 * (default 60): when no train of any line arrives in this many minutes before an appointment, the recommendation is
 * refused with NO_SERVICE_TIME.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "linkro.subway.timetable")
public class TimetableProperties {
    private boolean enabled = true;
    private int stationAccessSeconds = 60;
    private int noServiceWindowMinutes = 60;
}
