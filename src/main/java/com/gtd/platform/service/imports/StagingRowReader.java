package com.gtd.platform.service.imports;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.function.Consumer;
import javax.sql.DataSource;
import org.springframework.stereotype.Component;

/**
 * Streams {@code staging_incident} rows for one import batch without
 * loading all ~180k of them into memory at once.
 *
 * <p>Deliberately bypasses {@code JdbcTemplate}/Spring-managed transactions
 * and drives a raw JDBC {@link Connection} directly: the PostgreSQL driver
 * only honors {@code setFetchSize} (true server-side cursor streaming,
 * instead of buffering the whole result set client-side) when the
 * connection has {@code autoCommit=false} - but Spring's
 * {@code @Transactional(readOnly = true)} would additionally mark the
 * transaction read-only, and the {@link RowNormalizer}/{@link DimensionResolver}
 * work driven from inside the per-row callback needs to <em>write</em>
 * (creating new dimension rows). Using one dedicated, plain connection just
 * for this streaming read - separate from whatever connection those writes
 * borrow from the pool - sidesteps that conflict entirely rather than
 * fighting Spring's transaction semantics for it.
 */
@Component
public class StagingRowReader {

    private static final String SELECT_SQL = "SELECT id, eventid, iyear, imonth, iday, country_txt, region_txt, "
            + "provstate, city, latitude, longitude, summary, target1, attacktype1_txt, weaptype1_txt, "
            + "targtype1_txt, gname, nkill, nwound, success, suicide, individual, property, motive "
            + "FROM staging_incident WHERE import_batch_id = ? ORDER BY source_row_number";

    private final DataSource dataSource;

    public StagingRowReader(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public void streamRows(long importBatchId, Consumer<StagingRow> consumer) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement ps = connection.prepareStatement(
                    SELECT_SQL, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)) {
                ps.setFetchSize(1000);
                ps.setLong(1, importBatchId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        consumer.accept(mapRow(rs));
                    }
                }
            }
            connection.commit();
        }
    }

    private StagingRow mapRow(ResultSet rs) throws SQLException {
        return new StagingRow(
                rs.getLong("id"),
                rs.getString("eventid"),
                rs.getString("iyear"),
                rs.getString("imonth"),
                rs.getString("iday"),
                rs.getString("country_txt"),
                rs.getString("region_txt"),
                rs.getString("provstate"),
                rs.getString("city"),
                rs.getString("latitude"),
                rs.getString("longitude"),
                rs.getString("summary"),
                rs.getString("target1"),
                rs.getString("attacktype1_txt"),
                rs.getString("weaptype1_txt"),
                rs.getString("targtype1_txt"),
                rs.getString("gname"),
                rs.getString("nkill"),
                rs.getString("nwound"),
                rs.getString("success"),
                rs.getString("suicide"),
                rs.getString("individual"),
                rs.getString("property"),
                rs.getString("motive")
        );
    }
}
