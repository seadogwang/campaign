-- T35A2 production durable audit ledger schema.
--
-- The schema is expand-only: it creates one ledger table and never drops or rewrites an accepted object. The
-- table is append-only by construction. The application issues exactly two statements against it — one constant
-- insert and one constant identity lookup — and never issues an UPDATE, DELETE, TRUNCATE or DDL statement, so a
-- committed audit row can never be mutated or removed by this build.
--
-- Every column is a bounded, server-owned fact. Identifiers are never stored readable: tenant, program, user,
-- DataScope, subject and execution identifiers are stored only as domain-separated sha256 fingerprints, so the
-- original value cannot be read back from a row. No raw JWT, subject, member id, recipient address, prompt,
-- model output, SQL, provider request body, credential, URL or unrestricted exception message has a column.
--
-- The unique constraint on the immutable logical event key is what makes replay decidable:
--   * an exact replay of the same logical event carries the same key and the same document_hash, so the insert
--     conflicts and is a no-op that writes nothing;
--   * the same logical event presented with changed material carries the same key and a different document_hash,
--     which the writer reports as a bounded conflict instead of appending a second row.
-- inserted_at is the database's own insertion instant, generated server-side; occurred_at is the trusted
-- occurrence instant carried by the accepted source record and checked by its own contract.

create table campaign_production_audit_ledger (
    logical_key          varchar(80)  not null,
    event_id             varchar(160) not null,
    domain               varchar(32)  not null,
    event_type           varchar(64)  not null,
    format_version       varchar(32)  not null,
    tenant_fingerprint   varchar(80),
    program_fingerprint  varchar(80),
    resource_fingerprint varchar(80),
    disposition_code     varchar(64),
    reason_code          varchar(64),
    action_code          varchar(64),
    topic_code           varchar(64),
    source_hash          varchar(80),
    source_document      text         not null,
    document_hash        varchar(80)  not null,
    occurred_at          timestamptz  not null,
    inserted_at          timestamptz  not null default now(),
    constraint campaign_production_audit_ledger_key
        unique (logical_key),
    constraint campaign_production_audit_ledger_domain_check
        check (domain in ('SECURITY', 'CONVERSATION', 'LIFECYCLE', 'RUNTIME', 'ACTION')),
    constraint campaign_production_audit_ledger_source_document_check
        check (char_length(source_document) between 1 and 8192)
);

-- The one read path a human reviewer needs: one domain's rows in trusted occurrence order.
create index campaign_production_audit_ledger_occurrence
    on campaign_production_audit_ledger (domain, occurred_at, logical_key);