package com.company.campaign.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.company.campaign.persistence.entity.CampaignPlanCompileIdempotencyEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * Mapper for {@code campaign_plan_compile_idempotency} — insert with conflict detection, lookup by key.
 */
@Mapper
public interface CampaignPlanCompileIdempotencyMapper extends BaseMapper<CampaignPlanCompileIdempotencyEntity> {

    /** INSERT an idempotency record with ON CONFLICT DO NOTHING. */
    int tryInsertIdempotency(CampaignPlanCompileIdempotencyEntity entity);

    /** Look up the idempotency record by key. */
    CampaignPlanCompileIdempotencyEntity findByIdempotencyKey(String idempotencyKey);
}