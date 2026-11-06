package org.box1.notification.store;

import java.sql.SQLException;

/** A database failure. Carries only the SQL state, so no row values reach logs or dead-letter headers. */
public class StoreException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final String sqlState;

    public StoreException(SQLException cause) {
        super("Database operation failed: SQLState " + cause.getSQLState());
        this.sqlState = cause.getSQLState();
    }

    public String sqlState() {
        return sqlState;
    }

    /** The database cannot serve at all. Waiting fixes this, and no event is at fault. */
    public boolean isUnavailable() {
        return sqlState == null || sqlState.startsWith("08") || sqlState.startsWith("53") || sqlState.startsWith("57P");
    }

    /** The data can never be stored: a bad value or a constraint it will always break. */
    public boolean isPermanent() {
        return sqlState != null && (sqlState.startsWith("22") || sqlState.startsWith("23") && !"23505".equals(sqlState));
    }
}
