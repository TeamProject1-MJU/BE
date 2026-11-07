package com.mju.linkro.subway.congestion.holiday;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Runs {@link HolidayRefresher#daily()} at 03:00 Asia/Seoul. The app has no other scheduled job. */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class HolidayRefreshConfig {
}
