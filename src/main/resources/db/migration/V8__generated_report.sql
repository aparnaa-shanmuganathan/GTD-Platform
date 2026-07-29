-- generated_report per CLAUDE.md section 7: immutable once created,
-- filter_snapshot JSONB, dataset_version_id but deliberately NO FK to
-- fact_incident (a report must remain readable/listable even after the
-- underlying incidents it summarized have been superseded by a later
-- dataset_version).
--
-- Immutability reuses fn_reject_table_mutation() from V7__audit_log.sql -
-- same rationale (a DB trigger, not an application convention, is the only
-- thing that can't be quietly bypassed later).

CREATE TABLE generated_report (
    id                            BIGSERIAL PRIMARY KEY,
    report_name                   VARCHAR(255) NOT NULL,
    format                        VARCHAR(10) NOT NULL CHECK (format IN ('PDF', 'CSV')),
    status                        VARCHAR(20) NOT NULL DEFAULT 'GENERATED'
                                       CHECK (status IN ('GENERATED', 'FAILED')),
    dataset_version_id            BIGINT NOT NULL REFERENCES dataset_version (id),
    filter_snapshot               JSONB NOT NULL,
    file_path                     VARCHAR(1000),
    generated_by_admin_user_id    BIGINT REFERENCES admin_user (id),
    generated_at                  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX ix_generated_report_dataset_version_id ON generated_report (dataset_version_id);
CREATE INDEX ix_generated_report_generated_at ON generated_report (generated_at);

CREATE TRIGGER trg_generated_report_no_update
    BEFORE UPDATE ON generated_report
    FOR EACH ROW
    EXECUTE FUNCTION fn_reject_table_mutation();

CREATE TRIGGER trg_generated_report_no_delete
    BEFORE DELETE ON generated_report
    FOR EACH ROW
    EXECUTE FUNCTION fn_reject_table_mutation();
