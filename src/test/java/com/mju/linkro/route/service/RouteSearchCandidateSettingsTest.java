package com.mju.linkro.route.service;

import com.mju.linkro.room.service.RoomAccess;
import com.mju.linkro.route.kakao.KakaoRouteClient;
import com.mju.linkro.subway.api.StationLookup;
import com.mju.linkro.subway.congestion.CongestionResolver;
import com.mju.linkro.subway.congestion.repository.CongestionStatsRepository;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * The #155 settings bound by Spring ({@code linkro.route.timetable-candidate-cut-minutes},
 * {@code linkro.route.timetable-candidate-grace-minutes}): RouteSearchService alone with mocked collaborators. Wrong
 * values stop the context. Their effect on the candidates is RouteSearchCandidateSettingsPostgresTest.
 */
class RouteSearchCandidateSettingsTest {
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
    void theDefaultsAndValidValuesStart() {
        runner.run(context -> assertThat(context).hasNotFailed().hasSingleBean(RouteSearchService.class));
        runner.withPropertyValues("linkro.route.timetable-candidate-cut-minutes=1", "linkro.route.timetable-candidate-grace-minutes=0")
                .run(context -> assertThat(context).hasNotFailed().hasSingleBean(RouteSearchService.class));
    }

    @Test
    void aCutBelowOneStopsTheContext() {
        for (String cut : new String[]{"0", "-1"}) {
            runner.withPropertyValues("linkro.route.timetable-candidate-cut-minutes=" + cut).run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure()).rootCause().isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining("linkro.route.timetable-candidate-cut-minutes must be at least 1: " + cut);
            });
        }
    }

    @Test
    void aNegativeGraceStopsTheContext() {
        runner.withPropertyValues("linkro.route.timetable-candidate-grace-minutes=-1").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause().isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("linkro.route.timetable-candidate-grace-minutes must not be negative: -1");
        });
    }
}
