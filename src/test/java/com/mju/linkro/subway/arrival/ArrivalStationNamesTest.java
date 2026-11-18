package com.mju.linkro.subway.arrival;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The query name exceptions of station-query-names.csv as loaded by {@link ArrivalStationNames} (#166). */
class ArrivalStationNamesTest {
    /** The 21 rows added for #166 (2026-11-18 operating calls), in the issue's order. */
    static final String[][] ADDED = {
                {"5", "광나루", "광나루(장신대)"},
                {"5", "군자", "군자(능동)"},
                {"7", "군자", "군자(능동)"},
                {"5", "굽은다리", "굽은다리(강동구민회관앞)"},
                {"8", "남한산성입구", "남한산성입구(성남법원,검찰청)"},
                {"6", "대흥", "대흥(서강대앞)"},
                {"8", "몽촌토성", "몽촌토성(평화의문)"},
                {"7", "공릉", "공릉(서울산업대입구)"},
                {"7", "상도", "상도(중앙대앞)"},
                {"6", "상월곡", "상월곡(한국과학기술연구원)"},
                {"7", "숭실대입구", "숭실대입구(살피재)"},
                {"5", "신정", "신정(은행정)"},
                {"5", "아차산", "아차산(어린이대공원후문)"},
                {"6", "안암", "안암(고대병원앞)"},
                {"5", "오목교", "오목교(목동운동장앞)"},
                {"5", "올림픽공원", "올림픽공원(한국체대)"},
                {"6", "월곡", "월곡(동덕여대)"},
                {"5", "천호", "천호(풍납토성)"},
                {"8", "천호", "천호(풍납토성)"},
                {"6", "화랑대", "화랑대(서울여대입구)"},
                {"7", "자양", "뚝섬유원지"},
    };

    static List<String> resourceLines() throws IOException {
        try (InputStream in = ArrivalStationNames.class.getResourceAsStream(ArrivalStationNames.RESOURCE)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().toList();
        }
    }

    @Test
    void theAddedRowsMapToTheirQueryNames() {
        ArrivalStationNames names = new ArrivalStationNames();
        assertThat(ADDED.length).isEqualTo(21);
        for (String[] row : ADDED) {
            assertThat(names.queryName(row[0], row[1])).as("%s호선 %s", row[0], row[1]).isEqualTo(row[2]);
        }
    }

    @Test
    void aCommaInTheQueryNameStaysInOneCell() {
        assertThat(new ArrivalStationNames().queryName("8", "남한산성입구")).isEqualTo("남한산성입구(성남법원,검찰청)");
        assertThat(ArrivalStationNames.parse(List.of("line_code,station_name,query_name", "8,남한산성입구,남한산성입구(성남법원,검찰청)")))
                .containsExactly(Map.entry("8|남한산성입구", "남한산성입구(성남법원,검찰청)"));
        // Fewer than three cells is still an error.
        assertThatThrownBy(() -> ArrivalStationNames.parse(List.of("line_code,station_name,query_name", "8,남한산성입구")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void everyRowIsLoadedOnceWithNoDuplicateKey() throws IOException {
        List<String> rows = resourceLines().stream().map(String::strip)
                .filter(line -> !line.isEmpty() && !line.startsWith("#")).skip(1).toList();
        // 7 earlier rows + 21 rows of #166; parse rejects a repeated (line, DB name) key, so 28 rows give 28 keys.
        assertThat(rows).hasSize(28);
        assertThat(ArrivalStationNames.parse(resourceLines())).hasSize(28);
        assertThatThrownBy(() -> ArrivalStationNames.parse(List.of("line_code,station_name,query_name", "5,천호,천호(풍납토성)", "5,천호,천호")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("duplicate row");
    }

    @Test
    void theEarlierSevenRowsAreUnchanged() {
        ArrivalStationNames names = new ArrivalStationNames();
        assertThat(names.queryName("6", "응암")).isEqualTo("응암순환(상선)");
        assertThat(names.queryName("6", "새절")).isEqualTo("새절(신사)");
        assertThat(names.queryName("6", "증산")).isEqualTo("증산(명지대앞)");
        assertThat(names.queryName("6", "월드컵경기장")).isEqualTo("월드컵경기장(성산)");
        assertThat(names.queryName("7", "어린이대공원")).isEqualTo("어린이대공원(세종대)");
        assertThat(names.queryName("1", "서울역")).isEqualTo("서울");
        assertThat(names.queryName("4", "서울역")).isEqualTo("서울");
        // Per line: 5호선 자양 or 2호선 천호 are not mapped.
        assertThat(names.queryName("2", "천호")).isEqualTo("천호");
    }
}
