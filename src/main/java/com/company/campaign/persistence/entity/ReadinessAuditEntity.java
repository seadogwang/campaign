package com.company.campaign.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;

/**
 * Immutable readiness audit trail row.
 */
@TableName("readiness_audit")
public class ReadinessAuditEntity {

    @TableId(type = IdType.AUTO)
    private Long auditId;
    private String tenantId;
    private String programId;
    private String environment;
    private String formatVersion;
    private String action;
    private String disposition;
    private String reasonCode;
    private Instant occurredAt;
    private String actorRef;
    private String requestCorrelation;
    private String requirementCode;
    private String statusTransition;
    private String evidenceIdentityHash;
    private String subjectFingerprint;
    private String assessmentHash;
    private Long committedRevision;

    public Long getAuditId() {
        return auditId;
    }

    public void setAuditId(Long auditId) {
        this.auditId = auditId;
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

    public String getEnvironment() {
        return environment;
    }

    public void setEnvironment(String environment) {
        this.environment = environment;
    }

    public String getFormatVersion() {
        return formatVersion;
    }

    public void setFormatVersion(String formatVersion) {
        this.formatVersion = formatVersion;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public String getDisposition() {
        return disposition;
    }

    public void setDisposition(String disposition) {
        this.disposition = disposition;
    }

    public String getReasonCode() {
        return reasonCode;
    }

    public void setReasonCode(String reasonCode) {
        this.reasonCode = reasonCode;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public void setOccurredAt(Instant occurredAt) {
        this.occurredAt = occurredAt;
    }

    public String getActorRef() {
        return actorRef;
    }

    public void setActorRef(String actorRef) {
        this.actorRef = actorRef;
    }

    public String getRequestCorrelation() {
        return requestCorrelation;
    }

    public void setRequestCorrelation(String requestCorrelation) {
        this.requestCorrelation = requestCorrelation;
    }

    public String getRequirementCode() {
        return requirementCode;
    }

    public void setRequirementCode(String requirementCode) {
        this.requirementCode = requirementCode;
    }

    public String getStatusTransition() {
        return statusTransition;
    }

    public void setStatusTransition(String statusTransition) {
        this.statusTransition = statusTransition;
    }

    public String getEvidenceIdentityHash() {
        return evidenceIdentityHash;
    }

    public void setEvidenceIdentityHash(String evidenceIdentityHash) {
        this.evidenceIdentityHash = evidenceIdentityHash;
    }

    public String getSubjectFingerprint() {
        return subjectFingerprint;
    }

    public void setSubjectFingerprint(String subjectFingerprint) {
        this.subjectFingerprint = subjectFingerprint;
    }

    public String getAssessmentHash() {
        return assessmentHash;
    }

    public void setAssessmentHash(String assessmentHash) {
        this.assessmentHash = assessmentHash;
    }

    public Long getCommittedRevision() {
        return committedRevision;
    }

    public void setCommittedRevision(Long committedRevision) {
        this.committedRevision = committedRevision;
    }
}