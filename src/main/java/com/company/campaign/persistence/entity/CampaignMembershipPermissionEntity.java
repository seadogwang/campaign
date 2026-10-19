package com.company.campaign.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableName;

/**
 * Permission row for one production membership.
 */
@TableName("campaign_membership_permission")
public class CampaignMembershipPermissionEntity {

    private String membershipId;
    private String permission;

    public String getMembershipId() {
        return membershipId;
    }

    public void setMembershipId(String membershipId) {
        this.membershipId = membershipId;
    }

    public String getPermission() {
        return permission;
    }

    public void setPermission(String permission) {
        this.permission = permission;
    }
}