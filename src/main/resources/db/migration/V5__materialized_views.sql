-- Materialized views per CLAUDE.md section 7, one per dimension x event_year.
-- All six share the same aggregate shape: incident_count, fatality_count,
-- injury_count, success_count.
--
-- fatality_count/injury_count use plain SUM(nkill)/SUM(nwound) with NO outer
-- COALESCE. SUM() already skips NULL inputs per standard SQL - it does not
-- treat them as zero - so a group where every incident has unknown casualties
-- correctly yields a NULL aggregate rather than a misleading 0. Wrapping the
-- result in COALESCE(..., 0) would violate the "nkill/nwound nulls are never
-- coalesced to zero" rule in CLAUDE.md sections 7/8, so it is deliberately
-- omitted here too.
--
-- AWS-UNK-001 exclusion of is_unknown dimension rows from named-entity
-- aggregates happens at QUERY TIME (by whichever service queries these
-- views, joining back to the dim_* table and filtering is_unknown), not
-- inside the views themselves - the views include every row so that
-- unknown-inclusive totals remain queryable too.
--
-- Views are created WITH DATA (the default) rather than WITH NO DATA: even
-- though fact_incident is empty until Phase 3's import pipeline runs,
-- REFRESH MATERIALIZED VIEW CONCURRENTLY requires the view to have been
-- populated at least once via a regular (non-concurrent) refresh or initial
-- creation. Creating WITH DATA now (against zero rows) satisfies that
-- precondition so CONCURRENTLY refreshes work immediately once real data
-- exists, and so the Phase 1 integration test can call CONCURRENTLY refresh
-- against seed data without an extra bootstrap step.
--
-- Each view has a unique index on (dimension_id, event_year), required by
-- Postgres for CONCURRENTLY refresh to be possible at all.

CREATE MATERIALIZED VIEW mv_country_year_agg AS
SELECT
    f.country_id,
    f.event_year,
    COUNT(*)                                   AS incident_count,
    SUM(f.nkill)                               AS fatality_count,
    SUM(f.nwound)                              AS injury_count,
    COUNT(*) FILTER (WHERE f.is_success)       AS success_count
FROM fact_incident f
GROUP BY f.country_id, f.event_year;

CREATE UNIQUE INDEX ux_mv_country_year_agg ON mv_country_year_agg (country_id, event_year);

CREATE MATERIALIZED VIEW mv_region_year_agg AS
SELECT
    f.region_id,
    f.event_year,
    COUNT(*)                                   AS incident_count,
    SUM(f.nkill)                               AS fatality_count,
    SUM(f.nwound)                              AS injury_count,
    COUNT(*) FILTER (WHERE f.is_success)       AS success_count
FROM fact_incident f
GROUP BY f.region_id, f.event_year;

CREATE UNIQUE INDEX ux_mv_region_year_agg ON mv_region_year_agg (region_id, event_year);

CREATE MATERIALIZED VIEW mv_organization_year_agg AS
SELECT
    f.organization_id,
    f.event_year,
    COUNT(*)                                   AS incident_count,
    SUM(f.nkill)                               AS fatality_count,
    SUM(f.nwound)                              AS injury_count,
    COUNT(*) FILTER (WHERE f.is_success)       AS success_count
FROM fact_incident f
GROUP BY f.organization_id, f.event_year;

CREATE UNIQUE INDEX ux_mv_organization_year_agg ON mv_organization_year_agg (organization_id, event_year);

CREATE MATERIALIZED VIEW mv_attacktype_year_agg AS
SELECT
    f.attack_type_id,
    f.event_year,
    COUNT(*)                                   AS incident_count,
    SUM(f.nkill)                               AS fatality_count,
    SUM(f.nwound)                              AS injury_count,
    COUNT(*) FILTER (WHERE f.is_success)       AS success_count
FROM fact_incident f
GROUP BY f.attack_type_id, f.event_year;

CREATE UNIQUE INDEX ux_mv_attacktype_year_agg ON mv_attacktype_year_agg (attack_type_id, event_year);

CREATE MATERIALIZED VIEW mv_weapontype_year_agg AS
SELECT
    f.weapon_type_id,
    f.event_year,
    COUNT(*)                                   AS incident_count,
    SUM(f.nkill)                               AS fatality_count,
    SUM(f.nwound)                              AS injury_count,
    COUNT(*) FILTER (WHERE f.is_success)       AS success_count
FROM fact_incident f
GROUP BY f.weapon_type_id, f.event_year;

CREATE UNIQUE INDEX ux_mv_weapontype_year_agg ON mv_weapontype_year_agg (weapon_type_id, event_year);

CREATE MATERIALIZED VIEW mv_targettype_year_agg AS
SELECT
    f.target_type_id,
    f.event_year,
    COUNT(*)                                   AS incident_count,
    SUM(f.nkill)                               AS fatality_count,
    SUM(f.nwound)                              AS injury_count,
    COUNT(*) FILTER (WHERE f.is_success)       AS success_count
FROM fact_incident f
GROUP BY f.target_type_id, f.event_year;

CREATE UNIQUE INDEX ux_mv_targettype_year_agg ON mv_targettype_year_agg (target_type_id, event_year);

-- Freshness tracking, per CLAUDE.md section 7. Phase 3's IMPORT completion
-- step is responsible for calling REFRESH MATERIALIZED VIEW CONCURRENTLY on
-- each view and updating its row here; this migration only creates and
-- seeds the tracking table.
CREATE TABLE materialized_view_status (
    view_name                  VARCHAR(100) PRIMARY KEY,
    last_refreshed_at          TIMESTAMPTZ,
    last_refresh_duration_ms   BIGINT,
    last_refresh_status        VARCHAR(20) NOT NULL DEFAULT 'NEVER_REFRESHED'
                                    CHECK (last_refresh_status IN ('NEVER_REFRESHED', 'SUCCESS', 'FAILED')),
    row_count                  BIGINT,
    updated_at                 TIMESTAMPTZ NOT NULL DEFAULT now()
);

INSERT INTO materialized_view_status (view_name) VALUES
    ('mv_country_year_agg'),
    ('mv_region_year_agg'),
    ('mv_organization_year_agg'),
    ('mv_attacktype_year_agg'),
    ('mv_weapontype_year_agg'),
    ('mv_targettype_year_agg');
