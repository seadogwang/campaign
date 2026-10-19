package com.company.campaign.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;

/**
 * Bounded idempotency outcome row for one turn of a Campaign conversation.
 */
@TableName("campaign_conversation_outcome")
public class CampaignConversationOutcomeEntity {

    private String tenantId;
    private String programId;
    private String userId;
    private String conversationId;
    private String runId;
    private String idempotencyKey;
    private String fingerprint;
    private String resultHash;
    private byte[] resultBytes;
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

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public void setIdempotencyKey(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
    }

    public String getFingerprint() {
        return fingerprint;
    }

    public void setFingerprint(String fingerprint) {
        this.fingerprint = fingerprint;
    }

    public String getResultHash() {
        return resultHash;
    }

    public void setResultHash(String resultHash) {
        this.resultHash = resultHash;
    }

    public byte[] getResultBytes() {
        return resultBytes;
    }

    public void setResultBytes(byte[] resultBytes) {
        this.resultBytes = resultBytes;
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