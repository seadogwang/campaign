package com.company.campaign.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;

/**
 * Create-idempotency ledger row for a Campaign conversation reservation.
 */
@TableName("campaign_conversation_create")
public class CampaignConversationCreateEntity {

    private String tenantId;
    private String programId;
    private String userId;
    private String createKey;
    private String fingerprint;
    private String conversationId;
    private String runId;
    private Instant createdAt;
    private Instant expiresAt;

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public String getProgramId() {
        return programId;
    }

    public void setProgramId(String programId) {
        this.programId = programId;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getCreateKey() {
        return createKey;
    }

    public void setCreateKey(String createKey) {
        this.createKey = createKey;
    }

    public String getFingerprint() {
        return fingerprint;
    }

    public void setFingerprint(String fingerprint) {
        this.fingerprint = fingerprint;
    }

    public String getConversationId() {
        return conversationId;
    }

    public void setConversationId(String conversationId) {
        this.conversationId = conversationId;
    }

    public String getRunId() {
        return runId;
    }

    public void setRunId(String runId) {
        this.runId = runId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }
}