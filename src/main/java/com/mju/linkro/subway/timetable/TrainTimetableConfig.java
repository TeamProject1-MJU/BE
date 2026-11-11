package com.mju.linkro.subway.timetable;

import com.mju.linkro.subway.congestion.HolidayCalendar;
import com.mju.linkro.subway.graph.SubwayGraphLoader;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Loads the train timetable once at startup when {@code linkro.subway.module=graph} (#94). Only a lookup for later
 * use: route search does not read it yet.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "linkro.subway.module", havingValue = "graph")
public class TrainTimetableConfig {
    static final String RESOURCE = "train-timetable.csv.gz";

    @Bean
    TrainTimetable trainTimetable(NamedParameterJdbcTemplate jdbc) {
        try (InputStream in = new ClassPathResource(RESOURCE).getInputStream()) {
            return TrainTimetable.load(in, new SubwayGraphLoader(jdbc).load());
        } catch (IOException exception) {
            throw new UncheckedIOException("Train timetable " + RESOURCE + " is missing", exception);
        }
    }

    @Bean
    TimetableClock timetableClock(HolidayCalendar calendar) {
        return new TimetableClock(calendar);
    }
}
