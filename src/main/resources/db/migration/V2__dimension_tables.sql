-- Dimension tables per CLAUDE.md section 7. Each has a seeded is_unknown=TRUE
-- ROW (never a nullable FK on fact_incident) per AWS-UNK-001. A partial unique
-- index guarantees at most one is_unknown row per table, the same "exactly
-- one" pattern used for dataset_version.is_active in V3.
--
-- AWS-UNK-001 excludes is_unknown rows from named-entity aggregates at QUERY
-- TIME for Organization/Attack Type/Weapon Type/Target Type; Country/Region
-- use a different, staging-level exclusion mechanism (Phase 3 concern). All
-- six tables still get the same is_unknown row shape here for structural
-- consistency - only the downstream exclusion mechanism differs.

CREATE TABLE dim_region (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(150) NOT NULL UNIQUE,
    is_unknown  BOOLEAN NOT NULL DEFAULT FALSE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX ux_dim_region_single_unknown ON dim_region (is_unknown) WHERE is_unknown = TRUE;

CREATE TABLE dim_country (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(150) NOT NULL UNIQUE,
    region_id   BIGINT NOT NULL REFERENCES dim_region (id),
    is_unknown  BOOLEAN NOT NULL DEFAULT FALSE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX ux_dim_country_single_unknown ON dim_country (is_unknown) WHERE is_unknown = TRUE;
CREATE INDEX ix_dim_country_region_id ON dim_country (region_id);

CREATE TABLE dim_organization (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(255) NOT NULL UNIQUE,
    is_unknown  BOOLEAN NOT NULL DEFAULT FALSE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX ux_dim_organization_single_unknown ON dim_organization (is_unknown) WHERE is_unknown = TRUE;

CREATE TABLE dim_attack_type (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(150) NOT NULL UNIQUE,
    is_unknown  BOOLEAN NOT NULL DEFAULT FALSE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX ux_dim_attack_type_single_unknown ON dim_attack_type (is_unknown) WHERE is_unknown = TRUE;

CREATE TABLE dim_weapon_type (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(150) NOT NULL UNIQUE,
    is_unknown  BOOLEAN NOT NULL DEFAULT FALSE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX ux_dim_weapon_type_single_unknown ON dim_weapon_type (is_unknown) WHERE is_unknown = TRUE;

CREATE TABLE dim_target_type (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(150) NOT NULL UNIQUE,
    is_unknown  BOOLEAN NOT NULL DEFAULT FALSE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX ux_dim_target_type_single_unknown ON dim_target_type (is_unknown) WHERE is_unknown = TRUE;
