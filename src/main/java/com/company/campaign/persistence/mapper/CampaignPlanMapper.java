package com.company.campaign.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.company.campaign.persistence.entity.CampaignPlanEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * Mapper for {@code campaign_plan} — insert-only, lookup by plan_id.
 */
@Mapper
public interface CampaignPlanMapper extends BaseMapper<CampaignPlanEntity> {

    /** INSERT one immutable plan row (unconditional — may fail on duplicate PK). */
    int insert(CampaignPlanEntity entity);

    /** INSERT one immutable plan row with ON CONFLICT (plan_id) DO NOTHING for idempotent store. */
    int insertPlan(CampaignPlanEntity entity);

    /** Look up one plan by its deterministic plan_id. */
    CampaignPlanEntity findById(String planId);
}