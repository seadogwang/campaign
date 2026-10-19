package com.company.campaign.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.company.campaign.persistence.entity.ReadinessScopeEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * Mapper for {@code readiness_scope} — all operations use custom XML mapper statements.
 */
@Mapper
public interface ReadinessScopeMapper extends BaseMapper<ReadinessScopeEntity> {
}