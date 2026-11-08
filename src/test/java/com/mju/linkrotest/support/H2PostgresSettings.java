package com.mju.linkrotest.support;

import java.sql.Connection;
import java.sql.SQLException;

/** H2-only adapter; actual PostgreSQL settings and locking are tested with Testcontainers. */
public final class H2PostgresSettings {
    private H2PostgresSettings() {}

    public static String currentSetting(Connection connection, String name) throws SQLException {
        if (!"transaction_isolation".equals(name)) {
            throw new SQLException("Unsupported test setting: " + name);
        }
        return switch (connection.getTransactionIsolation()) {
            case Connection.TRANSACTION_READ_COMMITTED -> "read committed";
            case Connection.TRANSACTION_REPEATABLE_READ -> "repeatable read";
            case Connection.TRANSACTION_SERIALIZABLE -> "serializable";
            case Connection.TRANSACTION_READ_UNCOMMITTED -> "read uncommitted";
            default -> throw new SQLException("Unsupported test isolation");
        };
    }
}
