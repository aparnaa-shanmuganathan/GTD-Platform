package com.gtd.platform.service.imports;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The single PostgreSQL advisory lock guarding import concurrency (CLAUDE.md
 * section 8: "Import concurrency is a single PostgreSQL advisory lock (one
 * import at a time)"). Uses {@code pg_try_advisory_lock} - non-blocking - so
 * a second import attempt while one is running is rejected immediately
 * ({@link com.gtd.platform.shared.exception.ErrorCode#ERR_IMPORT_003}), not
 * queued.
 *
 * <p>Advisory locks are session-scoped in PostgreSQL, not transaction-scoped:
 * the lock is held by whichever physical connection acquired it, for as long
 * as that connection stays open (or until explicitly unlocked). A connection
 * borrowed-and-returned through Spring's normal transaction-per-call pattern
 * would risk the lock being silently released if the pool evicts an idle
 * connection mid-import. {@link #tryAcquire()} instead obtains one
 * dedicated {@link Connection} directly from the {@link DataSource} and
 * keeps it checked out - never returned to the pool - for the entire import
 * job; the caller holds the returned {@link Handle} for as long as the lock
 * must be held and closes it (releasing the lock, then the connection) in a
 * {@code finally} block.
 */
@Component
public class ImportAdvisoryLockService {

    private static final Logger log = LoggerFactory.getLogger(ImportAdvisoryLockService.class);

    /** Arbitrary fixed key identifying "the one import lock" - there is only ever one. */
    static final long IMPORT_LOCK_KEY = 7_241_982_001L;

    private final DataSource dataSource;

    public ImportAdvisoryLockService(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /** @return a held lock {@link Handle}, or empty if another import already holds it. */
    public Optional<Handle> tryAcquire() throws SQLException {
        Connection connection = dataSource.getConnection();
        boolean acquired = false;
        try {
            try (PreparedStatement ps = connection.prepareStatement("SELECT pg_try_advisory_lock(?)")) {
                ps.setLong(1, IMPORT_LOCK_KEY);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    acquired = rs.getBoolean(1);
                }
            }
            return acquired ? Optional.of(new Handle(connection)) : Optional.empty();
        } finally {
            if (!acquired) {
                connection.close();
            }
        }
    }

    public static final class Handle implements AutoCloseable {
        private final Connection connection;

        private Handle(Connection connection) {
            this.connection = connection;
        }

        @Override
        public void close() {
            try (PreparedStatement ps = connection.prepareStatement("SELECT pg_advisory_unlock(?)")) {
                ps.setLong(1, IMPORT_LOCK_KEY);
                ps.execute();
            } catch (SQLException e) {
                log.warn("Failed to explicitly release the import advisory lock; it will still be released "
                        + "when the underlying connection closes", e);
            } finally {
                try {
                    connection.close();
                } catch (SQLException e) {
                    log.warn("Failed to close the import advisory lock's dedicated connection", e);
                }
            }
        }
    }
}
