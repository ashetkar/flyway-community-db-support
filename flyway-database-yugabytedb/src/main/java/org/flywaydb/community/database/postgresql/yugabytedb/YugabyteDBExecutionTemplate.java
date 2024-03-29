package org.flywaydb.community.database.postgresql.yugabytedb;

import lombok.CustomLog;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.configuration.Configuration;
import org.flywaydb.core.internal.exception.FlywaySqlException;
import org.flywaydb.core.internal.jdbc.JdbcTemplate;

import java.sql.SQLException;
import java.util.HashMap;
import java.util.concurrent.Callable;

@CustomLog
public class YugabyteDBExecutionTemplate {

    private final Configuration configuration;
    private final JdbcTemplate jdbcTemplate;
    private final long tableHash;
    private final HashMap<Long, Boolean> tableEntries = new HashMap<>();


    YugabyteDBExecutionTemplate(Configuration configuration, JdbcTemplate jdbcTemplate, int hash) {
        this.configuration = configuration;
        this.jdbcTemplate = jdbcTemplate;
        this.tableHash = hash;
    }

    public <T> T execute(Callable<T> callable) {
        Exception error = null;
        try {
            lock();
            return callable.call();
        } catch (RuntimeException e) {
            error = e;
            throw e;
        } catch (Exception e) {
            error = e;
            throw new FlywayException(e);
        } finally {
            unlock(error);
        }
    }

    private void lock() throws SQLException {
        try {
            if (!tableEntries.containsKey(tableHash)) {
                try {
                    jdbcTemplate.execute("INSERT INTO " + YugabyteDBDatabase.LOCK_TABLE_NAME + " VALUES (" + tableHash + ", 'false', NOW());");
                    tableEntries.put(tableHash, true);
                } catch (SQLException e) {
                    if ("23505".equals(e.getSQLState())) {
                        // 23505 == UNIQUE_VIOLATION
                        LOG.debug("Table entry already added");
                    } else {
                        throw new FlywaySqlException("Could not initialize lock for table " + YugabyteDBDatabase.LOCK_TABLE_NAME, e);
                    }
                }
            }

            jdbcTemplate.execute("BEGIN;");
            jdbcTemplate.queryForBoolean("SELECT locked FROM " + YugabyteDBDatabase.LOCK_TABLE_NAME + " WHERE  table_hash = " + tableHash + " FOR UPDATE;");
        } catch (SQLException e) {
            throw new FlywaySqlException("Trying to acquire lock failed", e);
        }
    }

    private void unlock(Exception rethrow) throws FlywaySqlException {
        try {
            jdbcTemplate.execute("COMMIT;");
        } catch (SQLException e) {
            LOG.error("Commit failed: " + e);
            if (rethrow == null) {
                throw new FlywaySqlException("Commit failed", e);
            }
        }
    }

}
