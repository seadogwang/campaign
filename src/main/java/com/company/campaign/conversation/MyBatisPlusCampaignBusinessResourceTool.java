package com.company.campaign.conversation;

import com.company.campaign.builder.campaignagent.CampaignAgentErrorCodes;
import com.company.campaign.builder.campaignagent.CampaignAgentException;
import com.company.campaign.builder.campaignagent.CampaignBusinessResource;
import com.company.campaign.builder.campaignagent.CampaignBusinessResourceCatalog;
import com.company.campaign.builder.campaignagent.CampaignBusinessResourceTool;
import com.company.campaign.builder.campaignagent.CampaignBusinessResourceType;
import com.company.campaign.persistence.entity.CampaignBusinessResourceEntity;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * MyBatis-Plus adapter for the trusted Campaign business-resource discovery Tool.
 *
 * <p>It reads the {@code campaign_business_resource} table through the dedicated mapper statement and returns a
 * bounded, immutable {@link CampaignBusinessResourceCatalog}. A database failure or a malformed row is refused as
 * a non-disclosing {@code RESOURCE_CATALOG_UNAVAILABLE} code that carries neither a tenant, a program, a resource
 * identity nor a driver message.
 */
public final class MyBatisPlusCampaignBusinessResourceTool implements CampaignBusinessResourceTool {

    private static final String DISCOVER_STATEMENT =
            "com.company.campaign.persistence.mapper.CampaignBusinessResourceMapper.discoverResources";

    private final SqlSessionFactory sqlSessionFactory;

    public MyBatisPlusCampaignBusinessResourceTool(SqlSessionFactory sqlSessionFactory) {
        this.sqlSessionFactory = Objects.requireNonNull(sqlSessionFactory, "sqlSessionFactory");
    }

    @Override
    public CampaignBusinessResourceCatalog discover(String tenantId, String programId) {
        if (tenantId == null || tenantId.isBlank() || programId == null || programId.isBlank()) {
            throw CampaignAgentException.of(
                    CampaignAgentErrorCodes.REQUEST_INVALID,
                    "Business resource discovery requires trusted tenant and program identities.");
        }
        List<CampaignBusinessResource> resources = new ArrayList<>();
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            Map<String, String> params = Map.of("tenantId", tenantId, "programId", programId);
            List<CampaignBusinessResourceEntity> rows = session.selectList(DISCOVER_STATEMENT, params);
            for (CampaignBusinessResourceEntity row : rows) {
                resources.add(toDomain(row));
            }
        } catch (RuntimeException failure) {
            throw CampaignAgentException.of(
                    CampaignAgentErrorCodes.RESOURCE_CATALOG_UNAVAILABLE,
                    "The Campaign business resource catalog is unavailable.");
        }
        return new CampaignBusinessResourceCatalog(resources);
    }

    private static CampaignBusinessResource toDomain(CampaignBusinessResourceEntity entity) {
        return new CampaignBusinessResource(
                CampaignBusinessResourceType.valueOf(entity.getResourceType()),
                entity.getResourceCode(),
                entity.getDisplayName(),
                aliases(entity.getAliases()),
                Boolean.TRUE.equals(entity.getEnabled()),
                Boolean.TRUE.equals(entity.getRuntimeAvailable()),
                entity.getIntegrationKey());
    }

    /**
     * Converts the PostgreSQL {@code TEXT[]} column — already mapped to {@code String[]} by
     * {@link com.company.campaign.persistence.PostgresTextArrayTypeHandler} — to an immutable list.
     */
    private static List<String> aliases(String[] value) {
        if (value == null) {
            return List.of();
        }
        return List.of(value);
    }
}