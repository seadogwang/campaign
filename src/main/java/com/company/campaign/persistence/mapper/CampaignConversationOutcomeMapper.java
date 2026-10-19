package com.company.campaign.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.company.campaign.persistence.entity.CampaignConversationOutcomeEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * Mapper for {@code campaign_conversation_outcome} — all operations use custom XML mapper statements.
 */
@Mapper
public interface CampaignConversationOutcomeMapper extends BaseMapper<CampaignConversationOutcomeEntity> {
}