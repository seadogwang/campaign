-- T25 readiness slice, migration 2 of 3.
--
-- Expand-only: it adds the append-only assessment log without touching anything migration 1 created. The
-- accepted-evidence and blocker collections are stored as bounded canonical JSON documents, which is the only
-- place in this schema where a document is needed; every other column is a bounded scalar. Nothing here
-- serializes an arbitrary Java object.

CREATE TABLE IF NOT EXISTS readiness_assessment (
    tenant_id              VARCHAR(128) NOT NULL,
    program_id             VARCHAR(128) NOT NULL,
    environment            VARCHAR(32)  NOT NULL,
    assessment_id          VARCHAR(128) NOT NULL,
    format_version         VARCHAR(64)  NOT NULL,
    level                  VARCHAR(32)  NOT NULL,
    status                 VARCHAR(32)  NOT NULL,
    evaluated_at           TIMESTAMP WITH TIME ZONE NOT NULL,
    accepted_evidence_json TEXT         NOT NULL,
    blockers_json          TEXT         NOT NULL,
    assessment_hash        VARCHAR(80)  NOT NULL,
    committed_revision     BIGINT       NOT NULL,
    CONSTRAINT pk_readiness_assessment
        PRIMARY KEY (tenant_id, program_id, environment, assessment_id),
    CONSTRAINT uq_readiness_assessment_revision
        UNIQUE (tenant_id, program_id, environment, level, committed_revision),
    CONSTRAINT ck_readiness_assessment_level
        CHECK (level IN ('LOCAL_READY', 'AI_READY', 'LIVE_READY')),
    CONSTRAINT ck_readiness_assessment_status
        CHECK (status IN ('READY', 'BLOCKED', 'INVALID')),
    CONSTRAINT ck_readiness_assessment_revision CHECK (committed_revision >= 0)
);

CREATE INDEX IF NOT EXISTS ix_readiness_assessment_level
    ON readiness_assessment (tenant_id, program_id, environment, level, committed_revision);