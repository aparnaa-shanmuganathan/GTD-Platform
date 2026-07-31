-- platform_config: the CONFIG cross-cutting service's settings (CLAUDE.md
-- section 4), consolidating what earlier phases left as scattered hardcoded
-- defaults (Casualty's severity bands, Statistical's moving-average window/
-- outlier threshold, IMPORT's implicit APPEND/REPLACE choice, the JVM's
-- default session timeout) into one real, mutable table - see CLAUDE.md
-- section 12's "Phase 10 decisions" for which settings this table owns and
-- why, and why several of those earlier hardcoded defaults (severity bands,
-- moving-average window) are deliberately NOT migrated into this table.
--
-- DECISION: a single fixed-row table with explicit typed columns, not an
-- open-ended key/value table. This platform has consistently preferred
-- typed, constrained columns over EAV for small, fixed setting sets
-- (materialized_view_status is the same shape) - a key/value table would
-- lose CHECK-constraint validation per setting and gain nothing, since the
-- setting list is small and fixed, not open-ended. CHECK (id = 1) plus
-- seeding exactly one row is the same "exactly one of these" idea
-- dataset_version's partial unique index already established for a
-- different invariant, just simpler here since there is no history to
-- track - PlatformConfigService always reads/updates the row with id = 1.

CREATE TABLE platform_config (
    id                              BIGINT PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    session_timeout_minutes         INTEGER NOT NULL DEFAULT 30
                                         CHECK (session_timeout_minutes BETWEEN 5 AND 1440),
    default_import_mode             VARCHAR(20) NOT NULL DEFAULT 'APPEND'
                                         CHECK (default_import_mode IN ('APPEND', 'REPLACE')),
    materialized_view_auto_refresh  BOOLEAN NOT NULL DEFAULT TRUE,
    notify_on_import_completion     BOOLEAN NOT NULL DEFAULT TRUE,
    updated_at                      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by_admin_user_id        BIGINT REFERENCES admin_user (id)
);

INSERT INTO platform_config (id) VALUES (1);
