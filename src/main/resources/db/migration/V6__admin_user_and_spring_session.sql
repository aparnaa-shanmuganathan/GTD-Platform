-- Auth per CLAUDE.md section 7, reconciled per this phase's prompt:
--
-- DECISION: admin_session (mentioned alongside admin_user in the original
-- spec/handoff doc) is NOT created as a separate table. Spring Session's own
-- JDBC schema (spring_session / spring_session_attributes below, copied
-- verbatim from spring-session-jdbc-3.3.2's official
-- org/springframework/session/jdbc/schema-postgresql.sql) is the sole store
-- for active sessions. Login/logout auditing - the "audit-oriented" concern
-- admin_session might otherwise have covered - is handled entirely by
-- audit_log (V7), which is the correct home for it since it is append-only
-- and already the platform's single audit mechanism; a second, narrower
-- audit table would just duplicate that. See CLAUDE.md section 12 for the
-- full writeup of this decision.

CREATE TABLE admin_user (
    id              BIGSERIAL PRIMARY KEY,
    username        VARCHAR(100) NOT NULL UNIQUE,
    password_hash   VARCHAR(255) NOT NULL,
    full_name       VARCHAR(200),
    is_active       BOOLEAN NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_login_at   TIMESTAMPTZ
);

ALTER TABLE import_batch
    ADD CONSTRAINT fk_import_batch_initiated_by_admin_user
    FOREIGN KEY (initiated_by_admin_user_id) REFERENCES admin_user (id);

-- --- Spring Session JDBC schema (verbatim, PostgreSQL variant) ---
-- Source: spring-session-jdbc-3.3.2.jar!/org/springframework/session/jdbc/schema-postgresql.sql
-- Schema initialization is otherwise disabled (spring.session.jdbc.initialize-schema=never
-- in application.yml, set in Phase 0) precisely because these tables now exist
-- via Flyway instead - Flyway, not Spring Session's own initializer, owns this
-- schema going forward. spring.session.jdbc.cleanup-cron can be re-enabled now
-- that spring_session exists (see CLAUDE.md section 12).

CREATE TABLE SPRING_SESSION (
	PRIMARY_ID CHAR(36) NOT NULL,
	SESSION_ID CHAR(36) NOT NULL,
	CREATION_TIME BIGINT NOT NULL,
	LAST_ACCESS_TIME BIGINT NOT NULL,
	MAX_INACTIVE_INTERVAL INT NOT NULL,
	EXPIRY_TIME BIGINT NOT NULL,
	PRINCIPAL_NAME VARCHAR(100),
	CONSTRAINT SPRING_SESSION_PK PRIMARY KEY (PRIMARY_ID)
);

CREATE UNIQUE INDEX SPRING_SESSION_IX1 ON SPRING_SESSION (SESSION_ID);
CREATE INDEX SPRING_SESSION_IX2 ON SPRING_SESSION (EXPIRY_TIME);
CREATE INDEX SPRING_SESSION_IX3 ON SPRING_SESSION (PRINCIPAL_NAME);

CREATE TABLE SPRING_SESSION_ATTRIBUTES (
	SESSION_PRIMARY_ID CHAR(36) NOT NULL,
	ATTRIBUTE_NAME VARCHAR(200) NOT NULL,
	ATTRIBUTE_BYTES BYTEA NOT NULL,
	CONSTRAINT SPRING_SESSION_ATTRIBUTES_PK PRIMARY KEY (SESSION_PRIMARY_ID, ATTRIBUTE_NAME),
	CONSTRAINT SPRING_SESSION_ATTRIBUTES_FK FOREIGN KEY (SESSION_PRIMARY_ID) REFERENCES SPRING_SESSION(PRIMARY_ID) ON DELETE CASCADE
);
