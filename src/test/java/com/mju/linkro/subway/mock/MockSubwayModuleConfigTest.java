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
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(MockSubwayModuleConfig.class);

    @Test
    void mockIsTheDefaultAndWarnsOnce(CapturedOutput output) {
        runner.run(context -> {
            assertThat(context).hasSingleBean(SubwayRouteFinder.class).hasSingleBean(FareCalculator.class)
                    .hasSingleBean(CongestionLookup.class);
            assertThat(context.getBean(SubwayRouteFinder.class)).isInstanceOf(MockSubwayRouteFinder.class);
        });
        assertThat(output.getAll().lines().filter(line -> line.contains("Subway module is MOCK"))).hasSize(1);
        assertThat(output.getAll()).contains("WARN");
    }

    @Test
    void mockPropertyRegistersMock() {
        runner.withPropertyValues("linkro.subway.module=mock")
                .run(context -> assertThat(context).hasSingleBean(SubwayRouteFinder.class));
    }

    @Test
    void graphPropertyLeavesMockOut(CapturedOutput output) {
        runner.withPropertyValues("linkro.subway.module=graph").run(context -> {
            assertThat(context).doesNotHaveBean(SubwayRouteFinder.class).doesNotHaveBean(FareCalculator.class)
                    .doesNotHaveBean(CongestionLookup.class).doesNotHaveBean(MockSubwayModuleConfig.class);
        });
        assertThat(output.getAll()).doesNotContain("Subway module is MOCK");
    }
}
