package com.mju.linkro.subway.mock;

import com.mju.linkro.subway.api.CongestionLookup;
import com.mju.linkro.subway.api.FareCalculator;
import com.mju.linkro.subway.api.SubwayRouteFinder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(OutputCaptureExtension.class)
class MockSubwayModuleConfigTest {
    private static final String ROUTE_WARN = "Subway route module is MOCK";
    private static final String CONGESTION_WARN = "Subway congestion is MOCK";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(MockSubwayModuleConfig.class, MockCongestionConfig.class);

    @Test
    void allThreeMocksAreTheDefaultAndEachWarnsOnce(CapturedOutput output) {
        runner.run(context -> {
            assertThat(context).hasSingleBean(SubwayRouteFinder.class).hasSingleBean(FareCalculator.class)
                    .hasSingleBean(CongestionLookup.class);
            assertThat(context.getBean(SubwayRouteFinder.class)).isInstanceOf(MockSubwayRouteFinder.class);
            assertThat(context.getBean(FareCalculator.class)).isInstanceOf(MockFareCalculator.class);
            assertThat(context.getBean(CongestionLookup.class)).isInstanceOf(MockCongestionLookup.class);
        });
        assertThat(output.getAll().lines().filter(line -> line.contains(ROUTE_WARN))).hasSize(1);
        assertThat(output.getAll().lines().filter(line -> line.contains(CONGESTION_WARN))).hasSize(1);
        assertThat(output.getAll().lines().filter(line -> line.contains("MOCK")).allMatch(line -> line.contains("WARN"))).isTrue();
    }

    @Test
    void explicitMockValuesRegisterTheMocks() {
        runner.withPropertyValues("linkro.subway.module=mock", "linkro.subway.congestion=mock")
                .run(context -> assertThat(context).hasSingleBean(SubwayRouteFinder.class).hasSingleBean(CongestionLookup.class));
    }

    @Test
    void graphModuleLeavesOnlyTheCongestionMock(CapturedOutput output) {
        runner.withPropertyValues("linkro.subway.module=graph").run(context -> {
            assertThat(context).doesNotHaveBean(SubwayRouteFinder.class).doesNotHaveBean(FareCalculator.class)
                    .doesNotHaveBean(MockSubwayModuleConfig.class).hasSingleBean(CongestionLookup.class);
        });
        assertThat(output.getAll()).doesNotContain(ROUTE_WARN);
        assertThat(output.getAll().lines().filter(line -> line.contains(CONGESTION_WARN))).hasSize(1);
    }

    @Test
    void otherCongestionValueRemovesTheCongestionMock(CapturedOutput output) {
        runner.withPropertyValues("linkro.subway.congestion=stats").run(context -> {
            assertThat(context).doesNotHaveBean(CongestionLookup.class).doesNotHaveBean(MockCongestionConfig.class)
                    .hasSingleBean(SubwayRouteFinder.class);
        });
        assertThat(output.getAll()).doesNotContain(CONGESTION_WARN);
    }
}
