-- Development-only Campaign business-resource catalog fixture.
--
-- This is the seeding path for the opt-in development host's `campaign_business_resource` table, the table that
-- `JdbcCampaignBusinessResourceTool` reads for the fixed development tenant/program. It is deliberately NOT a
-- Flyway migration: it lives outside `classpath:db/campaign-conversation-migration`, so no environment's schema
-- is ever migrated with development fixture rows. An operator applies it explicitly with the development store's
-- own credentials, for example:
--
--   psql "$CAMPAIGN_AGENT_DEVELOPMENT_STORE_URL" -v ON_ERROR_STOP=1 \
--        -c 'SET search_path TO campaign_conversation_dev' \
--        -f src/main/resources/db/campaign-conversation-dev-seed/campaign-business-resources.sql
--
-- The statement is idempotent and re-runnable: every row is upserted on its primary key, so applying it to an
-- already-seeded schema converges the fixture instead of failing.
--
-- The default development catalog exposes three selectable communication channels (SMS, EMAIL, WECHAT), four
-- selectable trigger events (MEMBER_REGISTERED, POINTS_ISSUED, MEMBER_TIER_UPGRADED, MEMBER_TIER_DOWNGRADED),
-- and one selectable promotion (COUPON). WHATSAPP is registered but fully disabled so the catalog can recognise
-- a user utterance that names it and return a CAMPAIGN_AGENT_CHANNEL_UNSUPPORTED clarification instead of
-- silently substituting a different channel. POINTS_EXPIRING is also disabled — it was part of an earlier seed
-- revision and is converged here so that re-running this script against a previously-seeded schema disables any
-- leftover selectable row.

INSERT INTO campaign_business_resource
    (tenant_id, program_id, resource_type, resource_code, display_name, aliases,
     enabled, runtime_available, integration_key, sort_order)
VALUES
    ('tenant-dev', 'program-dev', 'COMMUNICATION_CHANNEL', 'SMS', '短信（本地测试）',
     ARRAY['短信', 'sms', '短消息'], TRUE, TRUE, 'dev-sms', 10),
    ('tenant-dev', 'program-dev', 'COMMUNICATION_CHANNEL', 'EMAIL', '邮件（本地测试）',
     ARRAY['邮件', '邮箱', 'email'], TRUE, TRUE, 'dev-email', 20),
    ('tenant-dev', 'program-dev', 'COMMUNICATION_CHANNEL', 'WECHAT', '微信（本地测试）',
     ARRAY['微信', 'wechat'], TRUE, TRUE, 'dev-wechat', 30),
    ('tenant-dev', 'program-dev', 'COMMUNICATION_CHANNEL', 'WHATSAPP', 'WhatsApp（本地测试）',
     ARRAY['whatsapp'], FALSE, FALSE, NULL, 40),
    ('tenant-dev', 'program-dev', 'PROMOTION', 'COUPON', '卡券（本地测试）',
     ARRAY['卡券', '优惠券', 'coupon'], TRUE, TRUE, 'dev-coupon', 10),
    ('tenant-dev', 'program-dev', 'TRIGGER_EVENT', 'MEMBER_REGISTERED', '会员注册（本地测试）',
     ARRAY['会员注册', '会员已注册', 'member registered'], TRUE, TRUE, 'dev-member-registered', 10),
    ('tenant-dev', 'program-dev', 'TRIGGER_EVENT', 'POINTS_ISSUED', '积分发放（本地测试）',
     ARRAY['积分发放', '已发放积分', 'points issued'], TRUE, TRUE, 'dev-points-issued', 20),
    ('tenant-dev', 'program-dev', 'TRIGGER_EVENT', 'MEMBER_TIER_UPGRADED', '会员等级提升（本地测试）',
     ARRAY['会员等级提升', '等级提升'], TRUE, TRUE, 'dev-member-tier-upgraded', 30),
    ('tenant-dev', 'program-dev', 'TRIGGER_EVENT', 'MEMBER_TIER_DOWNGRADED', '会员等级降低（本地测试）',
     ARRAY['会员等级降低', '等级降低'], TRUE, TRUE, 'dev-member-tier-downgraded', 40),
    ('tenant-dev', 'program-dev', 'TRIGGER_EVENT', 'POINTS_EXPIRING', '积分即将到期（本地测试）',
     ARRAY['积分快到期', '积分即将到期'], FALSE, FALSE, NULL, 50)
ON CONFLICT (tenant_id, program_id, resource_type, resource_code) DO UPDATE SET
    display_name = EXCLUDED.display_name,
    aliases = EXCLUDED.aliases,
    enabled = EXCLUDED.enabled,
    runtime_available = EXCLUDED.runtime_available,
    integration_key = EXCLUDED.integration_key,
    sort_order = EXCLUDED.sort_order,
    updated_at = CURRENT_TIMESTAMP;