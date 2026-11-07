package com.mju.linkro.subway.congestion.load;

import java.io.PrintStream;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Map;

/**
 * Command-line entry, separate from the application start-up (no Spring context, no Flyway).
 *
 * <pre>
 * DB_URL=jdbc:postgresql://... DB_USERNAME=... DB_PASSWORD=... \
 *   ./gradlew loadCongestion --args="--csv=/path/predictions_for_import.csv --manifest=/path/manifest.json"
 * </pre>
 * The manifest SHA-256 is checked first; connection settings are read and the database is contacted only after it
 * passes. The database must already be migrated to V13. Exit code 0 on success, 1 on any failure (nothing is written).
 */
public final class CongestionLoadTool {
    /** Opens the database connection; replaced in tests to prove no connection is attempted. */
    interface Connector {
        Connection connect(String url, String user, String password) throws SQLException;
    }

    private CongestionLoadTool() {}

    public static void main(String[] args) {
        System.exit(run(args, System.getenv(), DriverManager::getConnection, System.out, System.err));
    }

    static int run(String[] args, Map<String, String> env, Connector connector, PrintStream out, PrintStream err) {
        try {
            Path csv = Path.of(argument(args, "--csv"));
            Path manifest = Path.of(argument(args, "--manifest"));
            CongestionLoader.verify(csv, manifest);
            String url = env(env, "DB_URL");
            try (Connection connection = connector.connect(url, env(env, "DB_USERNAME"), env(env, "DB_PASSWORD"))) {
                out.print(new CongestionLoader(connection).load(csv, manifest).format());
            }
            return 0;
        } catch (Exception exception) {
            err.println("Congestion load failed, nothing was written: " + exception.getMessage());
            return 1;
        }
    }

    private static String argument(String[] args, String name) {
        for (String arg : args) {
            if (arg.startsWith(name + "=")) return arg.substring(name.length() + 1);
        }
        throw new IllegalArgumentException("missing argument " + name + "=<path>");
    }

    private static String env(Map<String, String> env, String name) {
        String value = env.get(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("environment variable " + name + " is not set");
        return value;
    }
}
