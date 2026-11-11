package com.mju.linkro.subway.fare;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

/** Fare table by station pair (#109): codes to stations, lowest fare per pair, unknown codes skipped. */
class FareTableTest {
    // 남태령-like station 1 has two codes (4호선 433, 7호선 2740); 삼각지-like station 2 has 428 and 2629; station 3 has 150.
    static final Map<String, Long> CODES = Map.of("433", 1L, "2740", 1L, "428", 2L, "2629", 2L, "150", 3L);

    static byte[] gzip(String text) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (GZIPOutputStream out = new GZIPOutputStream(bytes)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
        return bytes.toByteArray();
    }

    static FareTable load(String... rows) throws IOException {
        return FareTable.load(new ByteArrayInputStream(gzip(FareTable.HEADER + "\n" + String.join("\n", rows) + "\n")), CODES);
    }

    @Test
    void severalCodeCombinationsKeepTheLowestFare() throws IOException {
        FareTable table = load("433,428,9.0,1550", "433,2629,11.0,1650", "2740,428,12.0,1650", "2740,2629,10.5,1650",
                "428,433,9.0,1550", "150,428,4.0,1550");
        assertThat(table.fareBetween(1, 2)).hasValue(1550);
        assertThat(table.fareBetween(2, 1)).hasValue(1550);
        assertThat(table.fareBetween(3, 2)).hasValue(1550);
        // Directed: no row 428 -> 150.
        assertThat(table.fareBetween(2, 3)).isEmpty();
        assertThat(table.fareBetween(1, 99)).isEmpty();
        FareTable.LoadReport report = table.report();
        assertThat(report.rows()).isEqualTo(6);
        assertThat(report.stationPairs()).isEqualTo(3);
        assertThat(report.multiCodePairs()).isEqualTo(1);
        assertThat(report.multiCodePairsWithDifferentFares()).isEqualTo(1);
    }

    @Test
    void unknownCodesAndSameStationRowsAreSkipped() throws IOException {
        FareTable table = load("200,428,5.0,1550", "433,2740,0.0,0", "150,433,3.0,1550");
        assertThat(table.report().unmappedRows()).isEqualTo(1);
        assertThat(table.report().unmappedCodes()).containsExactly("200");
        assertThat(table.fareBetween(1, 1)).isEmpty();
        assertThat(table.fareBetween(3, 1)).hasValue(1550);
    }

    @Test
    void malformedFilesFail() {
        assertThatThrownBy(() -> FareTable.load(new ByteArrayInputStream(gzip("from,to\n")), CODES)).hasMessageContaining("header");
        assertThatThrownBy(() -> load("433,428,9.0")).hasMessageContaining("columns");
        assertThatThrownBy(() -> load("433,428,9.0,x")).hasMessageContaining("integer");
    }

    @Test
    void aLoadFailureGivesAnEmptyTableInsteadOfStoppingStartup() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        doThrow(new IllegalStateException("db down")).when(jdbc).query(anyString(), any(Map.class),
                any(org.springframework.jdbc.core.RowCallbackHandler.class));
        var lookup = new FareTableConfig().stationFareLookup(jdbc);
        assertThat(lookup.fareBetween(1, 2)).isEmpty();
        assertThat(FareTable.empty().fareBetween(1, 2)).isEmpty();
    }
}
