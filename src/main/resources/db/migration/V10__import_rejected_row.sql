-- import_rejected_row: queryable rejection detail for every staging row the
-- IMPORT service (Phase 3) declines to normalize into fact_incident, or
-- skips as a within-batch duplicate under APPEND mode. Not part of the
-- original CLAUDE.md section 7 schema list - added because the Phase 3
-- prompt explicitly requires rejection detail to stay queryable for the
-- future Data Quality Center, and nothing in Phase 1's schema captured it.
-- See CLAUDE.md section 12 for the decision recorded in full.

CREATE TABLE import_rejected_row (
    id                    BIGSERIAL PRIMARY KEY,
    import_batch_id       BIGINT NOT NULL REFERENCES import_batch (id),
    staging_incident_id   BIGINT NOT NULL REFERENCES staging_incident (id),
    eventid               TEXT,
    rejection_reason       VARCHAR(60) NOT NULL,
    rejection_detail       TEXT,
    rejected_at           TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX ix_import_rejected_row_import_batch_id ON import_rejected_row (import_batch_id);
CREATE INDEX ix_import_rejected_row_rejection_reason ON import_rejected_row (rejection_reason);
