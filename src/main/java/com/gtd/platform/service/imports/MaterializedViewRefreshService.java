package com.gtd.platform.service.imports;

import com.gtd.platform.shared.db.MaterializedViewStatus;
import com.gtd.platform.shared.db.MaterializedViewStatus.RefreshStatus;
import com.gtd.platform.shared.db.MaterializedViewStatusRepository;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Refreshes the six {@code mv_*_year_agg} views {@code CONCURRENTLY} and
 * updates {@code materialized_view_status} (CLAUDE.md section 7), per
 * CLAUDE.md section 12's "Phase 3's IMPORT completion step is responsible
 * for calling REFRESH ... CONCURRENTLY" note left in the V5 migration.
 *
 * <p>Deliberately <strong>not</strong> {@code @Transactional}, and must
 * never be called from within a transaction: PostgreSQL rejects
 * {@code REFRESH MATERIALIZED VIEW CONCURRENTLY} inside a transaction block.
 * {@link ImportConfirmationService} calls this only after
 * {@link DatasetVersionActivationService}'s own (separate, transactional)
 * activation has already committed - not the same object, so Spring's
 * {@code @Transactional} proxy for that call isn't bypassed by self-invocation.
 */
@Service
public class MaterializedViewRefreshService {

    private static final Logger log = LoggerFactory.getLogger(MaterializedViewRefreshService.class);

    static final List<String> MATERIALIZED_VIEWS = List.of(
            "mv_country_year_agg",
            "mv_region_year_agg",
            "mv_organization_year_agg",
            "mv_attacktype_year_agg",
            "mv_weapontype_year_agg",
            "mv_targettype_year_agg"
    );

    private final JdbcTemplate jdbcTemplate;
    private final MaterializedViewStatusRepository materializedViewStatusRepository;

    public MaterializedViewRefreshService(JdbcTemplate jdbcTemplate,
            MaterializedViewStatusRepository materializedViewStatusRepository) {
        this.jdbcTemplate = jdbcTemplate;
        this.materializedViewStatusRepository = materializedViewStatusRepository;
    }

    /** Refreshes every view; the first failure aborts the remaining refreshes and propagates. */
    public void refreshAll() {
        for (String view : MATERIALIZED_VIEWS) {
            refreshOne(view);
        }
    }

    private void refreshOne(String viewName) {
        long start = System.currentTimeMillis();
        try {
            jdbcTemplate.execute("REFRESH MATERIALIZED VIEW CONCURRENTLY " + viewName);
            long durationMs = System.currentTimeMillis() - start;
            Long rowCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + viewName, Long.class);
            updateStatus(viewName, durationMs, RefreshStatus.SUCCESS, rowCount);
        } catch (RuntimeException e) {
            long durationMs = System.currentTimeMillis() - start;
            updateStatus(viewName, durationMs, RefreshStatus.FAILED, null);
            log.error("Failed to refresh materialized view {}", viewName, e);
            throw e;
        }
    }

    private void updateStatus(String viewName, long durationMs, RefreshStatus status, Long rowCount) {
        MaterializedViewStatus entity = materializedViewStatusRepository.findById(viewName)
                .orElseGet(() -> {
                    MaterializedViewStatus created = new MaterializedViewStatus();
                    created.setViewName(viewName);
                    return created;
                });
        entity.setLastRefreshedAt(Instant.now());
        entity.setLastRefreshDurationMs(durationMs);
        entity.setLastRefreshStatus(status);
        entity.setRowCount(rowCount);
        entity.setUpdatedAt(Instant.now());
        materializedViewStatusRepository.save(entity);
    }
}
