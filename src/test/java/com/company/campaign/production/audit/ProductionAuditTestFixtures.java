package com.company.campaign.production.audit;

import com.company.campaign.action.ActionAuditRecord;
import com.company.campaign.conversation.CampaignConversationAuditRecord;
import com.company.campaign.lifecycle.LifecycleAction;
import com.company.campaign.lifecycle.LifecycleAuditEvent;
import com.company.campaign.lifecycle.LifecycleDisposition;
import com.company.campaign.runtime.RuntimeAuditRecord;
import com.company.campaign.security.audit.AuditResourceFingerprint;
import com.company.campaign.security.audit.SecurityAction;
import com.company.campaign.security.audit.SecurityAuditEvent;
import com.company.campaign.security.audit.SecurityDisposition;
import com.company.campaign.security.authorization.ResourceKind;
import com.company.campaign.shared.canonical.ContentHash;
import com.company.campaign.testsupport.MyBatisTestSupport;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

/**
 * Deterministic fixtures for the durable audit tests.
 *
 * <p>Every value is a literal: no ambient clock, no randomness and no identifier that could be mistaken for a
 * production value. The schema and the connection target are plainly non-production placeholders, and nothing
 * here is ever committed to a property file.
 */
final class ProductionAuditTestFixtures {

    static final Instant OCCURRED_AT = Instant.parse("2026-10-11T00:00:00Z");

    static final String LEDGER_URL = "jdbc:postgresql://127.0.0.1:1/campaign_audit_placeholder";

    static final String LEDGER_USERNAME = "audit-placeholder-user";

    static final String LEDGER_PASSWORD = "audit-placeholder-secret";

    static final String LEDGER_SCHEMA = "campaign_production_audit_t35a2";

    static final String TENANT = "tenant-1";

    static final String PROGRAM = "program-1";

    static final String USER = "user-1";

    static final String DATA_SCOPE = "scope-1";

    private ProductionAuditTestFixtures() {
    }

    // ------------------------------------------------------------------ configuration

    static ProductionAuditProperties.Ledger ledger() {
        ProductionAuditProperties.Ledger ledger = new ProductionAuditProperties.Ledger();
        ledger.setUrl(LEDGER_URL);
        ledger.setUsername(LEDGER_USERNAME);
        ledger.setPassword(LEDGER_PASSWORD);
        ledger.setSchema(LEDGER_SCHEMA);
        ledger.setConnectionTimeoutMs(250);
        ledger.setValidationTimeoutMs(250);
        ledger.validate();
        return ledger;
    }

    static ProductionAuditProperties properties() {
        ProductionAuditProperties properties = new ProductionAuditProperties();
        properties.setEnabled(true);
        ProductionAuditProperties.Ledger ledger = properties.getLedger();
        ledger.setUrl(LEDGER_URL);
        ledger.setUsername(LEDGER_USERNAME);
        ledger.setPassword(LEDGER_PASSWORD);
        ledger.setSchema(LEDGER_SCHEMA);
        ledger.setConnectionTimeoutMs(250);
        ledger.setValidationTimeoutMs(250);
        properties.validate();
        return properties;
    }

    static MyBatisPlusProductionAuditLedger ledgerOver(ScriptedProductionAuditJdbcBoundary boundary) {
        return new MyBatisPlusProductionAuditLedger(
                MyBatisTestSupport.sessionFactory(boundary.dataSource()), ledger());
    }

    // ------------------------------------------------------------------ hashes

    static ContentHash hash(String material) {
        return ContentHash.sha256(material.getBytes(StandardCharsets.UTF_8));
    }

    static AuditResourceFingerprint resourceFingerprint() {
        return new AuditResourceFingerprint(ResourceKind.SEGMENT, hash("resource-material"));
    }

    // ------------------------------------------------------------------ source records

    static SecurityAuditEvent securityEvent() {
        return securityEvent(SecurityDisposition.ALLOWED, "CAMPAIGN_RESOURCE_ALLOWED");
    }

