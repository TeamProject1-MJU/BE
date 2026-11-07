package com.mju.linkro.subway.graph;

import com.mju.linkro.subway.api.SubwayRouteFinder;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class SubwayGraphModuleConfigTest {
    // A mocked template returns empty lists for every query, so SubwayGraphLoader loads empty subway data.
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(SubwayGraphModuleConfig.class)
            .withBean(NamedParameterJdbcTemplate.class, () -> mock(NamedParameterJdbcTemplate.class));

    @Test
    void graphModeFailsToStartWithoutSubwayData() {
        runner.withPropertyValues("linkro.subway.module=graph").run(context -> {
            assertThat(context).hasFailed();
            Throwable root = context.getStartupFailure();
            while (root.getCause() != null) root = root.getCause();
            assertThat(root).isInstanceOf(IllegalStateException.class).hasMessageContaining("Subway graph is empty");
        });
    }

    @Test
    void otherModesDoNotLoadTheGraph() {
        runner.run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(SubwayGraph.class)
                .doesNotHaveBean(SubwayRouteFinder.class));
        runner.withPropertyValues("linkro.subway.module=mock").run(context -> assertThat(context).hasNotFailed()
                .doesNotHaveBean(SubwayGraph.class));
    }

    @Test
    void emptyModuleIsMockNotGraph() {
        runner.withUserConfiguration(com.mju.linkro.subway.mock.MockSubwayModuleConfig.class)
                .withPropertyValues("linkro.subway.module=").run(context -> {
                    assertThat(context).hasNotFailed().doesNotHaveBean(SubwayGraph.class).hasSingleBean(SubwayRouteFinder.class);
                    assertThat(context.getBean(SubwayRouteFinder.class)).isNotInstanceOf(DijkstraSubwayRouteFinder.class);
                });
    }
}
