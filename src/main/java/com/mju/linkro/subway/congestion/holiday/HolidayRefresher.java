package com.mju.linkro.subway.congestion.holiday;

import com.mju.linkro.subway.congestion.ServiceSlots;
import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Refreshes this year's and next year's holidays from the KASI special-day API: once in the background after
 * startup, then daily at 03:00 Asia/Seoul. A year is replaced only when all 12 months succeed; any failure keeps
 * that year's previous list (properties default or the last successful refresh). Without a key nothing is called.
 */
@Slf4j
@Component
public class HolidayRefresher {
    static final String DAILY_CRON = "0 0 3 * * *";

    private final KasiHolidayClient client;
    private final RefreshableHolidayCalendar calendar;
    private final Clock clock;
    private final ExecutorService startup = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "holiday-refresh");
        thread.setDaemon(true);
        return thread;
    });

    @Autowired
    public HolidayRefresher(KasiHolidayClient client, RefreshableHolidayCalendar calendar) {
        this(client, calendar, Clock.system(ServiceSlots.ZONE));
    }

    HolidayRefresher(KasiHolidayClient client, RefreshableHolidayCalendar calendar, Clock clock) {
        this.client = client;
        this.calendar = calendar;
        this.clock = clock;
    }

    /** Never blocks or fails startup: the refresh runs on its own daemon thread. */
    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        if (!client.enabled()) {
            log.info("KASI_SPECIAL_DAY_KEY is empty: holidays come from subway-holidays.properties only.");
            return;
        }
        log.info("Holiday refresh is enabled: in the background now and daily at 03:00 KST.");
        startup.execute(this::refreshSafely);
    }

    @Scheduled(cron = DAILY_CRON, zone = "Asia/Seoul")
    public void daily() {
        if (client.enabled()) refreshSafely();
    }

    private void refreshSafely() {
        try {
            refresh();
        } catch (RuntimeException exception) {
            log.warn("Holiday refresh failed: {}; keeping the previous lists", exception.getClass().getSimpleName());
        }
    }

    /** This year and next year (Asia/Seoul); returns whether each year was replaced. */
    public synchronized Map<Integer, Boolean> refresh() {
        int year = LocalDate.now(clock.withZone(ServiceSlots.ZONE)).getYear();
        Map<Integer, Boolean> replaced = new LinkedHashMap<>();
        for (int target : List.of(year, year + 1)) {
            replaced.put(target, refreshYear(target));
        }
        return replaced;
    }

    private boolean refreshYear(int year) {
        List<LocalDate> holidays = new ArrayList<>();
        for (int month = 1; month <= 12; month++) {
            KasiHolidayClient.Result result = client.holidays(year, month);
            if (result instanceof KasiHolidayClient.Failed failed) {
                log.warn("Holiday refresh {}: month {} failed ({}); keeping the previous {} list ({} holidays)",
                        year, month, failed.reason(), year, calendar.holidays(year).size());
                return false;
            }
            holidays.addAll(((KasiHolidayClient.Month) result).holidays());
        }
        calendar.replaceYear(year, holidays);
        log.info("Holiday refresh {}: {} holidays", year, holidays.size());
        return true;
    }

    @PreDestroy
    void shutdown() {
        startup.shutdownNow();
    }
}
