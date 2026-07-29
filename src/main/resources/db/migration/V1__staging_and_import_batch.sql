-- SVC-AGG/IMPORT foundation: import_batch tracks one upload lifecycle;
-- staging_incident holds the raw, unvalidated CSV rows for that batch.
--
-- staging_incident's column list mirrors the public GTD Codebook (the
-- release associated with globalterrorismdb_0718dist.csv, per
-- docs/BUILD-PHASES.md / gtd-project-handoff.md Section 5) as best-effort,
-- reconstructed from the well-documented public schema since the actual CSV
-- file is not present in this environment. Phase 3 (Dataset Import Pipeline)
-- MUST reconcile this column list against the real CSV header row before
-- the first production import and add any missing columns via a new
-- forward-only migration (never edit this one) - see CLAUDE.md section 12.
-- Every staging column is TEXT and nullable: staging is deliberately "raw,"
-- with no type coercion or validation happening at this layer.

CREATE TABLE import_batch (
    id                          BIGSERIAL PRIMARY KEY,
    file_name                   VARCHAR(255) NOT NULL,
    file_size_bytes             BIGINT NOT NULL,
    import_mode                 VARCHAR(20) NOT NULL
                                     CHECK (import_mode IN ('APPEND', 'REPLACE')),
    status                      VARCHAR(30) NOT NULL DEFAULT 'PENDING'
                                     CHECK (status IN (
                                         'PENDING', 'STAGING', 'VALIDATING', 'NORMALIZING',
                                         'AWAITING_CONFIRMATION', 'COMPLETED', 'FAILED', 'CANCELLED'
                                     )),
    total_rows                  INTEGER,
    rows_staged                 INTEGER NOT NULL DEFAULT 0,
    rows_rejected                INTEGER NOT NULL DEFAULT 0,
    -- Nullable + FK added later in V6__admin_user_and_spring_session.sql,
    -- once admin_user exists. AWS-IMPORT-001: staging/validation is
    -- session-independent, so this may legitimately be null while running.
    initiated_by_admin_user_id  BIGINT,
    started_at                  TIMESTAMPTZ,
    completed_at                TIMESTAMPTZ,
    error_message                TEXT,
    created_at                  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE staging_incident (
    id                  BIGSERIAL PRIMARY KEY,
    import_batch_id     BIGINT NOT NULL REFERENCES import_batch (id),
    source_row_number   INTEGER NOT NULL,
    staged_at           TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- Identification / date
    eventid             TEXT,
    iyear               TEXT,
    imonth              TEXT,
    iday                TEXT,
    approxdate          TEXT,
    extended            TEXT,
    resolution          TEXT,

    -- Location
    country             TEXT,
    country_txt         TEXT,
    region              TEXT,
    region_txt          TEXT,
    provstate           TEXT,
    city                TEXT,
    latitude            TEXT,
    longitude           TEXT,
    specificity         TEXT,
    vicinity            TEXT,
    location            TEXT,
    summary             TEXT,

    -- Incident classification
    crit1               TEXT,
    crit2               TEXT,
    crit3               TEXT,
    doubtterr           TEXT,
    alternative         TEXT,
    alternative_txt     TEXT,
    multiple            TEXT,
    success             TEXT,
    suicide             TEXT,

    -- Attack type (up to 3)
    attacktype1         TEXT,
    attacktype1_txt     TEXT,
    attacktype2         TEXT,
    attacktype2_txt     TEXT,
    attacktype3         TEXT,
    attacktype3_txt     TEXT,

    -- Target/victim (up to 3)
    targtype1           TEXT,
    targtype1_txt       TEXT,
    targsubtype1        TEXT,
    targsubtype1_txt    TEXT,
    corp1               TEXT,
    target1             TEXT,
    natlty1             TEXT,
    natlty1_txt         TEXT,
    targtype2           TEXT,
    targtype2_txt       TEXT,
    targsubtype2        TEXT,
    targsubtype2_txt    TEXT,
    corp2               TEXT,
    target2             TEXT,
    natlty2             TEXT,
    natlty2_txt         TEXT,
    targtype3           TEXT,
    targtype3_txt       TEXT,
    targsubtype3        TEXT,
    targsubtype3_txt    TEXT,
    corp3               TEXT,
    target3             TEXT,
    natlty3             TEXT,
    natlty3_txt         TEXT,

    -- Perpetrator
    gname               TEXT,
    gsubname            TEXT,
    gname2              TEXT,
    gsubname2           TEXT,
    gname3              TEXT,
    gsubname3           TEXT,
    motive              TEXT,
    guncertain1         TEXT,
    guncertain2         TEXT,
    guncertain3         TEXT,
    individual          TEXT,
    nperps              TEXT,
    nperpcap            TEXT,

    -- Claim of responsibility
    claimed             TEXT,
    claimmode           TEXT,
    claimmode_txt       TEXT,
    claim2              TEXT,
    claimmode2          TEXT,
    claimmode2_txt      TEXT,
    claim3              TEXT,
    claimmode3          TEXT,
    claimmode3_txt      TEXT,
    compclaim           TEXT,

    -- Weapon (up to 4)
    weaptype1           TEXT,
    weaptype1_txt       TEXT,
    weapsubtype1        TEXT,
    weapsubtype1_txt    TEXT,
    weaptype2           TEXT,
    weaptype2_txt       TEXT,
    weapsubtype2        TEXT,
    weapsubtype2_txt    TEXT,
    weaptype3           TEXT,
    weaptype3_txt       TEXT,
    weapsubtype3        TEXT,
    weapsubtype3_txt    TEXT,
    weaptype4           TEXT,
    weaptype4_txt       TEXT,
    weapsubtype4        TEXT,
    weapsubtype4_txt    TEXT,
    weapdetail          TEXT,

    -- Casualties
    nkill               TEXT,
    nkillus             TEXT,
    nkillter            TEXT,
    nwound              TEXT,
    nwoundus            TEXT,
    nwoundte            TEXT,

    -- Property damage
    property            TEXT,
    propextent          TEXT,
    propextent_txt      TEXT,
    propvalue           TEXT,
    propcomment         TEXT,

    -- Hostages / kidnapping
    ishostkid           TEXT,
    nhostkid            TEXT,
    nhostkidus          TEXT,
    nhours              TEXT,
    ndays               TEXT,
    divert              TEXT,
    kidhijcountry       TEXT,
    ransom              TEXT,
    ransomamt           TEXT,
    ransomamtus         TEXT,
    ransompaid          TEXT,
    ransompaidus        TEXT,
    ransomnote          TEXT,
    hostkidoutcome      TEXT,
    hostkidoutcome_txt  TEXT,
    nreleased           TEXT,

    -- Sources / metadata
    addnotes            TEXT,
    scite1              TEXT,
    scite2              TEXT,
    scite3              TEXT,
    dbsource            TEXT,
    int_log             TEXT,
    int_ideo            TEXT,
    int_misc            TEXT,
    int_any             TEXT,
    related             TEXT
);

CREATE INDEX ix_staging_incident_import_batch_id ON staging_incident (import_batch_id);
CREATE INDEX ix_staging_incident_eventid ON staging_incident (eventid);
