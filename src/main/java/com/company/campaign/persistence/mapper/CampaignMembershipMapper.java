package com.company.campaign.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.company.campaign.persistence.entity.CampaignMembershipEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * Mapper for {@code campaign_membership} — all operations use custom XML mapper statements.
 */
@Mapper
public interface CampaignMembershipMapper extends BaseMapper<CampaignMembershipEntity> {
}