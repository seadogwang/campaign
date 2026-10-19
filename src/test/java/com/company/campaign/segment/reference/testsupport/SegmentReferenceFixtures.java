package com.company.campaign.segment.reference.testsupport;

import com.company.campaign.metadata.domain.SubjectKind;
import com.company.campaign.metadata.testsupport.MetadataFixtures;
import com.company.campaign.security.authorization.AuthorizationDecision;
import com.company.campaign.security.authorization.AuthorizationDisposition;
import com.company.campaign.security.authorization.AuthorizationReason;
import com.company.campaign.security.authorization.AuthorizationRequest;
import com.company.campaign.security.authorization.AuthorizationService;
import com.company.campaign.security.authorization.ResourceKind;
import com.company.campaign.security.context.CampaignPermission;
import com.company.campaign.security.context.TrustedExecutionContext;
import com.company.campaign.segment.reference.AudienceProgramPolicy;
import com.company.campaign.segment.reference.AudienceVersionAvailability;
import com.company.campaign.segment.reference.AudienceVersionRef;
import com.company.campaign.segment.reference.AudienceVersionSnapshot;
import com.company.campaign.segment.reference.EffectiveDataScope;
import com.company.campaign.segment.reference.InMemoryAudienceVersionCatalog;
import com.company.campaign.segment.reference.InMemoryReferenceDataScopePort;
import com.company.campaign.segment.reference.ReferenceCandidate;
import com.company.campaign.segment.reference.ReferencePolicy;
import com.company.campaign.segment.reference.ReferenceValidationContext;
import com.company.campaign.shared.canonical.ContentHash;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * Deterministic T18 fixtures built only from accepted public contracts.
 *
 * <p>No clock, network, database, random identifier or corpus asset is touched, so every graph, scope and
 * hash these fixtures produce is reproducible byte for byte.
 */
public final class SegmentReferenceFixtures {

    public static final String TENANT = MetadataFixtures.TENANT;
    public static final String PROGRAM = MetadataFixtures.PROGRAM;
    public static final String FOREIGN_TENANT = "tenant-999";
    public static final String SIBLING_PROGRAM = "program-002";

    /** The trusted caller's own DataScope, as the metadata fixtures already pin it. */
    public static final String CALLER_SCOPE = "scope-008";
    /** A referenced version's published scope: a genuine subset of the caller's. */
    public static final String AUDIENCE_SCOPE = "scope-aud-1";
    /** A second referenced scope, narrower still. */
    public static final String NARROW_SCOPE = "scope-narrow";
    /** A scope sharing no member with the caller's, so their intersection is empty. */
    public static final String DISJOINT_SCOPE = "scope-disjoint";

    private static final Instant PUBLISHED_AT = Instant.parse("2026-09-01T00:00:00Z");

    private SegmentReferenceFixtures() {
    }

