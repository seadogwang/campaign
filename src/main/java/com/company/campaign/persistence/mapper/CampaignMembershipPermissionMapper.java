package com.company.campaign.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.company.campaign.persistence.entity.CampaignMembershipPermissionEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * Mapper for {@code campaign_membership_permission} — all operations use custom XML mapper statements.
 */
@Mapper
public interface CampaignMembershipPermissionMapper extends BaseMapper<CampaignMembershipPermissionEntity> {
}