-- audit_log per CLAUDE.md section 7 ("append-only, no updates/deletes").
--
-- DECISION: append-only is enforced with a DB-level trigger (fn_reject_table_mutation
-- below), not an application-level convention. A trigger cannot be bypassed
-- by a future service/repository that forgets the convention, a raw SQL
-- console session, or a bug in the ORM layer - only a DB migration can lift
-- it, which is the right amount of friction for an audit trail. See
-- CLAUDE.md section 12.
--
-- fn_reject_table_mutation() is intentionally generic (keyed off TG_TABLE_NAME/
-- TG_OP) so V8__generated_report.sql can reuse it for that table's own
-- immutability requirement instead of duplicating the function.

CREATE TABLE audit_log (
    id                     BIGSERIAL PRIMARY KEY,
    occurred_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    actor_admin_user_id    BIGINT REFERENCES admin_user (id),
    action_type            VARCHAR(100) NOT NULL,
    entity_type            VARCHAR(100),
    entity_id              VARCHAR(100),
    details                JSONB,
    ip_address             VARCHAR(45)
);

CREATE INDEX ix_audit_log_occurred_at ON audit_log (occurred_at);
CREATE INDEX ix_audit_log_actor_admin_user_id ON audit_log (actor_admin_user_id);

CREATE FUNCTION fn_reject_table_mutation() RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION '% is append-only/immutable: % is not permitted', TG_TABLE_NAME, TG_OP;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_audit_log_no_update
    BEFORE UPDATE ON audit_log
    FOR EACH ROW
    EXECUTE FUNCTION fn_reject_table_mutation();

CREATE TRIGGER trg_audit_log_no_delete
    BEFORE DELETE ON audit_log
    FOR EACH ROW
    EXECUTE FUNCTION fn_reject_table_mutation();
