-- Extend the business resource type set with PROMOTION.
--
-- The closed catalog has always been designed to host additional resource kinds as the Campaign
-- builder adds capabilities.  PROMOTION is a deterministic resource (coupon, reward, etc.) that
-- the model may decide is REQUIRED or NOT_REQUIRED; when REQUIRED, it must bind to a row that is
-- already registered in this table.
--
-- PostgreSQL does not support ALTER CHECK so the constraint is dropped and recreated with the
-- expanded value set.  This is safe: the new set is a strict superset, so every row that passed
-- the old constraint still passes the new one.  The statement pattern mirrors the existing V102
-- constraint precisely so that the unified migration scan assertion stays deterministic.

ALTER TABLE campaign_business_resource
    DROP CONSTRAINT IF EXISTS ck_campaign_business_resource_type;

ALTER TABLE campaign_business_resource
    ADD CONSTRAINT ck_campaign_business_resource_type
        CHECK (resource_type IN ('COMMUNICATION_CHANNEL', 'TRIGGER_EVENT', 'PROMOTION'));