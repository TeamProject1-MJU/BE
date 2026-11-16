package com.mju.linkro.subway.timetable;

import java.util.ArrayList;
import java.util.List;
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
 *
 * <p>{@code transferOverrides} (#151) replace the seconds of TRANSFER edges for the timetable routes only; the defaults
 * are in {@code subway-transfer-overrides.properties}. A pair not listed keeps the seed seconds (station_connections),
 * the distance always stays, and the fixed-cost graph (Dijkstra) never uses them. TimetableRouter checks them against
 * the graph at startup and fails on a wrong entry.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "linkro.subway.timetable")
public class TimetableProperties {
    private boolean enabled = true;
    private int stationAccessSeconds = 60;
    private int noServiceWindowMinutes = 60;
    private List<TransferOverride> transferOverrides = new ArrayList<>();

    /**
     * Seconds of the transfer between two lines at one station, both directions. {@code station} is the station name as
     * stored (e.g. 총신대입구(이수)); {@code fromLine} and {@code toLine} are line codes ("4", "7"). The seconds take the
     * place of the seed's walking seconds; the wait for the next train is still added by the router.
     */
    @Getter
    @Setter
    public static class TransferOverride {
        private String station;
        private String fromLine;
        private String toLine;
        private int seconds;

        public TransferOverride() {}

        public TransferOverride(String station, String fromLine, String toLine, int seconds) {
            this.station = station;
            this.fromLine = fromLine;
            this.toLine = toLine;
            this.seconds = seconds;
        }

        @Override
        public String toString() {
            return station + " " + fromLine + "<->" + toLine + " " + seconds + " s";
        }
    }
}