    /** The same recorded decision with different outcome material, so its logical key is unchanged. */
    static SecurityAuditEvent securityEvent(SecurityDisposition disposition, String reasonCode) {
        return new SecurityAuditEvent(
                SecurityAction.RESOURCE_AUTHORIZATION,
                disposition,
                reasonCode,
                OCCURRED_AT,
                1234L,
                TENANT,
                PROGRAM,
                USER,
                DATA_SCOPE,
                disposition == SecurityDisposition.NOT_FOUND ? null : resourceFingerprint(),
                hash("redacted-projection"));
    }

    static CampaignConversationAuditRecord conversationRecord() {
        return conversationRecord("TURN_ACCEPTED", 3L);
    }

    static CampaignConversationAuditRecord conversationRecord(String code, long revision) {
        return new CampaignConversationAuditRecord(
                hash("conversation-key"),
                TENANT,
                PROGRAM,
                USER,
                "TURN",
                "ROUTING",
                code != null && code.startsWith("TURN_ACCEPTED"),
                code,
                revision,
                OCCURRED_AT);
    }

    static LifecycleAuditEvent lifecycleEvent() {
        return lifecycleEvent(LifecycleDisposition.APPLIED, "CAMPAIGN_APPLY_ACCEPTED");
    }

    static LifecycleAuditEvent lifecycleEvent(LifecycleDisposition disposition, String reasonCode) {
        return new LifecycleAuditEvent(
                LifecycleAction.APPLY,
                disposition,
                reasonCode,
                OCCURRED_AT,
                TENANT,
                PROGRAM,
                USER,
                DATA_SCOPE,
                disposition == LifecycleDisposition.NOT_FOUND ? null : resourceFingerprint(),
                hash("plan-material"),
                hash("content-material"));
    }

    /** A second lifecycle command whose identity material differs only in the content hash it addressed. */
    static LifecycleAuditEvent lifecycleEvent(String reasonCode, String contentMaterial) {
        return new LifecycleAuditEvent(
                LifecycleAction.APPLY,
                LifecycleDisposition.APPLIED,
                reasonCode,
                OCCURRED_AT,
                TENANT,
                PROGRAM,
                USER,
                DATA_SCOPE,
                resourceFingerprint(),
                hash("plan-material"),
                hash(contentMaterial));
    }

    static RuntimeAuditRecord runtimeRecord() {
        return runtimeRecord("record-1", "EXECUTION_CREATED", 0L, 1L);
    }

    static RuntimeAuditRecord runtimeRecord(String recordId, String reasonCode, Long before, Long after) {
        return RuntimeAuditRecord.of(
                recordId, TENANT, reasonCode, "EXECUTION", "subject-1", "execution-1", before, after, OCCURRED_AT);
    }

    static ActionAuditRecord actionRecord() {
        return actionRecord("record-1", "ACTION_RESERVED", 0L, 1L);
    }

    static ActionAuditRecord actionRecord(String recordId, String reasonCode, Long before, Long after) {
        return ActionAuditRecord.of(
                recordId, TENANT, reasonCode, "ACTION", "subject-1", before, after, OCCURRED_AT);
    }

    // ------------------------------------------------------------------ entries

    static ProductionAuditEntry securityEntry() {
        return ProductionSecurityAuditSink.entry(securityEvent());
    }

    static ProductionAuditEntry conversationEntry() {
        return ProductionConversationAuditSink.entry(conversationRecord());
    }

    static ProductionAuditEntry lifecycleEntry() {
        return ProductionLifecycleAuditSink.entry(lifecycleEvent());
    }

    static ProductionAuditEntry runtimeEntry() {
        return ProductionRuntimeAuditSink.entry(runtimeRecord());
    }

    static ProductionAuditEntry actionEntry() {
        return ProductionActionAuditSink.entry(actionRecord());
    }

    /** One entry of every accepted audit port, in domain order. */
    static java.util.List<ProductionAuditEntry> allEntries() {
        return java.util.List.of(securityEntry(), conversationEntry(), lifecycleEntry(), runtimeEntry(), actionEntry());
    }
}
