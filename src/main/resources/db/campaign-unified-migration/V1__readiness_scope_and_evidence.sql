-- T25 readiness slice, migration 1 of 3 (unified business migration set).
--
-- Expand-only and safe for an empty database: this file only adds a scope revision row and an append-only
-- evidence log. It drops, truncates and rewrites nothing, so it can be applied to a database that already
-- holds accepted data without changing any existing row.
--
-- The schema stores bounded scalar fields only. An evidence record is a set of bounded machine values plus
-- canonical hashes, never a serialized Java object, so a stored row cannot carry an arbitrary payload.

CREATE TABLE IF NOT EXISTS readiness_scope (
    tenant_id   VARCHAR(128) NOT NULL,
    program_id  VARCHAR(128) NOT NULL,
    environment VARCHAR(32)  NOT NULL,
    revision    BIGINT       NOT NULL,
    updated_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_readiness_scope PRIMARY KEY (tenant_id, program_id, environment),
    CONSTRAINT ck_readiness_scope_environment
        CHECK (environment IN ('LOCAL', 'CI', 'STAGING', 'PRODUCTION')),
    CONSTRAINT ck_readiness_scope_revision CHECK (revision >= 0)
);

CREATE TABLE IF NOT EXISTS readiness_evidence (
    tenant_id                VARCHAR(128) NOT NULL,
    program_id               VARCHAR(128) NOT NULL,
    environment              VARCHAR(32)  NOT NULL,
    evidence_id              VARCHAR(128) NOT NULL,
    format_version           VARCHAR(64)  NOT NULL,
    requirement_code         VARCHAR(64)  NOT NULL,
    status                   VARCHAR(32)  NOT NULL,
    origin                   VARCHAR(32)  NOT NULL,
    subject                  VARCHAR(128) NOT NULL,
    version_ref              VARCHAR(128) NOT NULL,
    evidence_hash            VARCHAR(80)  NOT NULL,
    evidence_reference       VARCHAR(256) NOT NULL,
    production_gate_evidence BOOLEAN      NOT NULL,
    reason_code              VARCHAR(64),
    observed_at              TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at               TIMESTAMP WITH TIME ZONE,
    identity_hash            VARCHAR(80)  NOT NULL,
    material_hash            VARCHAR(80)  NOT NULL,
    committed_revision       BIGINT       NOT NULL,
    CONSTRAINT pk_readiness_evidence
        PRIMARY KEY (tenant_id, program_id, environment, evidence_id),
    CONSTRAINT uq_readiness_evidence_identity
        UNIQUE (tenant_id, program_id, environment, identity_hash),
    CONSTRAINT ck_readiness_evidence_status
        CHECK (status IN ('SATISFIED', 'BLOCKED', 'INVALID')),
    CONSTRAINT ck_readiness_evidence_origin
        CHECK (origin IN ('PRODUCTION', 'REAL_LOCAL', 'SIMULATED', 'FIXTURE', 'SYNTHETIC',
                          'DEVELOPMENT_ONLY', 'EVALUATION_ASSET')),
    CONSTRAINT ck_readiness_evidence_reason
        CHECK ((status = 'SATISFIED' AND reason_code IS NULL)
            OR (status <> 'SATISFIED' AND reason_code IS NOT NULL)),
    CONSTRAINT ck_readiness_evidence_expiry
        CHECK (expires_at IS NULL OR expires_at > observed_at),
    CONSTRAINT ck_readiness_evidence_revision CHECK (committed_revision >= 0)
);

CREATE INDEX IF NOT EXISTS ix_readiness_evidence_requirement
    ON readiness_evidence (tenant_id, program_id, environment, requirement_code);