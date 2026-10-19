package com.company.campaign.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;

/**
 * Append-only production audit ledger row.
 */
@TableName("campaign_production_audit_ledger")
public class CampaignProductionAuditLedgerEntity {

    private String logicalKey;
    private String eventId;
    private String domain;
    private String eventType;
    private String formatVersion;
    private String tenantFingerprint;
    private String programFingerprint;
    private String resourceFingerprint;
    private String dispositionCode;
    private String reasonCode;
    private String actionCode;
    private String topicCode;
    private String sourceHash;
    private String sourceDocument;
    private String documentHash;
    private Instant occurredAt;
    private Instant insertedAt;

    public String getLogicalKey() {
        return logicalKey;
    }

    public void setLogicalKey(String logicalKey) {
        this.logicalKey = logicalKey;
    }

    public String getEventId() {
        return eventId;
    }

    public void setEventId(String eventId) {
        this.eventId = eventId;
    }

    public String getDomain() {
        return domain;
    }

    public void setDomain(String domain) {
        this.domain = domain;
    }

    public String getEventType() {
        return eventType;
    }

    public void setEventType(String eventType) {
        this.eventType = eventType;
    }

    public String getFormatVersion() {
        return formatVersion;
    }

    public void setFormatVersion(String formatVersion) {
        this.formatVersion = formatVersion;
    }

    public String getTenantFingerprint() {
        return tenantFingerprint;
    }

    public void setTenantFingerprint(String tenantFingerprint) {
        this.tenantFingerprint = tenantFingerprint;
    }

    public String getProgramFingerprint() {
        return programFingerprint;
    }

    public void setProgramFingerprint(String programFingerprint) {
        this.programFingerprint = programFingerprint;
    }

    public String getResourceFingerprint() {
        return resourceFingerprint;
    }

    public void setResourceFingerprint(String resourceFingerprint) {
        this.resourceFingerprint = resourceFingerprint;
    }

    public String getDispositionCode() {
        return dispositionCode;
    }

    public void setDispositionCode(String dispositionCode) {
        this.dispositionCode = dispositionCode;
    }

    public String getReasonCode() {
        return reasonCode;
    }

    public void setReasonCode(String reasonCode) {
        this.reasonCode = reasonCode;
    }

    public String getActionCode() {
        return actionCode;
    }

    public void setActionCode(String actionCode) {
        this.actionCode = actionCode;
    }

    public String getTopicCode() {
        return topicCode;
    }

    public void setTopicCode(String topicCode) {
        this.topicCode = topicCode;
    }

    public String getSourceHash() {
        return sourceHash;
    }

    public void setSourceHash(String sourceHash) {
        this.sourceHash = sourceHash;
    }

    public String getSourceDocument() {
        return sourceDocument;
    }

    public void setSourceDocument(String sourceDocument) {
        this.sourceDocument = sourceDocument;
    }

    public String getDocumentHash() {
        return documentHash;
    }

    public void setDocumentHash(String documentHash) {
        this.documentHash = documentHash;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public void setOccurredAt(Instant occurredAt) {
        this.occurredAt = occurredAt;
    }

    public Instant getInsertedAt() {
        return insertedAt;
    }

    public void setInsertedAt(Instant insertedAt) {
        this.insertedAt = insertedAt;
    }
}