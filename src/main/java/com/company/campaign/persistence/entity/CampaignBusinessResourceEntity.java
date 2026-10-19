package com.company.campaign.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;

/**
 * Host-owned Campaign business resource exposed through the read-only catalog.
 *
 * <p>The {@code aliases} column is a PostgreSQL {@code TEXT[]} array, mapped via
 * {@link com.company.campaign.persistence.PostgresTextArrayTypeHandler}.
 */
@TableName("campaign_business_resource")
public class CampaignBusinessResourceEntity {

    private String tenantId;
    private String programId;
    private String resourceType;
    private String resourceCode;
    private String displayName;

    @TableField(typeHandler = com.company.campaign.persistence.PostgresTextArrayTypeHandler.class)
    private String[] aliases;

    private Boolean enabled;
    private Boolean runtimeAvailable;
    private String integrationKey;
    private Integer sortOrder;
    private Instant updatedAt;

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

    public String getResourceType() {
        return resourceType;
    }

    public void setResourceType(String resourceType) {
        this.resourceType = resourceType;
    }

    public String getResourceCode() {
        return resourceCode;
    }

    public void setResourceCode(String resourceCode) {
        this.resourceCode = resourceCode;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String[] getAliases() {
        return aliases;
    }

    public void setAliases(String[] aliases) {
        this.aliases = aliases;
    }

    public Boolean getEnabled() {
        return enabled;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }

    public Boolean getRuntimeAvailable() {
        return runtimeAvailable;
    }

    public void setRuntimeAvailable(Boolean runtimeAvailable) {
        this.runtimeAvailable = runtimeAvailable;
    }

    public String getIntegrationKey() {
        return integrationKey;
    }

    public void setIntegrationKey(String integrationKey) {
        this.integrationKey = integrationKey;
    }

    public Integer getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(Integer sortOrder) {
        this.sortOrder = sortOrder;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}