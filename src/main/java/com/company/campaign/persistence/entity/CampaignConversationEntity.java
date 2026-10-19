package com.company.campaign.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;

/**
 * Durable Campaign conversation store row.
 *
 * <p>Composite-key table ({@code tenant_id, program_id, user_id, conversation_id, run_id}); the primary key
 * is handled in mapper XML, not via {@code @TableId}.
 */
@TableName("campaign_conversation")
public class CampaignConversationEntity {

    private String tenantId;
    private String programId;
    private String userId;
    private String conversationId;
    private String runId;
    private Boolean reserved;
    private Long revision;
    private String sessionFormat;
    private String sessionHash;
    private byte[] sessionBytes;
    private Instant createdAt;
    private Instant updatedAt;
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

    public Boolean getReserved() {
        return reserved;
    }

    public void setReserved(Boolean reserved) {
        this.reserved = reserved;
    }

    public Long getRevision() {
        return revision;
    }

    public void setRevision(Long revision) {
        this.revision = revision;
    }

    public String getSessionFormat() {
        return sessionFormat;
    }

    public void setSessionFormat(String sessionFormat) {
        this.sessionFormat = sessionFormat;
    }

    public String getSessionHash() {
        return sessionHash;
    }

    public void setSessionHash(String sessionHash) {
        this.sessionHash = sessionHash;
    }

    public byte[] getSessionBytes() {
        return sessionBytes;
    }

    public void setSessionBytes(byte[] sessionBytes) {
        this.sessionBytes = sessionBytes;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }
}