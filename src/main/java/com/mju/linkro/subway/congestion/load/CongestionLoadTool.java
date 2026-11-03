package com.mju.linkro.subway.congestion.load;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;

/**
 * Command-line entry, separate from the application start-up (no Spring context, no Flyway).
 *
 * <pre>
 * DB_URL=jdbc:postgresql://... DB_USERNAME=... DB_PASSWORD=... \
 *   ./gradlew loadCongestion --args="--csv=/path/predictions_for_import.csv --manifest=/path/manifest.json"
 * </pre>
 * The database must already be migrated to V13. Exit code 0 on success, 1 on any failure (nothing is written).
 */
public final class CongestionLoadTool {
    private CongestionLoadTool() {}

    public static void main(String[] args) {
        try {
            Path csv = Path.of(argument(args, "--csv"));
            Path manifest = Path.of(argument(args, "--manifest"));
            String url = env("DB_URL");
            try (Connection connection = DriverManager.getConnection(url, env("DB_USERNAME"), env("DB_PASSWORD"))) {
                System.out.print(new CongestionLoader(connection).load(csv, manifest).format());
            }
        } catch (Exception exception) {
            System.err.println("Congestion load failed, nothing was written: " + exception.getMessage());
            System.exit(1);
        }
    }

    private static String argument(String[] args, String name) {
        for (String arg : args) {
            if (arg.startsWith(name + "=")) return arg.substring(name.length() + 1);
        }
        throw new IllegalArgumentException("missing argument " + name + "=<path>");
    }

    private static String env(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("environment variable " + name + " is not set");
        return value;
    }
}
