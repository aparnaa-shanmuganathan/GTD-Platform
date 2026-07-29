-- fact_incident per CLAUDE.md section 7. PK is eventid VARCHAR(12) (the real
-- GTD event identifier format, e.g. '197001000001').
--
-- FK columns to dim_organization/dim_attack_type/dim_weapon_type/dim_target_type
-- are NOT NULL: CLAUDE.md section 7 requires "not a nullable FK" for unknown
-- categorical values, and the six dimension tables from V2 each carry a seeded
-- is_unknown row precisely so these columns are never null - unknown values
-- point at the is_unknown row instead. This FK set is not explicitly
-- enumerated in CLAUDE.md section 7 beyond dataset_version_id and region_id;
-- it is required so the six mv_*_year_agg views in V5 (also required by
-- section 7) have something to group by. See CLAUDE.md section 12 for this
-- decision recorded in full.
--
-- nkill/nwound are nullable with CHECK (... >= 0) and are NEVER defaulted or
-- coalesced anywhere in this DDL (no DEFAULT 0, no COALESCE) - per CLAUDE.md
-- sections 7 and 8.

CREATE TABLE fact_incident (
    eventid                     VARCHAR(12) PRIMARY KEY,
    dataset_version_id          BIGINT NOT NULL REFERENCES dataset_version (id),

    country_id                  BIGINT NOT NULL REFERENCES dim_country (id),
    -- Denormalized onto the fact table (the one intentional 3NF exception -
    -- see CLAUDE.md section 7); consistency against dim_country.region_id is
    -- enforced below via trigger, not by application code alone.
    region_id                   BIGINT NOT NULL REFERENCES dim_region (id),
    attack_type_id               BIGINT NOT NULL REFERENCES dim_attack_type (id),
    weapon_type_id               BIGINT NOT NULL REFERENCES dim_weapon_type (id),
    target_type_id               BIGINT NOT NULL REFERENCES dim_target_type (id),
    organization_id              BIGINT NOT NULL REFERENCES dim_organization (id),

    event_year                  INTEGER NOT NULL,
    event_month                 INTEGER,
    event_day                   INTEGER,
    provstate                   VARCHAR(150),
    city                        VARCHAR(150),
    latitude                    DOUBLE PRECISION,
    longitude                   DOUBLE PRECISION,
    summary                     TEXT,
    target_description          VARCHAR(500),

    is_success                  BOOLEAN,
    is_suicide                  BOOLEAN,
    is_individual_perpetrator   BOOLEAN,
    is_property_damage          BOOLEAN,

    -- Never defaulted/coalesced to zero - CLAUDE.md sections 7 and 8.
    nkill                        INTEGER CHECK (nkill >= 0),
    nwound                       INTEGER CHECK (nwound >= 0),

    motive                       TEXT,
    created_at                   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX ix_fact_incident_dataset_version_id ON fact_incident (dataset_version_id);
CREATE INDEX ix_fact_incident_country_id ON fact_incident (country_id);
CREATE INDEX ix_fact_incident_region_id ON fact_incident (region_id);
CREATE INDEX ix_fact_incident_attack_type_id ON fact_incident (attack_type_id);
CREATE INDEX ix_fact_incident_weapon_type_id ON fact_incident (weapon_type_id);
CREATE INDEX ix_fact_incident_target_type_id ON fact_incident (target_type_id);
CREATE INDEX ix_fact_incident_organization_id ON fact_incident (organization_id);
CREATE INDEX ix_fact_incident_event_year ON fact_incident (event_year);

-- Trigger enforcing fact_incident.region_id consistency against
-- dim_country.region_id, per CLAUDE.md section 7.
CREATE FUNCTION fn_enforce_fact_incident_region_consistency() RETURNS TRIGGER AS $$
DECLARE
    expected_region_id BIGINT;
BEGIN
    SELECT region_id INTO expected_region_id FROM dim_country WHERE id = NEW.country_id;

    IF expected_region_id IS NULL THEN
        RAISE EXCEPTION 'fact_incident.country_id % does not exist in dim_country', NEW.country_id;
    END IF;

    IF NEW.region_id <> expected_region_id THEN
        RAISE EXCEPTION
            'fact_incident.region_id (%) does not match dim_country.region_id (%) for country_id %',
            NEW.region_id, expected_region_id, NEW.country_id;
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_fact_incident_region_consistency
    BEFORE INSERT OR UPDATE OF country_id, region_id ON fact_incident
    FOR EACH ROW
    EXECUTE FUNCTION fn_enforce_fact_incident_region_consistency();
