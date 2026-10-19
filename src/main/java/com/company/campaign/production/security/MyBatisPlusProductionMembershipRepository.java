package com.company.campaign.production.security;

import com.company.campaign.contracts.LocaleTag;
import com.company.campaign.contracts.TrustedExecutionContextRef;
import com.company.campaign.persistence.entity.CampaignMembershipEntity;
import com.company.campaign.security.context.CampaignPermission;
import com.company.campaign.security.context.MembershipStatus;
import com.company.campaign.security.context.PrincipalMembership;
import com.company.campaign.security.context.VerifiedIdentity;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;

import java.time.Instant;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The durable MyBatis-Plus adapter of {@link ProductionMembershipRepository}.
 *
 * <p>It selects the active membership of a verified issuer and subject with one constant mapper statement and a
 * second constant statement for its permissions. No identifier in either statement is request-controlled: the
 * table names are literals, the schema is bound through the pool's {@code search_path}, and the only bound values
 * are the verified issuer, the verified subject and the opaque selector (or SQL {@code NULL}).
 *
 * <p>Every lookup is bounded: the session is read-only, the two statements are closed with the session, and the
 * result rows are counted against the configured maximums. Zero matches, an inactive or expired membership, a row
 * whose issuer/subject contradict the verified identity, an unknown permission and an ambiguous match all return
 * the same empty result, so no refusal discloses a fact about the directory. A database failure is raised as a
 * bounded {@link ProductionMembershipUnavailableException}.
 */
final class MyBatisPlusProductionMembershipRepository implements ProductionMembershipRepository {

    /**
     * The one constant membership statement. The selector is matched with {@code IS NOT DISTINCT FROM} so that
     * the same literal statement binds either an opaque value or {@code NULL}, and no SQL is ever concatenated.
     */
    static final String MEMBERSHIP_STATEMENT =
            "com.company.campaign.persistence.mapper.CampaignMembershipMapper.findActiveMembership";

    /** The one constant permission statement; permissions are normalized through the closed vocabulary. */
    static final String PERMISSION_STATEMENT =
            "com.company.campaign.persistence.mapper.CampaignMembershipPermissionMapper.findPermissionsByMembershipId";

    private final SqlSessionFactory sqlSessionFactory;
    private final ProductionSecurityProperties.Membership properties;

    MyBatisPlusProductionMembershipRepository(
            SqlSessionFactory sqlSessionFactory, ProductionSecurityProperties.Membership properties) {
        this.sqlSessionFactory = Objects.requireNonNull(sqlSessionFactory, "sqlSessionFactory");
        this.properties = Objects.requireNonNull(properties, "properties");
    }

    @Override
    public Optional<ResolvedMembership> findActiveMembership(
            VerifiedIdentity identity, Optional<String> selectionClaimValue) {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(selectionClaimValue, "selectionClaimValue");
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            List<CampaignMembershipEntity> rows = readMembershipRows(session, identity, selectionClaimValue);
            if (rows.size() != 1) {
                return Optional.empty();
            }
            CampaignMembershipEntity row = rows.get(0);
            if (!owns(row, identity) || !isActive(row) || !isValidAt(row, identity.authenticatedAt())) {
                return Optional.empty();
            }
            Set<CampaignPermission> permissions = readPermissions(session, row.getMembershipId());
            return Optional.of(toResolvedMembership(row, permissions));
        } catch (RuntimeException failure) {
            if (failure instanceof ProductionMembershipUnavailableException pmue) {
                throw pmue;
            }
            // An unknown permission, a malformed locale/timezone/currency and an incoherent snapshot all refuse.
            return Optional.empty();
        }
    }

    private List<CampaignMembershipEntity> readMembershipRows(
            SqlSession session, VerifiedIdentity identity, Optional<String> selectionClaimValue) {
        Map<String, Object> params = new HashMap<>();
        params.put("issuer", identity.issuer());
        params.put("subject", identity.subjectId());
        params.put("selectionKey", selectionClaimValue.orElse(null));
        return session.selectList(MEMBERSHIP_STATEMENT, params);
    }

    private Set<CampaignPermission> readPermissions(SqlSession session, String membershipId) {
        Map<String, String> params = Map.of("membershipId", membershipId);
        List<String> permissionNames = session.selectList(PERMISSION_STATEMENT, params);
        if (permissionNames.size() > properties.getMaxPermissionRows()) {
            return Set.of();
        }
        Set<CampaignPermission> permissions = new LinkedHashSet<>();
        for (String name : permissionNames) {
            permissions.add(CampaignPermission.fromWireName(name));
        }
        return permissions;
    }

    // ------------------------------------------------------------------ row predicates

    private static boolean owns(CampaignMembershipEntity row, VerifiedIdentity identity) {
        return Objects.equals(identity.issuer(), row.getIssuer())
                && Objects.equals(identity.subjectId(), row.getSubject());
    }

    private static boolean isActive(CampaignMembershipEntity row) {
        return "ACTIVE".equalsIgnoreCase(row.getStatus());
    }

    /**
     * Checks that the membership interval covers the authentication instant.
     *
     * <p>A null {@code validFrom}, {@code asOf} or {@code dataWatermark} is treated as invalid, and an
     * authentication instant before {@code validFrom} or after {@code validUntil} is refused.
     */
    private static boolean isValidAt(CampaignMembershipEntity row, Instant authenticatedAt) {
        if (row.getValidFrom() == null || row.getAsOf() == null || row.getDataWatermark() == null
                || authenticatedAt == null) {
            return false;
        }
        if (authenticatedAt.isBefore(row.getValidFrom())) {
            return false;
        }
        return row.getValidUntil() == null || !authenticatedAt.isAfter(row.getValidUntil());
    }

    // ------------------------------------------------------------------ domain mapping

    private static ResolvedMembership toResolvedMembership(
            CampaignMembershipEntity row, Set<CampaignPermission> permissions) {
        PrincipalMembership membership = new PrincipalMembership(
                require(row.getMembershipId()),
                require(row.getUserId()),
                require(row.getTenantId()),
                require(row.getProgramId()),
                require(row.getDataScopeId()),
                MembershipStatus.ACTIVE,
                permissions);
        TrustedExecutionContextRef reference = new TrustedExecutionContextRef(
                require(row.getTenantId()),
                require(row.getProgramId()),
                require(row.getUserId()),
                require(row.getDataScopeId()),
                require(row.getMetadataSnapshotId()),
                LocaleTag.of(Locale.forLanguageTag(require(row.getLocale()))),
                ZoneId.of(require(row.getTimezone())),
                require(row.getReportingCurrency()),
                row.getAsOf(),
                row.getDataWatermark());
        return new ResolvedMembership(membership, reference);
    }

    private static String require(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("a required membership column is absent");
        }
        return value;
    }
}