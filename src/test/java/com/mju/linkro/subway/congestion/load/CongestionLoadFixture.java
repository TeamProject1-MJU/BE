package com.mju.linkro.subway.congestion.load;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Sample CSV from src/test/resources and a manifest written next to it with the file's real SHA-256. */
public final class CongestionLoadFixture {
    public static final String MODEL = "HGB_20261103_39T";

    private CongestionLoadFixture() {}

    public static Path sampleCsv() {
        try {
            return Path.of(CongestionLoadFixture.class.getResource("/congestion-load/predictions_for_import.csv").toURI());
        } catch (URISyntaxException exception) {
            throw new IllegalStateException(exception);
        }
    }

    /** The SHA is computed at test time so line-ending conversion on checkout cannot break the fixture. */
    public static Path manifest(Path dir, Path csv, String sha) throws IOException {
        Path manifest = dir.resolve("manifest.json");
        Files.writeString(manifest, "{\"model_version\":\"" + MODEL + "\",\"output_sha256\":{\"" + csv.getFileName() + "\":\"" + sha + "\"}}");
        return manifest;
    }

    public static Path validManifest(Path dir, Path csv) throws IOException {
        return manifest(dir, csv, CongestionLoader.sha256(csv));
    }
}
