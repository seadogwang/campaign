package com.company.campaign.query.testsupport;

import com.company.campaign.evaluation.segment.contract.MemberIdSet;
import com.company.campaign.query.fixture.FixtureScopeBinding;
import com.company.campaign.query.plan.ReferenceExecutionCheck;
import com.company.campaign.query.plan.ReferenceExecutionGuard;
import com.company.campaign.query.plan.SegmentQueryPlanner;
import com.company.campaign.query.plan.SemanticQueryPlan;
import com.company.campaign.security.authorization.AuthorizationService;
import com.company.campaign.segment.dsl.contract.SegmentDsl;
import com.company.campaign.segment.dsl.predicate.AudienceReferencePredicate;
import com.company.campaign.segment.dsl.predicate.Predicate;
import com.company.campaign.segment.reference.AudienceMembershipPort;
import com.company.campaign.segment.reference.AudienceProgramPolicy;
import com.company.campaign.segment.reference.AudienceRetentionPort;
import com.company.campaign.segment.reference.AudienceVersionRef;
import com.company.campaign.segment.reference.AudienceVersionSnapshot;
import com.company.campaign.segment.reference.FixtureAudienceMembershipDataset;
import com.company.campaign.segment.reference.InMemoryAudienceRetentionStore;
import com.company.campaign.segment.reference.InMemoryAudienceVersionCatalog;
import com.company.campaign.segment.reference.ReferenceExecutionContext;
import com.company.campaign.segment.reference.ReferencePolicy;
import com.company.campaign.segment.reference.testsupport.SegmentReferenceFixtures;
import com.company.campaign.segment.validation.SegmentValidationResult;
import com.company.campaign.segment.validation.SegmentValidator;
import com.company.campaign.segment.validation.testsupport.SegmentValidationFixtures;

import java.util.List;
import java.util.Set;

/**
 * Deterministic T18 planning/execution fixtures bridging the accepted reference contracts to the T08 planner
 * and the T09 fixture executor.
 *
 * <p>No clock, network, database, random identifier or corpus asset is touched, so every graph, scope, plan
 * and membership hash these helpers produce is reproducible byte for byte.
 */
public final class ReferenceQueryFixtures {

    /** The host-computed effective scope of a single root-level reference under the standard fixtures. */
    public static final String EFFECTIVE_SCOPE_ID = SegmentReferenceFixtures.AUDIENCE_SCOPE;

    /** The caller's own trusted scope, as the standard trusted context already pins it. */
    public static final String CALLER_SCOPE_ID = SegmentReferenceFixtures.CALLER_SCOPE;

    private ReferenceQueryFixtures() {
    }

    // ------------------------------------------------------------------ pins and documents

    /** The one standard referenced version: {@code audience-vip@version-7}. */
    public static AudienceVersionRef version() {
        return version("audience-vip", "version-7");
    }

    /** An exact pin whose content hash is a pure function of its own identity text. */
    public static AudienceVersionRef version(String audienceId, String versionId) {
        return SegmentReferenceFixtures.ref(audienceId, versionId);
    }

    /** An editable DSL reference leaf for one exact pin. */
    public static AudienceReferencePredicate reference(String nodeId, AudienceVersionRef version) {
        return new AudienceReferencePredicate(
                nodeId, version.audienceId(), version.versionId(), version.versionHash());
    }

    /** A document whose predicate is the supplied tree, pinned to the standard Metadata Snapshot. */
    public static SegmentDsl document(Predicate predicate) {
        return SegmentValidationFixtures.publish(predicate);
    }

    /** A document that is exactly one reference to the supplied pin. */
    public static SegmentDsl referenceDocument(AudienceVersionRef version) {
        return document(reference("n-ref", version));
    }

    // ------------------------------------------------------------------ trusted ports

    /** A catalog holding exactly the supplied snapshots. */
    public static InMemoryAudienceVersionCatalog catalogWith(AudienceVersionSnapshot... snapshots) {
        return SegmentReferenceFixtures.catalogWith(snapshots);
    }

    /** A catalog holding the standard snapshot of one version. */
    public static InMemoryAudienceVersionCatalog catalogOf(AudienceVersionRef version) {
        return catalogWith(SegmentReferenceFixtures.snapshot(version));
    }

    /** The host reference ports for planning and execution, over a retention store that retains indefinitely. */
    public static ReferenceExecutionContext executionContext(InMemoryAudienceVersionCatalog catalog) {
        return executionContext(catalog, InMemoryAudienceRetentionStore.retaining());
    }

    /** The host reference ports for planning and execution over a chosen retention store. */
    public static ReferenceExecutionContext executionContext(
            InMemoryAudienceVersionCatalog catalog, AudienceRetentionPort retentionPort) {
        return new ReferenceExecutionContext(
                catalog,
                AudienceProgramPolicy.sameProgramOnly(),
                SegmentReferenceFixtures.dataScopePort(),
                catalog,
                retentionPort,
                ReferencePolicy.standard());
    }

