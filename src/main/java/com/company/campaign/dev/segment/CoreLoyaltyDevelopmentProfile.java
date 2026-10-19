package com.company.campaign.dev.segment;

import com.company.campaign.builder.segment.DevelopmentAiToSqlRequest;
import com.company.campaign.contracts.LocaleTag;
import com.company.campaign.contracts.TrustedExecutionContextRef;
import com.company.campaign.metadata.domain.MetadataSnapshot;
import com.company.campaign.metadata.resource.MetadataSnapshotResourceLoader;
import com.company.campaign.query.sql.SqlCompilePolicy;
import com.company.campaign.query.sql.StorageCatalog;
import com.company.campaign.query.sql.StorageCatalogResourceLoader;
import com.company.campaign.security.authorization.AuthorizationService;
import com.company.campaign.security.authorization.DataScopeDecision;
import com.company.campaign.security.authorization.FailClosedAuthorizationService;
import com.company.campaign.security.context.CampaignPermission;
import com.company.campaign.security.context.MembershipStatus;
import com.company.campaign.security.context.PrincipalMembership;
import com.company.campaign.security.context.TrustedExecutionContext;
import com.company.campaign.security.context.VerifiedIdentity;
import com.company.campaign.segment.intent.RegisteredDefaultSource;
import com.company.campaign.segment.intent.RegisteredDefaultsResourceLoader;
import com.company.campaign.segment.resolution.ResolutionPolicy;
import com.company.campaign.segment.resolution.ResolutionPolicyResourceLoader;
import com.company.campaign.segment.validation.SegmentValidationPolicy;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Locale;
import java.util.Set;

/** Frozen development-only Loyalty Metadata, policy, trusted context and physical storage mapping. */
public final class CoreLoyaltyDevelopmentProfile {

    public static final String PROFILE_VERSION = "core-loyalty-development/17";
    public static final String TENANT = "tenant-dev";
    public static final String PROGRAM = "program-dev";
    public static final String SNAPSHOT_ID = "core-loyalty-metadata";
    public static final String SNAPSHOT_VERSION = "17";

    private static final MetadataSnapshot SNAPSHOT = DevelopmentSupplementLoader.metadata(
            "metadata/core-loyalty-development-v17-supplement.json").apply(
                    MetadataSnapshotResourceLoader.load("metadata/core-loyalty-development-v10.json"));
    private static final ResolutionPolicy POLICY = ResolutionPolicyResourceLoader.load(
            "metadata/core-loyalty-resolution-v17.json");
    private static final RegisteredDefaultSource DEFAULTS = RegisteredDefaultsResourceLoader.load(
            "metadata/core-loyalty-defaults-v7.json");
    private static final StorageCatalog STORAGE = DevelopmentSupplementLoader.storage(
            "metadata/core-loyalty-storage-v16-supplement.json").apply(
                    StorageCatalogResourceLoader.load("metadata/core-loyalty-storage-v9.json"));
    private static final AuthorizationService AUTHORIZATION =
            new FailClosedAuthorizationService(query -> DataScopeDecision.allowed());

    private CoreLoyaltyDevelopmentProfile() {
    }

    public static MetadataSnapshot snapshot() {
        return SNAPSHOT;
    }

    public static ResolutionPolicy resolutionPolicy() {
        return POLICY;
    }

    public static StorageCatalog storageCatalog() {
        return STORAGE;
    }

    public static RegisteredDefaultSource registeredDefaults() {
        return DEFAULTS;
    }

    public static AuthorizationService authorizationService() {
        return AUTHORIZATION;
    }

    public static DevelopmentAiToSqlRequest request(String utterance, Instant asOf) {
        return new DevelopmentAiToSqlRequest(
                utterance,
                "zh-CN",
                SNAPSHOT,
                POLICY,
                DEFAULTS,
                context(asOf),
                SegmentValidationPolicy.standard(),
                STORAGE,
                SqlCompilePolicy.standard(),
                null);
    }

    private static TrustedExecutionContext context(Instant asOf) {
        Set<CampaignPermission> permissions = Set.of(CampaignPermission.values());
        VerifiedIdentity identity = new VerifiedIdentity("user-dev", "https://issuer.example", asOf);
        PrincipalMembership membership = new PrincipalMembership(
                "membership-dev", "user-dev", TENANT, PROGRAM, "scope-dev",
                MembershipStatus.ACTIVE, permissions);
        TrustedExecutionContextRef reference = new TrustedExecutionContextRef(
                TENANT, PROGRAM, "user-dev", "scope-dev", SNAPSHOT_ID,
                LocaleTag.of(Locale.SIMPLIFIED_CHINESE), ZoneId.of("Asia/Shanghai"), "CNY", asOf, asOf);
        return TrustedExecutionContext.of(identity, membership, reference);
    }

}
