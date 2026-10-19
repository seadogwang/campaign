package com.company.campaign.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.company.campaign.persistence.entity.CampaignProductionAuditLedgerEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * Mapper for {@code campaign_production_audit_ledger} — all operations use custom XML mapper statements.
 */
@Mapper
public interface CampaignProductionAuditLedgerMapper extends BaseMapper<CampaignProductionAuditLedgerEntity> {
}