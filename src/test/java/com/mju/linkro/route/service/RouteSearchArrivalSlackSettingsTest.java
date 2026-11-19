package com.mju.linkro.route.service;

import com.mju.linkro.room.service.RoomAccess;
import com.mju.linkro.route.kakao.KakaoRouteClient;
import com.mju.linkro.subway.api.StationLookup;
import com.mju.linkro.subway.congestion.CongestionResolver;
import com.mju.linkro.subway.congestion.repository.CongestionStatsRepository;
import jakarta.persistence.EntityManagerFactory;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * {@code linkro.route.arrival-max-slack-minutes} (#170) bound by Spring, like the #155 settings of
 * {@link RouteSearchCandidateSettingsTest}: at least 1, otherwise the context does not start. Its effect is
 * RouteSearchArrivalTimetableTest.
 */
class RouteSearchArrivalSlackSettingsTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            // RoomAccess (even mocked) carries @PersistenceContext: give it a factory to inject from.
            .withBean(EntityManagerFactory.class, () -> mock(EntityManagerFactory.class))
            .withBean(RoomAccess.class, () -> mock(RoomAccess.class))
            .withBean(StationLookup.class, () -> mock(StationLookup.class))
            .withBean(KakaoRouteClient.class, () -> mock(KakaoRouteClient.class))
            .withBean(KakaoRouteMapper.class, () -> mock(KakaoRouteMapper.class))
            .withBean(InternalRouteBuilder.class, () -> mock(InternalRouteBuilder.class))
            .withBean(CongestionStatsRepository.class, () -> mock(CongestionStatsRepository.class))
            .withBean(CongestionResolver.class, () -> mock(CongestionResolver.class))
            .withBean(RouteSearchService.class);

    @Test
    void theDefaultAndOneMinuteStart() {
        assertThat(RouteSearchService.DEFAULT_ARRIVAL_MAX_SLACK_MINUTES).isEqualTo(30);
        runner.run(context -> assertThat(context).hasNotFailed().hasSingleBean(RouteSearchService.class));
        runner.withPropertyValues("linkro.route.arrival-max-slack-minutes=1")
                .run(context -> assertThat(context).hasNotFailed().hasSingleBean(RouteSearchService.class));
    }

    @Test
    void zeroOrLessStopsTheContext() {
        for (String minutes : new String[]{"0", "-1"}) {
            runner.withPropertyValues("linkro.route.arrival-max-slack-minutes=" + minutes).run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure()).rootCause().isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining("linkro.route.arrival-max-slack-minutes must be at least 1: " + minutes);
            });
        }
    }

    @Test
    void theConstructorChecksIt() {
        assertThatThrownBy(() -> new RouteSearchService(null, null, null, null, null, null, Clock.systemUTC(), null, null, 365, 10, 15, 6, 0))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("linkro.route.arrival-max-slack-minutes");
        assertThatCode(() -> new RouteSearchService(null, null, null, null, null, null, Clock.systemUTC(), null, null, 365, 10, 15, 6, 1))
                .doesNotThrowAnyException();
    }
}