    public static ContentHash hash(String text) {
        return ContentHash.sha256(text.getBytes(StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------------ pins

    /** An exact pin whose content hash is a pure function of its own identity text. */
    public static AudienceVersionRef ref(String audienceId, String versionId) {
        return new AudienceVersionRef(audienceId, versionId, hash(audienceId + "/" + versionId));
    }

    public static EffectiveDataScope scope(String dataScopeId) {
        return new EffectiveDataScope(dataScopeId, hash("scope:" + dataScopeId));
    }

    // ------------------------------------------------------------------ snapshots

    public static AudienceVersionSnapshot snapshot(AudienceVersionRef version) {
        return snapshot(version, TENANT, PROGRAM, SubjectKind.MEMBER, AUDIENCE_SCOPE, List.of(), 1,
                AudienceVersionAvailability.AVAILABLE);
    }

    public static AudienceVersionSnapshot snapshot(
            AudienceVersionRef version, List<AudienceVersionRef> dependencies) {
        return snapshot(version, TENANT, PROGRAM, SubjectKind.MEMBER, AUDIENCE_SCOPE, dependencies,
                1 + dependencies.size(), AudienceVersionAvailability.AVAILABLE);
    }

    public static AudienceVersionSnapshot snapshot(
            AudienceVersionRef version,
            String tenantId,
            String programId,
            SubjectKind subject,
            String dataScopeId,
            List<AudienceVersionRef> dependencies,
            int expandedComplexity,
            AudienceVersionAvailability availability) {
        return new AudienceVersionSnapshot(
                tenantId,
                programId,
                version,
                subject,
                hash("ast:" + version.identityKey()),
                hash("plan:" + version.identityKey()),
                scope(dataScopeId),
                dependencies,
                hash("graph:" + version.identityKey()),
                PUBLISHED_AT,
                availability,
                expandedComplexity);
    }

    // ------------------------------------------------------------------ trusted context

    public static TrustedExecutionContext context() {
        return MetadataFixtures.context(MetadataFixtures.allPermissions());
    }

    /** A caller holding every permission except the one a segment reference requires. */
    public static TrustedExecutionContext contextWithoutSegmentEvaluate() {
        return MetadataFixtures.context(Set.of(CampaignPermission.CAMPAIGN_READ));
    }

    public static TrustedExecutionContext foreignTenantContext() {
        return MetadataFixtures.context(FOREIGN_TENANT, PROGRAM, MetadataFixtures.allPermissions());
    }

    // ------------------------------------------------------------------ authorization

    /** Allows every decision through the fail-closed boundary. */
    public static AuthorizationService allowAll() {
        return request -> AuthorizationDecision.allow(request.resource().kind(), request.resource().kind()
                .requiredPermission());
    }

    /** Denies every decision, whatever the resource kind. */
    public static AuthorizationService denyAll() {
        return request -> AuthorizationDecision.deny(
                AuthorizationDisposition.PERMISSION_DENIED,
                AuthorizationReason.MISSING_PERMISSION,
                request.resource().kind(),
                request.resource().kind().requiredPermission());
    }

    /** Denies only {@link ResourceKind#SEGMENT}; every other kind is allowed. */
    public static AuthorizationService denySegmentsOnly() {
        return request -> request.resource().kind() == ResourceKind.SEGMENT
                ? AuthorizationDecision.deny(
                        AuthorizationDisposition.PERMISSION_DENIED,
                        AuthorizationReason.MISSING_PERMISSION,
                        ResourceKind.SEGMENT,
                        ResourceKind.SEGMENT.requiredPermission())
                : AuthorizationDecision.allow(
                        request.resource().kind(), request.resource().kind().requiredPermission());
    }

    /** A boundary that throws on every call, standing in for an unreachable decision service. */
    public static AuthorizationService throwing() {
        return request -> {
            throw new IllegalStateException("authorization boundary failure with secret-like text");
        };
    }

    // ------------------------------------------------------------------ ports

    /** The caller scope {m1..m4}, the audience scope {m1..m3}, a narrower {m1,m2} and a disjoint {x1}. */
    public static InMemoryReferenceDataScopePort dataScopePort() {
        return new InMemoryReferenceDataScopePort()
                .register(CALLER_SCOPE, Set.of("m1", "m2", "m3", "m4"))
                .register(AUDIENCE_SCOPE, Set.of("m1", "m2", "m3"))
                .register(NARROW_SCOPE, Set.of("m1", "m2"))
                .register(DISJOINT_SCOPE, Set.of("x1"));
    }

    public static AudienceProgramPolicy sameProgramOnly() {
        return AudienceProgramPolicy.sameProgramOnly();
    }

    /** A host policy that additionally allows this program to reach one sibling program. */
    public static AudienceProgramPolicy allowingSiblingProgram() {
        return (caller, referenced) -> caller != null
                && (caller.equals(referenced) || caller.equals(PROGRAM) && referenced.equals(SIBLING_PROGRAM));
    }

    public static ReferenceValidationContext referenceContext(InMemoryAudienceVersionCatalog catalog) {
        return ReferenceValidationContext.of(
                catalog, sameProgramOnly(), dataScopePort(), ReferencePolicy.standard());
    }

    public static ReferenceValidationContext referenceContext(
            InMemoryAudienceVersionCatalog catalog, ReferencePolicy policy) {
        return ReferenceValidationContext.of(catalog, sameProgramOnly(), dataScopePort(), policy);
    }

    public static ReferenceValidationContext referenceContext(
            InMemoryAudienceVersionCatalog catalog, AudienceProgramPolicy programPolicy) {
        return ReferenceValidationContext.of(catalog, programPolicy, dataScopePort(), ReferencePolicy.standard());
    }

    // ------------------------------------------------------------------ candidates

    public static ReferenceCandidate candidate(String nodeId, AudienceVersionRef reference) {
        return new ReferenceCandidate(nodeId, reference, SubjectKind.MEMBER);
    }

    /** A catalog holding one available version of {@code audience-vip}. */
    public static InMemoryAudienceVersionCatalog catalogWith(AudienceVersionSnapshot... snapshots) {
        InMemoryAudienceVersionCatalog catalog = new InMemoryAudienceVersionCatalog();
        for (AudienceVersionSnapshot snapshot : snapshots) {
            catalog.publish(snapshot);
        }
        return catalog;
    }
}