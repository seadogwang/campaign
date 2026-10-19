package com.company.campaign.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.company.campaign.persistence.entity.ReadinessAuditEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * Mapper for {@code readiness_audit} — all operations use custom XML mapper statements.
 */
@Mapper
public interface ReadinessAuditMapper extends BaseMapper<ReadinessAuditEntity> {
}