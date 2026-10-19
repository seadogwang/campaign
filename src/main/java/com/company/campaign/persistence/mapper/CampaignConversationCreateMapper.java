package com.company.campaign.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.company.campaign.persistence.entity.CampaignConversationCreateEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * Mapper for {@code campaign_conversation_create} — all operations use custom XML mapper statements.
 */
@Mapper
public interface CampaignConversationCreateMapper extends BaseMapper<CampaignConversationCreateEntity> {
}