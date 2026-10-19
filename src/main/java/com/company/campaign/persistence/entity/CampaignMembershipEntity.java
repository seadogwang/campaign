package com.company.campaign.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;

/**
 * Server-owned production membership row.
 */
@TableName("campaign_membership")
public class CampaignMembershipEntity {

    @TableId
    private String membershipId;
    private String issuer;
    private String subject;
    private String userId;
    private String tenantId;
    private String programId;
    private String dataScopeId;
    private String selectionKey;
    private String status;
    private Instant validFrom;
    private Instant validUntil;
    private String metadataSnapshotId;
    private String locale;
    private String timezone;
    private String reportingCurrency;
    private Instant asOf;
    private Instant dataWatermark;
    private Long revision;
    private Instant createdAt;

    public String getMembershipId() {
        return membershipId;
    }

    public void setMembershipId(String membershipId) {
        this.membershipId = membershipId;
    }

    public String getIssuer() {
        return issuer;
    }

    public void setIssuer(String issuer) {
        this.issuer = issuer;
    }

    public String getSubject() {
        return subject;
    }

    public void setSubject(String subject) {
        this.subject = subject;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
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

    public String getDataScopeId() {
        return dataScopeId;
    }

    public void setDataScopeId(String dataScopeId) {
        this.dataScopeId = dataScopeId;
    }

    public String getSelectionKey() {
        return selectionKey;
    }

    public void setSelectionKey(String selectionKey) {
        this.selectionKey = selectionKey;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Instant getValidFrom() {
        return validFrom;
    }

    public void setValidFrom(Instant validFrom) {
        this.validFrom = validFrom;
    }

    public Instant getValidUntil() {
        return validUntil;
    }

    public void setValidUntil(Instant validUntil) {
        this.validUntil = validUntil;
    }

    public String getMetadataSnapshotId() {
        return metadataSnapshotId;
    }

    public void setMetadataSnapshotId(String metadataSnapshotId) {
        this.metadataSnapshotId = metadataSnapshotId;
    }

    public String getLocale() {
        return locale;
    }

    public void setLocale(String locale) {
        this.locale = locale;
    }

    public String getTimezone() {
        return timezone;
    }

    public void setTimezone(String timezone) {
        this.timezone = timezone;
    }

    public String getReportingCurrency() {
        return reportingCurrency;
    }

    public void setReportingCurrency(String reportingCurrency) {
        this.reportingCurrency = reportingCurrency;
    }

    public Instant getAsOf() {
        return asOf;
    }

    public void setAsOf(Instant asOf) {
        this.asOf = asOf;
    }

    public Instant getDataWatermark() {
        return dataWatermark;
    }

    public void setDataWatermark(Instant dataWatermark) {
        this.dataWatermark = dataWatermark;
    }

    public Long getRevision() {
        return revision;
    }

    public void setRevision(Long revision) {
        this.revision = revision;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}