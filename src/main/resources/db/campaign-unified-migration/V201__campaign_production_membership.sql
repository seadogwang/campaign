-- T35A1 production membership schema (unified business migration set).
--
-- The schema is expand-only: it creates the two server-owned tables the production membership adapter reads and
-- never drops or rewrites an accepted object. Every column is a trusted, server-owned fact; none of them is ever
-- taken from a token role or scope. Permissions are normalized rows so a membership cannot carry an authority the
-- closed CampaignPermission vocabulary does not name.
--
-- Every CREATE carries an idempotence guard so this unified migration can be applied on top of a boundary that
-- already carries the accepted objects from an earlier dedicated membership run.
--
-- The partial unique index prevents duplicate active authority for the same issuer, subject and selection key:
-- a second active row for the same selection key cannot be inserted, so an ambiguous match is a data defect the
-- database itself refuses rather than a lookup that silently picks one.

create table if not exists campaign_membership (
    membership_id        varchar(128) primary key,
    issuer               varchar(255) not null,
    subject              varchar(255) not null,
    user_id              varchar(128) not null,
    tenant_id            varchar(128) not null,
    program_id           varchar(128) not null,
    data_scope_id        varchar(128) not null,
    selection_key        varchar(255),
    status               varchar(16)  not null,
    valid_from           timestamptz  not null,
    valid_until          timestamptz,
    metadata_snapshot_id varchar(128) not null,
    locale               varchar(35)  not null,
    timezone             varchar(64)  not null,
    reporting_currency   char(3)      not null,
    as_of                timestamptz  not null,
    data_watermark       timestamptz  not null,
    revision             bigint       not null default 0,
    created_at           timestamptz  not null default now(),
    constraint campaign_membership_status_check check (status in ('ACTIVE', 'INACTIVE')),
    constraint campaign_membership_validity_check check (valid_until is null or valid_until >= valid_from),
    constraint campaign_membership_watermark_check check (data_watermark <= as_of)
);

create table if not exists campaign_membership_permission (
    membership_id varchar(128) not null references campaign_membership (membership_id) on delete restrict,
    permission    varchar(64)  not null,
    primary key (membership_id, permission)
);

create unique index if not exists campaign_membership_active_owner_key
    on campaign_membership (issuer, subject, coalesce(selection_key, ''))
    where status = 'ACTIVE';

create index if not exists campaign_membership_owner_lookup
    on campaign_membership (issuer, subject, selection_key);