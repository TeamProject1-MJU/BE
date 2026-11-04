package com.mju.linkro.subway.congestion.load;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class CongestionLoadToolTest {
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();
    private final AtomicInteger connections = new AtomicInteger();

    private int run(Path csv, Path manifest, Map<String, String> env) {
        return CongestionLoadTool.run(new String[] {"--csv=" + csv, "--manifest=" + manifest}, env,
                (url, user, password) -> {
                    connections.incrementAndGet();
                    throw new java.sql.SQLException("connection must not be attempted");
                },
                new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8));
    }

    @Test
    void shaMismatchIsReportedBeforeAnyConnectionEvenWithABadUrl(@TempDir Path dir) throws Exception {
        Path csv = CongestionLoadFixture.sampleCsv();
        Path manifest = CongestionLoadFixture.manifest(dir, csv, "0".repeat(64));

        int code = run(csv, manifest, Map.of("DB_URL", "jdbc:postgresql://wrong-host:1/none", "DB_USERNAME", "x", "DB_PASSWORD", "y"));

        assertThat(code).isEqualTo(1);
        assertThat(connections).hasValue(0);
        assertThat(err.toString(StandardCharsets.UTF_8)).startsWith("Congestion load failed, nothing was written: SHA-256 mismatch");
        assertThat(out.toString(StandardCharsets.UTF_8)).isEmpty();
    }

    @Test
    void shaMismatchComesFirstEvenWithoutConnectionSettings(@TempDir Path dir) throws Exception {
        Path csv = CongestionLoadFixture.sampleCsv();
        int code = run(csv, CongestionLoadFixture.manifest(dir, csv, "0".repeat(64)), Map.of());

        assertThat(code).isEqualTo(1);
        assertThat(connections).hasValue(0);
        assertThat(err.toString(StandardCharsets.UTF_8)).contains("SHA-256 mismatch").doesNotContain("DB_URL");
    }

    @Test
    void connectionSettingsAreReadOnlyAfterTheShaPasses(@TempDir Path dir) throws Exception {
        Path csv = CongestionLoadFixture.sampleCsv();
        int code = run(csv, CongestionLoadFixture.validManifest(dir, csv), Map.of());

        assertThat(code).isEqualTo(1);
        assertThat(connections).hasValue(0);
        assertThat(err.toString(StandardCharsets.UTF_8)).contains("environment variable DB_URL is not set");
    }

    @Test
    void validShaThenTriesToConnect(@TempDir Path dir) throws Exception {
        Path csv = CongestionLoadFixture.sampleCsv();
        int code = run(csv, CongestionLoadFixture.validManifest(dir, csv),
                Map.of("DB_URL", "jdbc:postgresql://wrong-host:1/none", "DB_USERNAME", "x", "DB_PASSWORD", "y"));

        assertThat(code).isEqualTo(1);
        assertThat(connections).hasValue(1);
        assertThat(err.toString(StandardCharsets.UTF_8)).contains("connection must not be attempted");
    }
}
