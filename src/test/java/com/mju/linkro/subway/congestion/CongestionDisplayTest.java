package com.mju.linkro.subway.congestion;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/** Rule E: integer half-up, capped below the stored level's upper bound. */
class CongestionDisplayTest {

    @ParameterizedTest(name = "{0} {1} -> {2}")
    @CsvSource({
            // plain rounding
            "92.5, NORMAL, 93", "92.49, NORMAL, 92", "61.04, NORMAL, 61", "8.97, COMFORTABLE, 9",
            // capped at the upper bound of the stored level
            "34.5, COMFORTABLE, 34", "34.95326485813263, COMFORTABLE, 34", "49.6, RELAXED, 49",
            "99.96055952795814, NORMAL, 99", "149.5, CROWDED, 149",
            // just above a lower bound: never pushed below it
            "35.0, RELAXED, 35", "50.0, NORMAL, 50", "100.4, CROWDED, 100", "150.2, VERY_CROWDED, 150",
            // ESTIMATED values follow the same rule
            "34.9999999999985, COMFORTABLE, 34",
            // no cap for VERY_CROWDED
            "199.5, VERY_CROWDED, 200", "0, COMFORTABLE, 0"})
    void examples(String pct, String level, int shown) {
        assertThat(CongestionResolver.display(new BigDecimal(pct), level)).isEqualTo(shown);
    }

    /** Every value near every level bound shows a pct whose own level is the stored one. */
    @Test
    void shownPctNeverContradictsTheStoredLevel() {
        List<BigDecimal> values = new ArrayList<>();
        for (int bound : new int[] {0, 35, 50, 100, 150}) {
            for (String delta : new String[] {"-1", "-0.5000000000001", "-0.5", "-0.4999999999999", "-0.000000000001",
                    "0", "0.000000000001", "0.4999999999999", "0.5", "0.5000000000001", "1"}) {
                BigDecimal value = BigDecimal.valueOf(bound).add(new BigDecimal(delta));
                if (value.signum() >= 0) values.add(value);
            }
        }
        for (int i = 0; i <= 2000; i++) values.add(BigDecimal.valueOf(i, 1)); // 0.0 .. 200.0 by 0.1
        assertThat(values).hasSizeGreaterThan(2000).allSatisfy(value -> {
            String stored = levelOf(value);
            int shown = CongestionResolver.display(value, stored);
            assertThat(levelOf(BigDecimal.valueOf(shown))).as("%s -> %d", value, shown).isEqualTo(stored);
            // Half up never lowers below the lower bound, and the cap moves by less than one.
            assertThat(BigDecimal.valueOf(shown).subtract(value).abs()).isLessThanOrEqualTo(BigDecimal.ONE);
        });
    }

    /** ck_cong_level: lower bound inclusive, upper bound exclusive. */
    private static String levelOf(BigDecimal pct) {
        if (pct.compareTo(BigDecimal.valueOf(35)) < 0) return "COMFORTABLE";
        if (pct.compareTo(BigDecimal.valueOf(50)) < 0) return "RELAXED";
        if (pct.compareTo(BigDecimal.valueOf(100)) < 0) return "NORMAL";
        return pct.compareTo(BigDecimal.valueOf(150)) < 0 ? "CROWDED" : "VERY_CROWDED";
    }
}
