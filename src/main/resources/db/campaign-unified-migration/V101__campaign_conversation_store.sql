-- T34B durable Campaign conversation store, unified business migration set.
--
-- Expand-only and safe for an empty database: this file only adds three tables and their indexes. It drops,
-- truncates and rewrites nothing, so it can be applied to a database that already holds accepted data without
-- changing any existing row.
--
-- Every table stores bounded scalar columns plus one canonical, hash-pinned byte document per row. A stored
-- session is never a serialized Java object, and its bytes are produced only by the one hand-written codec, so a
-- row cannot carry an arbitrary payload.

-- One conversation per trusted tenant/program/user plus the bounded conversation and run identity. The row is
-- created by an explicit reservation (or by a legacy commit for a key that was never reserved); a turn never
-- creates one. `revision` is the committed session revision, and the session columns are either all NULL (no
-- turn accepted yet) or all set together.
CREATE TABLE IF NOT EXISTS campaign_conversation (
    tenant_id       VARCHAR(128) NOT NULL,
    program_id      VARCHAR(128) NOT NULL,
    user_id         VARCHAR(128) NOT NULL,
    conversation_id VARCHAR(128) NOT NULL,
    run_id          VARCHAR(128) NOT NULL,
    reserved        BOOLEAN      NOT NULL,
    revision        BIGINT       NOT NULL,
    session_format  VARCHAR(64),
    session_hash    VARCHAR(80),
    session_bytes   BYTEA,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_campaign_conversation
        PRIMARY KEY (tenant_id, program_id, user_id, conversation_id, run_id),
    CONSTRAINT ck_campaign_conversation_revision CHECK (revision >= 0),
    CONSTRAINT ck_campaign_conversation_session
        CHECK ((session_format IS NULL AND session_hash IS NULL AND session_bytes IS NULL)
            OR (session_format IS NOT NULL AND session_hash IS NOT NULL AND session_bytes IS NOT NULL)),
    CONSTRAINT ck_campaign_conversation_expiry CHECK (expires_at > created_at)
);

CREATE INDEX IF NOT EXISTS ix_campaign_conversation_expiry
    ON campaign_conversation (tenant_id, program_id, user_id, expires_at);

-- The create-idempotency ledger. A replay with the same create key and fingerprint returns the reserved
-- identity; a changed fingerprint is a bounded conflict. The row is removed once it has expired AND the
-- conversation it reserved no longer exists, so a replay can never return a reservation for a conversation that
-- is already gone.
CREATE TABLE IF NOT EXISTS campaign_conversation_create (
    tenant_id       VARCHAR(128) NOT NULL,
    program_id      VARCHAR(128) NOT NULL,
    user_id         VARCHAR(128) NOT NULL,
    create_key      VARCHAR(96)  NOT NULL,
    fingerprint     VARCHAR(80)  NOT NULL,
    conversation_id VARCHAR(128) NOT NULL,
    run_id          VARCHAR(128) NOT NULL,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_campaign_conversation_create
        PRIMARY KEY (tenant_id, program_id, user_id, create_key),
    CONSTRAINT ck_campaign_conversation_create_expiry CHECK (expires_at > created_at)
);

CREATE INDEX IF NOT EXISTS ix_campaign_conversation_create_expiry
    ON campaign_conversation_create (tenant_id, program_id, user_id, expires_at);

-- The bounded idempotency outcomes of one conversation. Each row carries the canonical fingerprint of the exact
-- request that produced the turn and the canonical, hash-pinned bytes of the bounded turn result, so a replay
-- after a restart is byte-equivalent and calls no model and no Tool. The per-conversation count is bounded by
-- the store, which evicts the oldest outcome before exceeding its declared limit.
CREATE TABLE IF NOT EXISTS campaign_conversation_outcome (
    tenant_id       VARCHAR(128) NOT NULL,
    program_id      VARCHAR(128) NOT NULL,
    user_id         VARCHAR(128) NOT NULL,
    conversation_id VARCHAR(128) NOT NULL,
    run_id          VARCHAR(128) NOT NULL,
    idempotency_key VARCHAR(96)  NOT NULL,
    fingerprint     VARCHAR(80)  NOT NULL,
    result_hash     VARCHAR(80)  NOT NULL,
    result_bytes    BYTEA        NOT NULL,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_campaign_conversation_outcome
        PRIMARY KEY (tenant_id, program_id, user_id, conversation_id, run_id, idempotency_key),
    CONSTRAINT ck_campaign_conversation_outcome_expiry CHECK (expires_at > created_at)
);

CREATE INDEX IF NOT EXISTS ix_campaign_conversation_outcome_expiry
    ON campaign_conversation_outcome (tenant_id, program_id, user_id, conversation_id, run_id, expires_at);