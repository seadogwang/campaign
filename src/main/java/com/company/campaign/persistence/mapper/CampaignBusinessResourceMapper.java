package com.company.campaign.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.company.campaign.persistence.entity.CampaignBusinessResourceEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * Mapper for {@code campaign_business_resource} — all operations use custom XML mapper statements.
 */
@Mapper
public interface CampaignBusinessResourceMapper extends BaseMapper<CampaignBusinessResourceEntity> {
}