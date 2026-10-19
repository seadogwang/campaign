package com.company.campaign.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;

/**
 * Durable Campaign Plan store row.
 *
 * <p>Immutable by construction: rows are inserted once and never updated. plan_id is the primary
 * key (all 64 SHA-256 digest hex characters). canonical_material_bytes stores the exact canonical
 * UTF-8 material from which the genuine CampaignPlan can be reconstructed.
 */
@TableName("campaign_plan")
public class CampaignPlanEntity {

    @TableId
    private String planId;
    private String planHash;
    private String planFormatVersion;
    private String compilerVersion;
    private Instant compiledAt;
    private Instant expiresAt;
    private String tenantId;
    private String programId;
    private String userId;
    private String campaignId;
    private Long baseCampaignRevision;
    private String baseDraftId;
    private Long baseDraftRevision;
    private String forkBaseIdentity;
    private String proposalHash;
    private String manifestHash;
    private String catalogVersion;
    private String catalogHash;
    private String policyVersion;
    private String policyHash;
    private String manifestJson;
    private byte[] canonicalMaterialBytes;
    private Instant createdAt;

    public String getPlanId() {
        return planId;
    }

    public void setPlanId(String planId) {
        this.planId = planId;
    }

    public String getPlanHash() {
        return planHash;
    }

    public void setPlanHash(String planHash) {
        this.planHash = planHash;
    }

    public String getPlanFormatVersion() {
        return planFormatVersion;
    }

    public void setPlanFormatVersion(String planFormatVersion) {
        this.planFormatVersion = planFormatVersion;
    }

    public String getCompilerVersion() {
        return compilerVersion;
    }

    public void setCompilerVersion(String compilerVersion) {
        this.compilerVersion = compilerVersion;
    }

    public Instant getCompiledAt() {
        return compiledAt;
    }

    public void setCompiledAt(Instant compiledAt) {
        this.compiledAt = compiledAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }

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

    public String getCampaignId() {
        return campaignId;
    }

    public void setCampaignId(String campaignId) {
        this.campaignId = campaignId;
    }

    public Long getBaseCampaignRevision() {
        return baseCampaignRevision;
    }

    public void setBaseCampaignRevision(Long baseCampaignRevision) {
        this.baseCampaignRevision = baseCampaignRevision;
    }

    public String getBaseDraftId() {
        return baseDraftId;
    }

    public void setBaseDraftId(String baseDraftId) {
        this.baseDraftId = baseDraftId;
    }

    public Long getBaseDraftRevision() {
        return baseDraftRevision;
    }

    public void setBaseDraftRevision(Long baseDraftRevision) {
        this.baseDraftRevision = baseDraftRevision;
    }

    public String getForkBaseIdentity() {
        return forkBaseIdentity;
    }

    public void setForkBaseIdentity(String forkBaseIdentity) {
        this.forkBaseIdentity = forkBaseIdentity;
    }

    public String getProposalHash() {
        return proposalHash;
    }

    public void setProposalHash(String proposalHash) {
        this.proposalHash = proposalHash;
    }

    public String getManifestHash() {
        return manifestHash;
    }

    public void setManifestHash(String manifestHash) {
        this.manifestHash = manifestHash;
    }

    public String getCatalogVersion() {
        return catalogVersion;
    }

    public void setCatalogVersion(String catalogVersion) {
        this.catalogVersion = catalogVersion;
    }

    public String getCatalogHash() {
        return catalogHash;
    }

    public void setCatalogHash(String catalogHash) {
        this.catalogHash = catalogHash;
    }

    public String getPolicyVersion() {
        return policyVersion;
    }

    public void setPolicyVersion(String policyVersion) {
        this.policyVersion = policyVersion;
    }

    public String getPolicyHash() {
        return policyHash;
    }

    public void setPolicyHash(String policyHash) {
        this.policyHash = policyHash;
    }

    public String getManifestJson() {
        return manifestJson;
    }

    public void setManifestJson(String manifestJson) {
        this.manifestJson = manifestJson;
    }

    public byte[] getCanonicalMaterialBytes() {
        return canonicalMaterialBytes;
    }

    public void setCanonicalMaterialBytes(byte[] canonicalMaterialBytes) {
        this.canonicalMaterialBytes = canonicalMaterialBytes;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}