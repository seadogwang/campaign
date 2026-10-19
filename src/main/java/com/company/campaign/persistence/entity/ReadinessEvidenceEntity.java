package com.company.campaign.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;

/**
 * Append-only readiness evidence row.
 */
@TableName("readiness_evidence")
public class ReadinessEvidenceEntity {

    private String tenantId;
    private String programId;
    private String environment;
    private String evidenceId;
    private String formatVersion;
    private String requirementCode;
    private String status;
    private String origin;
    private String subject;
    private String versionRef;
    private String evidenceHash;
    private String evidenceReference;
    private Boolean productionGateEvidence;
    private String reasonCode;
    private Instant observedAt;
    private Instant expiresAt;
    private String identityHash;
    private String materialHash;
    private Long committedRevision;

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

    public String getEnvironment() {
        return environment;
    }

    public void setEnvironment(String environment) {
        this.environment = environment;
    }

    public String getEvidenceId() {
        return evidenceId;
    }

    public void setEvidenceId(String evidenceId) {
        this.evidenceId = evidenceId;
    }

    public String getFormatVersion() {
        return formatVersion;
    }

    public void setFormatVersion(String formatVersion) {
        this.formatVersion = formatVersion;
    }

    public String getRequirementCode() {
        return requirementCode;
    }

    public void setRequirementCode(String requirementCode) {
        this.requirementCode = requirementCode;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getOrigin() {
        return origin;
    }

    public void setOrigin(String origin) {
        this.origin = origin;
    }

    public String getSubject() {
        return subject;
    }

    public void setSubject(String subject) {
        this.subject = subject;
    }

    public String getVersionRef() {
        return versionRef;
    }

    public void setVersionRef(String versionRef) {
        this.versionRef = versionRef;
    }

    public String getEvidenceHash() {
        return evidenceHash;
    }

    public void setEvidenceHash(String evidenceHash) {
        this.evidenceHash = evidenceHash;
    }

    public String getEvidenceReference() {
        return evidenceReference;
    }

    public void setEvidenceReference(String evidenceReference) {
        this.evidenceReference = evidenceReference;
    }

    public Boolean getProductionGateEvidence() {
        return productionGateEvidence;
    }

    public void setProductionGateEvidence(Boolean productionGateEvidence) {
        this.productionGateEvidence = productionGateEvidence;
    }

    public String getReasonCode() {
        return reasonCode;
    }

    public void setReasonCode(String reasonCode) {
        this.reasonCode = reasonCode;
    }

    public Instant getObservedAt() {
        return observedAt;
    }

    public void setObservedAt(Instant observedAt) {
        this.observedAt = observedAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }

    public String getIdentityHash() {
        return identityHash;
    }

    public void setIdentityHash(String identityHash) {
        this.identityHash = identityHash;
    }

    public String getMaterialHash() {
        return materialHash;
    }

    public void setMaterialHash(String materialHash) {
        this.materialHash = materialHash;
    }

    public Long getCommittedRevision() {
        return committedRevision;
    }

    public void setCommittedRevision(Long committedRevision) {
        this.committedRevision = committedRevision;
    }
}