package com.company.campaign.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.company.campaign.persistence.entity.CampaignConversationEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * Mapper for {@code campaign_conversation} — all operations use custom XML mapper statements.
 */
@Mapper
public interface CampaignConversationMapper extends BaseMapper<CampaignConversationEntity> {
}