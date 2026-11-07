package com.mju.linkro.subway.congestion;

import com.mju.linkro.subway.api.CongestionLookup;
import com.mju.linkro.subway.congestion.repository.CongestionStatsRepository;
import com.mju.linkro.subway.mock.MockCongestionConfig;
import com.mju.linkro.subway.mock.MockCongestionLookup;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** linkro.subway.congestion: mock (default) or db, never both. */
@ExtendWith(OutputCaptureExtension.class)
class CongestionSwitchTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(MockCongestionConfig.class, DbCongestionConfig.class)
            .withBean(CongestionStatsRepository.class, () -> mock(CongestionStatsRepository.class))
            .withBean(CongestionResolver.class, () -> new CongestionResolver(new HolidayCalendar("")));

    @Test
    void defaultIsTheMock(CapturedOutput output) {
        runner.run(context -> {
            assertThat(context).hasSingleBean(CongestionLookup.class).doesNotHaveBean(DbCongestionConfig.class);
            assertThat(context.getBean(CongestionLookup.class)).isInstanceOf(MockCongestionLookup.class);
        });
        assertThat(output.getAll()).contains("Subway congestion is MOCK").doesNotContain("Subway congestion is DB");
    }

    @Test
    void emptyValueIsTheMock(CapturedOutput output) {
        runner.withPropertyValues("linkro.subway.congestion=").run(context -> {
            assertThat(context).hasSingleBean(CongestionLookup.class).doesNotHaveBean(DbCongestionConfig.class);
            assertThat(context.getBean(CongestionLookup.class)).isInstanceOf(MockCongestionLookup.class);
        });
        assertThat(output.getAll()).contains("Subway congestion is MOCK").doesNotContain("Subway congestion is DB");
    }

    @Test
    void dbRegistersOnlyTheDbLookup(CapturedOutput output) {
        runner.withPropertyValues("linkro.subway.congestion=db").run(context -> {
            assertThat(context).hasSingleBean(CongestionLookup.class).doesNotHaveBean(MockCongestionConfig.class);
            assertThat(context.getBean(CongestionLookup.class)).isInstanceOf(DbCongestionLookup.class);
        });
        assertThat(output.getAll().lines().filter(line -> line.contains("Subway congestion is DB"))).singleElement()
                .satisfies(line -> assertThat(line).contains("INFO"));
        assertThat(output.getAll()).doesNotContain("Subway congestion is MOCK");
    }
}
