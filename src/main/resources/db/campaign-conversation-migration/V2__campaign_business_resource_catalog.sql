-- Host-owned Campaign business resources exposed through the read-only catalog Tool.
--
-- A model or user utterance never makes a channel or trigger event executable. A resource is selectable only
-- when this table says it is enabled and its backing integration is available. Operations may register rows
-- independently for each tenant/program without changing prompts or application code.
CREATE TABLE IF NOT EXISTS campaign_business_resource (
    tenant_id          VARCHAR(128) NOT NULL,
    program_id         VARCHAR(128) NOT NULL,
    resource_type      VARCHAR(32)  NOT NULL,
    resource_code      VARCHAR(128) NOT NULL,
    display_name       VARCHAR(128) NOT NULL,
    aliases            TEXT[]       NOT NULL DEFAULT ARRAY[]::TEXT[],
    enabled            BOOLEAN      NOT NULL DEFAULT FALSE,
    runtime_available  BOOLEAN      NOT NULL DEFAULT FALSE,
    integration_key    VARCHAR(128),
    sort_order         INTEGER      NOT NULL DEFAULT 0,
    updated_at         TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_campaign_business_resource
        PRIMARY KEY (tenant_id, program_id, resource_type, resource_code),
    CONSTRAINT ck_campaign_business_resource_type
        CHECK (resource_type IN ('COMMUNICATION_CHANNEL', 'TRIGGER_EVENT')),
    CONSTRAINT ck_campaign_business_resource_code
        CHECK (resource_code ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$'),
    CONSTRAINT ck_campaign_business_resource_display_name
        CHECK (char_length(display_name) BETWEEN 1 AND 128),
    CONSTRAINT ck_campaign_business_resource_integration
        CHECK ((runtime_available = FALSE) OR (integration_key IS NOT NULL)),
    CONSTRAINT ck_campaign_business_resource_sort_order
        CHECK (sort_order BETWEEN 0 AND 1000000)
);

CREATE INDEX IF NOT EXISTS ix_campaign_business_resource_discovery
    ON campaign_business_resource (tenant_id, program_id, resource_type, enabled, runtime_available, sort_order);
