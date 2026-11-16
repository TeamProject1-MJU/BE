package com.mju.linkro.subway.timetable;

import com.mju.linkro.subway.timetable.TimetableProperties.TransferOverride;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.core.io.support.ResourcePropertySource;
import static org.assertj.core.api.Assertions.*;

/**
 * subway-transfer-overrides.properties (#151) read the way TrainTimetableConfig's @PropertySource reads it (UTF-8) and
 * bound to {@link TimetableProperties}. Whether every entry matches the migrated database is
 * TransferOverridesPostgresTest's job.
 */
class TransferOverridesFileTest {

    static List<TransferOverride> overrides() throws IOException {
        ResourcePropertySource source = new ResourcePropertySource(
                new EncodedResource(new ClassPathResource("subway-transfer-overrides.properties"), StandardCharsets.UTF_8));
        return new Binder(ConfigurationPropertySources.from(source))
                .bind("linkro.subway.timetable", TimetableProperties.class).get().getTransferOverrides();
    }

    static String text(TransferOverride entry) {
        return entry.getStation() + " " + entry.getFromLine() + "-" + entry.getToLine() + " " + entry.getSeconds();
    }

    @Test
    void theFileBindsThirtyNinePairsWithKoreanNames() throws IOException {
        List<TransferOverride> overrides = overrides();
        assertThat(overrides).hasSize(39);
        assertThat(overrides).extracting(TransferOverridesFileTest::text).contains(
                "충무로 3-4 120", "천호 5-8 60", "노원 4-7 480", "총신대입구(이수) 4-7 240", "종로3가 1-5 420",
                "서울역 1-4 300", "종로3가 1-3 187", "동대문역사문화공원 2-5 313", "을지로4가 2-5 143");
        assertThat(text(overrides.get(0))).isEqualTo("충무로 3-4 120");
        assertThat(text(overrides.get(38))).isEqualTo("을지로4가 2-5 143");
    }

    @Test
    void everyEntryIsCompleteAndEveryPairAppearsOnce() throws IOException {
        Set<String> pairs = new HashSet<>();
        for (TransferOverride entry : overrides()) {
            assertThat(entry.getStation()).isNotBlank();
            assertThat(entry.getFromLine()).isNotBlank().isNotEqualTo(entry.getToLine());
            assertThat(entry.getToLine()).isNotBlank();
            assertThat(entry.getSeconds()).isPositive();
            String low = entry.getFromLine().compareTo(entry.getToLine()) < 0 ? entry.getFromLine() : entry.getToLine();
            String high = low.equals(entry.getFromLine()) ? entry.getToLine() : entry.getFromLine();
            assertThat(pairs.add(entry.getStation() + " " + low + "-" + high)).as(text(entry)).isTrue();
        }
    }
}
