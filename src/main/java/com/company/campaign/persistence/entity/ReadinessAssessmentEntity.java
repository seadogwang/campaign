package com.company.campaign.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;

/**
 * Append-only readiness assessment row.
 */
@TableName("readiness_assessment")
public class ReadinessAssessmentEntity {

    private String tenantId;
    private String programId;
    private String environment;
    private String assessmentId;
    private String formatVersion;
    private String level;
    private String status;
    private Instant evaluatedAt;
    private String acceptedEvidenceJson;
    private String blockersJson;
    private String assessmentHash;
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

    public String getAssessmentId() {
        return assessmentId;
    }

    public void setAssessmentId(String assessmentId) {
        this.assessmentId = assessmentId;
    }

    public String getFormatVersion() {
        return formatVersion;
    }

    public void setFormatVersion(String formatVersion) {
        this.formatVersion = formatVersion;
    }

    public String getLevel() {
        return level;
    }

    public void setLevel(String level) {
        this.level = level;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Instant getEvaluatedAt() {
        return evaluatedAt;
    }

    public void setEvaluatedAt(Instant evaluatedAt) {
        this.evaluatedAt = evaluatedAt;
    }

    public String getAcceptedEvidenceJson() {
        return acceptedEvidenceJson;
    }

    public void setAcceptedEvidenceJson(String acceptedEvidenceJson) {
        this.acceptedEvidenceJson = acceptedEvidenceJson;
    }

    public String getBlockersJson() {
        return blockersJson;
    }

    public void setBlockersJson(String blockersJson) {
        this.blockersJson = blockersJson;
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