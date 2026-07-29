-- Dataset versioning per CLAUDE.md section 7. Every completed import produces
-- a new dataset_version; the partial unique index enforces that at most one
-- version is active at a time (same "exactly one" pattern as the dim_*
-- is_unknown indexes in V2).

CREATE TABLE dataset_version (
    id               BIGSERIAL PRIMARY KEY,
    version_label    VARCHAR(100) NOT NULL,
    import_batch_id  BIGINT NOT NULL REFERENCES import_batch (id),
    is_active        BOOLEAN NOT NULL DEFAULT FALSE,
    row_count        INTEGER,
    activated_at     TIMESTAMPTZ,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX ux_dataset_version_single_active ON dataset_version (is_active) WHERE is_active = TRUE;
CREATE INDEX ix_dataset_version_import_batch_id ON dataset_version (import_batch_id);
