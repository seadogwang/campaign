package com.company.campaign.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.company.campaign.persistence.entity.ReadinessAssessmentEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * Mapper for {@code readiness_assessment} — all operations use custom XML mapper statements.
 */
@Mapper
public interface ReadinessAssessmentMapper extends BaseMapper<ReadinessAssessmentEntity> {
}