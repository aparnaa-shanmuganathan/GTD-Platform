package com.gtd.platform.service.imports;

import com.gtd.platform.shared.db.ImportBatch.ImportMode;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Bulk-upserts {@link FactIncidentRecord}s via raw batched JDBC - never JPA
 * entity-by-entity saves, which cannot hit the &le;5 min/180k-row NFR.
 *
 * <p>Implements the row-level upsert semantics decided for Phase 3 (CLAUDE.md
 * section 12): REPLACE mode {@code INSERT ... ON CONFLICT (eventid) DO UPDATE}
 * (overwrite an existing row's data, reassign its {@code dataset_version_id});
 * APPEND mode {@code INSERT ... ON CONFLICT (eventid) DO NOTHING} (leave any
 * existing row untouched). Neither mode deletes rows absent from the new
 * file - {@code fact_incident}'s single-column {@code eventid} PK (CLAUDE.md
 * section 7) means the table only ever holds one current row per event, not
 * a historical snapshot per {@code dataset_version}.
 *
 * <p>{@code region_id} is always the value {@link DimensionResolver} paired
 * with the resolved {@code country_id} - never independently derived - so
 * {@code fact_incident}'s region-consistency trigger (V4 migration) passes
 * by construction on every row.
 */
@Component
public class FactIncidentBulkWriter {

    private static final String COLUMNS = "eventid, dataset_version_id, country_id, region_id, attack_type_id, "
            + "weapon_type_id, target_type_id, organization_id, event_year, event_month, event_day, "
            + "provstate, city, latitude, longitude, summary, target_description, "
            + "is_success, is_suicide, is_individual_perpetrator, is_property_damage, nkill, nwound, motive, created_at";

    private static final String VALUES_PLACEHOLDERS =
            "?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now()";

    private static final String UPSERT_REPLACE_SQL = "INSERT INTO fact_incident (" + COLUMNS + ") "
            + "VALUES (" + VALUES_PLACEHOLDERS + ") "
            + "ON CONFLICT (eventid) DO UPDATE SET "
            + "dataset_version_id = EXCLUDED.dataset_version_id, "
            + "country_id = EXCLUDED.country_id, "
            + "region_id = EXCLUDED.region_id, "
            + "attack_type_id = EXCLUDED.attack_type_id, "
            + "weapon_type_id = EXCLUDED.weapon_type_id, "
            + "target_type_id = EXCLUDED.target_type_id, "
            + "organization_id = EXCLUDED.organization_id, "
            + "event_year = EXCLUDED.event_year, "
            + "event_month = EXCLUDED.event_month, "
            + "event_day = EXCLUDED.event_day, "
            + "provstate = EXCLUDED.provstate, "
            + "city = EXCLUDED.city, "
            + "latitude = EXCLUDED.latitude, "
            + "longitude = EXCLUDED.longitude, "
            + "summary = EXCLUDED.summary, "
            + "target_description = EXCLUDED.target_description, "
            + "is_success = EXCLUDED.is_success, "
            + "is_suicide = EXCLUDED.is_suicide, "
            + "is_individual_perpetrator = EXCLUDED.is_individual_perpetrator, "
            + "is_property_damage = EXCLUDED.is_property_damage, "
            + "nkill = EXCLUDED.nkill, "
            + "nwound = EXCLUDED.nwound, "
            + "motive = EXCLUDED.motive";

    private static final String UPSERT_APPEND_SQL = "INSERT INTO fact_incident (" + COLUMNS + ") "
            + "VALUES (" + VALUES_PLACEHOLDERS + ") "
            + "ON CONFLICT (eventid) DO NOTHING";

    private final JdbcTemplate jdbcTemplate;

    public FactIncidentBulkWriter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * @return the records skipped because their eventid already existed -
     *         only ever non-empty in APPEND mode, where an existing eventid
     *         is left untouched rather than merged. Returned as the full
     *         records (not just eventids) and matched back to
     *         {@code updateCounts} by index, not by eventid value: two rows
     *         in the same batch can legitimately share an eventid (the
     *         first inserts, the second then genuinely conflicts against
     *         it), and matching by value alone would misattribute which
     *         occurrence was actually skipped.
     */
    public List<FactIncidentRecord> writeBatch(List<FactIncidentRecord> batch, ImportMode mode) {
        if (batch.isEmpty()) {
            return List.of();
        }
        String sql = (mode == ImportMode.REPLACE) ? UPSERT_REPLACE_SQL : UPSERT_APPEND_SQL;
        List<Object[]> args = batch.stream().map(this::toArgs).toList();
        int[] updateCounts = jdbcTemplate.batchUpdate(sql, args);

        if (mode != ImportMode.APPEND) {
            return List.of();
        }
        List<FactIncidentRecord> skipped = new ArrayList<>();
        for (int i = 0; i < updateCounts.length; i++) {
            if (updateCounts[i] == 0) {
                skipped.add(batch.get(i));
            }
        }
        return skipped;
    }

    private Object[] toArgs(FactIncidentRecord r) {
        return new Object[] {
                r.eventId(), r.datasetVersionId(), r.countryId(), r.regionId(), r.attackTypeId(),
                r.weaponTypeId(), r.targetTypeId(), r.organizationId(), r.eventYear(), r.eventMonth(), r.eventDay(),
                r.provstate(), r.city(), r.latitude(), r.longitude(), r.summary(), r.targetDescription(),
                r.success(), r.suicide(), r.individualPerpetrator(), r.propertyDamage(), r.nkill(), r.nwound(), r.motive()
        };
    }
}