    /** The execution check a reference-bearing plan and its execution paths must be supplied with. */
    public static ReferenceExecutionCheck executionCheck(
            InMemoryAudienceVersionCatalog catalog, AuthorizationService authorizationService) {
        return new ReferenceExecutionCheck(
                new ReferenceExecutionGuard(authorizationService),
                SegmentValidationFixtures.context(),
                executionContext(catalog));
    }

    // ------------------------------------------------------------------ validation and planning

    /** Validates one reference-bearing document against the standard fixtures and fails on any finding. */
    public static SegmentValidationResult validated(
            SegmentDsl document, InMemoryAudienceVersionCatalog catalog) {
        return validated(document, catalog, SegmentReferenceFixtures.allowAll());
    }

    /** Validates one reference-bearing document under a chosen authorization boundary. */
    public static SegmentValidationResult validated(
            SegmentDsl document, InMemoryAudienceVersionCatalog catalog, AuthorizationService authorizationService) {
        SegmentValidationResult result = new SegmentValidator(authorizationService).validate(
                document,
                SegmentValidationFixtures.snapshot(),
                SegmentValidationFixtures.context(),
                SegmentValidationFixtures.policy(),
                SegmentReferenceFixtures.referenceContext(catalog));
        if (!result.valid()) {
            throw new IllegalStateException("reference fixture document did not validate: "
                    + result.issues().stream().map(com.company.campaign.contracts.Issue::code).toList());
        }
        return result;
    }

    /** Plans one already validated reference-bearing result through the reference-aware planner entry point. */
    public static SemanticQueryPlan plan(
            SegmentValidationResult result,
            InMemoryAudienceVersionCatalog catalog,
            ReferenceExecutionContext referenceContext) {
        return new SegmentQueryPlanner(SegmentReferenceFixtures.allowAll()).plan(
                result,
                SegmentValidationFixtures.snapshot(),
                SegmentValidationFixtures.context(),
                referenceContext);
    }

    /** Validates and plans one reference-bearing document under the default fixtures. */
    public static SemanticQueryPlan plan(SegmentDsl document, InMemoryAudienceVersionCatalog catalog) {
        return plan(validated(document, catalog), catalog, executionContext(catalog));
    }

    /** Validates and plans one reference-bearing document under the default fixtures. */
    public static SemanticQueryPlan plan(Predicate predicate, InMemoryAudienceVersionCatalog catalog) {
        return plan(document(predicate), catalog);
    }

    // ------------------------------------------------------------------ execution fixtures

    /** A membership dataset that answers exactly the supplied members for one exact pin. */
    public static FixtureAudienceMembershipDataset members(AudienceVersionRef version, Set<String> memberIds) {
        return new FixtureAudienceMembershipDataset().register(version, memberIds);
    }

    /** A membership dataset that answers nothing at all. */
    public static AudienceMembershipPort noMembers() {
        return (reference, scope) -> java.util.Optional.empty();
    }

    /** A retention store that is unreachable on every operation, standing in for a failed host lookup. */
    public static AudienceRetentionPort unreachableRetention() {
        return new AudienceRetentionPort() {
            @Override
            public com.company.campaign.segment.reference.AudienceRetentionHold acquire(
                    com.company.campaign.segment.reference.AudienceRetentionHold hold) {
                throw new IllegalStateException("retention store unreachable");
            }

            @Override
            public void release(String holdId) {
                throw new IllegalStateException("retention store unreachable");
            }

            @Override
            public List<com.company.campaign.segment.reference.AudienceRetentionHold> activeHolds(
                    AudienceVersionRef reference) {
                throw new IllegalStateException("retention store unreachable");
            }

            @Override
            public com.company.campaign.segment.reference.HardDeletionOutcome hardDelete(
                    AudienceVersionRef reference) {
                throw new IllegalStateException("retention store unreachable");
            }

            @Override
            public com.company.campaign.segment.reference.RetentionReleasePolicy releasePolicy() {
                throw new IllegalStateException("retention store unreachable");
            }
        };
    }

    /**
     * The fixture DataScope binding a reference-bearing execution needs: the caller's own scope and the
     * host-computed effective intersection, both server-owned.
     */
    public static FixtureScopeBinding referenceScope(MemberIdSet callerMembers, MemberIdSet effectiveMembers) {
        return FixtureScopeBinding.publish("fixture-scope-reference-v1", List.of(
                new FixtureScopeBinding.ScopeEntry(CALLER_SCOPE_ID, callerMembers),
                new FixtureScopeBinding.ScopeEntry(EFFECTIVE_SCOPE_ID, effectiveMembers)));
    }
}