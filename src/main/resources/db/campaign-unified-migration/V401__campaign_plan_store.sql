-- Campaign Plan durable store and compile idempotency record (unified business migration set).
--
-- The schema is expand-only: it creates two tables and never drops or rewrites an accepted object.
-- The tables are immutable-by-construction: plan rows are inserted once and never updated; idempotency
-- rows are inserted once per key + proposal hash combination.
--
-- Every CREATE carries an idempotence guard so this unified migration can be applied on top of a
-- boundary that already carries the accepted objects.
--
-- plan_id is the deterministic SHA-256-derived identifier (all 64 digest hex characters).
-- canonical_material_bytes stores enough canonical JSON material to reconstruct the exact immutable
-- CampaignPlan required by CampaignPlanStore.find.
-- The idempotency table enforces durable conflict: one idempotency key may only ever be bound to
-- one proposal hash; a different proposal under the same key is a durable conflict.

create table if not exists campaign_plan (
    plan_id                  varchar(64)   not null,
    plan_hash                varchar(80)   not null,
    plan_format_version      varchar(32)   not null,
    compiler_version         varchar(32)   not null,
    compiled_at              timestamptz   not null,
    expires_at               timestamptz   not null,
    tenant_id                varchar(64)   not null,
    program_id               varchar(64)   not null,
    user_id                  varchar(128)  not null,
    campaign_id              varchar(64),
    base_campaign_revision   bigint,
    base_draft_id            varchar(64),
    base_draft_revision      bigint,
    fork_base_identity       varchar(256),
    proposal_hash            varchar(80)   not null,
    manifest_hash            varchar(80)   not null,
    catalog_version          varchar(32)   not null,
    catalog_hash             varchar(80)   not null,
    policy_version           varchar(32)   not null,
    policy_hash              varchar(80)   not null,
    manifest_json            text          not null,
    canonical_material_bytes bytea         not null,
    created_at               timestamptz   not null default now(),
    constraint campaign_plan_pk
        primary key (plan_id),
    constraint campaign_plan_plan_id_length_check
        check (char_length(plan_id) = 64),
    constraint campaign_plan_canonical_material_bytes_not_empty
        check (octet_length(canonical_material_bytes) > 0)
);

-- Lookup by plan_id is the host-only retrieval path.
create unique index if not exists campaign_plan_plan_id_idx
    on campaign_plan (plan_id);

create table if not exists campaign_plan_compile_idempotency (
    idempotency_key    varchar(256)  not null,
    proposal_hash      varchar(80)   not null,
    plan_id            varchar(64)   not null,
    created_at         timestamptz   not null default now(),
    constraint campaign_plan_compile_idempotency_key_hash_uniq
        unique (idempotency_key, proposal_hash),
    constraint campaign_plan_compile_idempotency_key_uniq
        unique (idempotency_key),
    constraint campaign_plan_compile_idempotency_key_length_check
        check (char_length(idempotency_key) between 1 and 256),
    constraint campaign_plan_compile_idempotency_plan_id_length_check
        check (char_length(plan_id) = 64)
);

-- The unique constraint on idempotency_key alone makes conflict detection durable:
-- an INSERT that reuses the same key with a different proposal_hash fails at the database level.