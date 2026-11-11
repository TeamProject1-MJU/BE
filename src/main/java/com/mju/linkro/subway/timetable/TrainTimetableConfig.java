package com.mju.linkro.subway.timetable;

import com.mju.linkro.subway.api.TimetableRouteFinder;
import com.mju.linkro.subway.congestion.HolidayCalendar;
import com.mju.linkro.subway.graph.SubwayGraph;
import com.mju.linkro.subway.graph.SubwayGraphLoader;
import com.mju.linkro.subway.graph.SubwayGraphProperties;
import com.mju.linkro.subway.graph.TimetableRouter;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Loads the train timetable once at startup (#94) and routes on it (#96) when {@code linkro.subway.module=graph} and
 * {@code linkro.subway.timetable.enabled} is not false.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "linkro.subway.module", havingValue = "graph")
@ConditionalOnBooleanProperty(name = "linkro.subway.timetable.enabled", matchIfMissing = true)
@EnableConfigurationProperties(TimetableProperties.class)
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

    @Bean
    TimetableRouteFinder timetableRouteFinder(SubwayGraph graph, TrainTimetable timetable, TimetableClock clock,
                                              SubwayGraphProperties costs, TimetableProperties properties) {
        return new TimetableRouter(graph, timetable, clock, costs, properties.getStationAccessSeconds(),
                Duration.ofMinutes(properties.getNoServiceWindowMinutes()));
    }
}
