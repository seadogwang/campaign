package com.company.campaign.lifecycle.testsupport;

import com.company.campaign.builder.authoring.SegmentAuthoringIntegration;
import com.company.campaign.builder.authoring.SegmentAuthoringResult;
import com.company.campaign.builder.authoring.SegmentAuthoringVersions;
import com.company.campaign.builder.authoring.testsupport.SegmentAuthoringFixtures;
import com.company.campaign.builder.authoring.testsupport.SegmentAuthoringIntegrationFixtures;
import com.company.campaign.builder.compiler.CampaignCompiler;
import com.company.campaign.builder.compiler.CampaignPlan;
import com.company.campaign.builder.compiler.CampaignPlanStore;
import com.company.campaign.builder.compiler.CapabilityReadiness;
import com.company.campaign.builder.compiler.InMemoryCampaignPlanStore;
import com.company.campaign.builder.compiler.PlanRiskFixtures;
import com.company.campaign.builder.compiler.TrustedCurrentCampaign;
import com.company.campaign.evaluation.segment.gate.CapabilityState;
import com.company.campaign.evaluation.segment.gate.ReferenceApprovalLookupPort;
import com.company.campaign.evaluation.segment.gate.SegmentReadinessProjection;
import com.company.campaign.lifecycle.ApplyService;
import com.company.campaign.lifecycle.FrozenCompatibilityPort;
import com.company.campaign.lifecycle.LifecycleIdGenerator;
import com.company.campaign.lifecycle.LifecycleStore;
import com.company.campaign.lifecycle.ManifestValidationPort;
import com.company.campaign.lifecycle.PublishPreparationService;
import com.company.campaign.lifecycle.ReferencePreparationLookupPort;
import com.company.campaign.lifecycle.PublishService;
import com.company.campaign.lifecycle.PublishedForkService;
import com.company.campaign.lifecycle.SavedCampaignDraft;
import com.company.campaign.lifecycle.SegmentReadinessLookupPort;
import com.company.campaign.security.authorization.AuthorizationService;
import com.company.campaign.security.context.CampaignPermission;
import com.company.campaign.security.context.TrustedExecutionContext;
import com.company.campaign.segment.validation.testsupport.SegmentValidationFixtures;
import com.company.campaign.shared.canonical.ContentHash;
import com.company.campaign.builder.testsupport.BuilderFixtures;

import java.time.Clock;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;

/**
 * Shared, deterministic fixtures for the T17 publish-phase tests.
 *
 * <p>Every Plan is a genuine T16 product compiled through the accepted shared Segment authoring integration
 * and every Draft is a genuine product of a real host Apply, so the tests exercise the whole lifecycle
 * boundary rather than a stand-in. Readiness is always supplied through the host-only lookup port, never on a
 * command.
 */
public final class PublishTestFixtures {

    private static final SegmentAuthoringIntegration INTEGRATION =
            new SegmentAuthoringIntegration(new CampaignCompiler(SegmentValidationFixtures.allowAll()));

    private PublishTestFixtures() {
    }

    // ------------------------------------------------------------------ plans

    /** A genuine rule-based Segment Plan compiled under exactly {@code ACTIVE} production readiness. */
    public static CampaignPlan activeSegmentPlan() {
        return compiled(SegmentAuthoringFixtures.active());
    }

    /** The same Plan compiled honestly under {@code EXPERIMENTAL} development readiness. */
    public static CampaignPlan experimentalSegmentPlan() {
        return compiled(SegmentAuthoringFixtures.experimental());
    }

    /**
     * The same Plan compiled under a caption of {@code EVALUATION_REQUIRED} readiness.
     *
     * <p>A gated input moved since the approved decision, so the compile is honest development evidence and
     * the production guard must refuse it.
     */
    public static CampaignPlan evaluationRequiredSegmentPlan() {
        return compiled(new CapabilityReadiness(
                SegmentAuthoringVersions.CAPABILITY_CODE, CapabilityReadiness.EVALUATION_REQUIRED, false,
                List.of("GATED_INPUT_CHANGED"), SegmentAuthoringFixtures.APPROVED_DECISION_HASH,
                SegmentAuthoringFixtures.GATE_INPUTS_HASH));
    }

    private static CampaignPlan compiled(CapabilityReadiness readiness) {
        return ((SegmentAuthoringResult.Compiled) INTEGRATION.author(
                SegmentAuthoringIntegrationFixtures.createRequest(
                        SegmentAuthoringIntegrationFixtures.visual(
                                SegmentAuthoringIntegrationFixtures.proof()),
                        readiness))).plan();
    }

    /** The same genuine Plan re-derived with a populated risk summary, which the T15 compiler leaves empty. */
    public static CampaignPlan withRisks(CampaignPlan plan, List<String> riskCodes) {
        return PlanRiskFixtures.withRisks(plan, riskCodes);
    }

    /**
     * A genuine EDIT Plan of the saved Draft's own Segment component.
     *
     * <p>It is used to move the Draft on between a preparation and its confirmation, so the preparation's
     * frozen revision is genuinely stale rather than merely asserted to be.
     */
    public static CampaignPlan editPlan(SavedCampaignDraft base, CapabilityReadiness readiness) {
        TrustedCurrentCampaign current = new TrustedCurrentCampaign(
                base.tenantId(), base.programId(), base.campaignId(), base.campaignRevision(),
                base.draftIdentity(), base.forkBase(), base.manifest().components());
        String logicalId = base.manifest().components().get(0).logicalId();
        return ((SegmentAuthoringResult.Compiled) INTEGRATION.author(
                SegmentAuthoringIntegrationFixtures.editRequest(
                        SegmentAuthoringIntegrationFixtures.visual(
                                SegmentAuthoringIntegrationFixtures.proof()),
                        logicalId, current, readiness))).plan();
    }

    public static InMemoryCampaignPlanStore planStore(CampaignPlan... plans) {
        InMemoryCampaignPlanStore store = new InMemoryCampaignPlanStore();
        for (CampaignPlan plan : plans) {
            store.store(plan);
        }
        return store;
    }

    // ------------------------------------------------------------------ readiness

    /** The exact host-owned {@code ACTIVE}, AI-ready projection the accepted T14 gate would publish. */
    public static SegmentReadinessProjection activeProjection() {
        return SegmentReadinessProjection.of(
                SegmentAuthoringVersions.CAPABILITY_CODE, CapabilityState.ACTIVE, true, List.of(),
                SegmentAuthoringFixtures.APPROVED_DECISION_HASH, SegmentAuthoringFixtures.GATE_INPUTS_HASH);
    }

    /** A projection of the same capability whose state is {@code EXPERIMENTAL}. */
    public static SegmentReadinessProjection experimentalProjection() {
        return SegmentReadinessProjection.of(
                SegmentAuthoringVersions.CAPABILITY_CODE, CapabilityState.EXPERIMENTAL, false,
                List.of("APPROVAL_MISSING"), null, SegmentAuthoringFixtures.GATE_INPUTS_HASH);
    }

    /** A projection of the same capability whose gated inputs moved since the approved decision. */
    public static SegmentReadinessProjection evaluationRequiredProjection() {
        return SegmentReadinessProjection.of(
                SegmentAuthoringVersions.CAPABILITY_CODE, CapabilityState.EVALUATION_REQUIRED, false,
                List.of("GATED_INPUT_CHANGED"), SegmentAuthoringFixtures.APPROVED_DECISION_HASH,
                SegmentAuthoringFixtures.GATE_INPUTS_HASH);
    }

    /** An {@code ACTIVE} projection resting on a superseded approved decision. */
    public static SegmentReadinessProjection supersededDecisionProjection() {
        return SegmentReadinessProjection.of(
                SegmentAuthoringVersions.CAPABILITY_CODE, CapabilityState.ACTIVE, true, List.of(),
                otherHash("other-approved-decision"), SegmentAuthoringFixtures.GATE_INPUTS_HASH);
    }

    /** An {@code ACTIVE} projection whose gated inputs hash is stale. */
    public static SegmentReadinessProjection staleInputsProjection() {
        return SegmentReadinessProjection.of(
                SegmentAuthoringVersions.CAPABILITY_CODE, CapabilityState.ACTIVE, true, List.of(),
                SegmentAuthoringFixtures.APPROVED_DECISION_HASH, otherHash("other-gate-inputs"));
    }

    public static SegmentReadinessLookupPort readiness(SegmentReadinessProjection projection) {
        return capabilityId -> Optional.ofNullable(projection);
    }

    /** A host that holds no readiness evidence at all: publishing must fail closed. */
    public static SegmentReadinessLookupPort missingReadiness() {
        return capabilityId -> Optional.empty();
    }

    /** A host whose readiness store cannot answer: publishing must fail closed. */
    public static SegmentReadinessLookupPort failingReadiness() {
        return capabilityId -> {
            throw new IllegalStateException("readiness unavailable");
        };
    }

    private static ContentHash otherHash(String seed) {
        return ContentHash.sha256(seed.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------------ services

    /**
     * The Apply boundary over this scenario's store.
     *
     * <p>It shares the scenario's one identity source, because a host allocates every lifecycle identity from
     * one place: two boundaries with private counters would hand out the same identifier twice.
     */
    public static ApplyService applyService(
            CampaignPlanStore plans, LifecycleStore store, LifecycleIdGenerator ids) {
        return new ApplyService(
                plans, authorization(), LifecycleTestFixtures.compatible(),
                LifecycleTestFixtures.valid(), store, ids, LifecycleTestFixtures.CLOCK);
    }

    public static PublishPreparationService prepareService(
            CampaignPlanStore plans,
            LifecycleStore store,
            SegmentReadinessLookupPort readiness,
            Clock clock,
            LifecycleIdGenerator ids) {
        return prepareService(
                plans, store, readiness, LifecycleTestFixtures.valid(), clock, ids);
    }

    /** A prepare boundary with an explicit final-Manifest validation port. */
    public static PublishPreparationService prepareService(
            CampaignPlanStore plans,
            LifecycleStore store,
            SegmentReadinessLookupPort readiness,
            ManifestValidationPort validation,
            Clock clock,
            LifecycleIdGenerator ids) {
        return prepareService(plans, store, readiness, validation, clock, ids, null, null);
    }

    /**
     * A prepare boundary wired for the dedicated Existing Audience Reference activation.
     *
     * <p>Both reference ports are host-only and may be absent: a reference-free Plan never consults them, and
     * a reference-bearing Plan handed none fails closed rather than inheriting a default approval.
     */
    public static PublishPreparationService prepareService(
            CampaignPlanStore plans,
            LifecycleStore store,
            SegmentReadinessLookupPort readiness,
            ManifestValidationPort validation,
            Clock clock,
            LifecycleIdGenerator ids,
            ReferencePreparationLookupPort referenceFacts,
            ReferenceApprovalLookupPort referenceApprovals) {
        return new PublishPreparationService(
                plans, authorization(), LifecycleTestFixtures.compatible(), validation, readiness, store, ids,
                clock, referenceFacts, referenceApprovals);
    }

    public static PublishService publishService(
            CampaignPlanStore plans,
            LifecycleStore store,
            SegmentReadinessLookupPort readiness,
            FrozenCompatibilityPort compatibility,
            ManifestValidationPort validation,
            Clock clock,
            LifecycleIdGenerator ids) {
        return publishService(plans, store, readiness, compatibility, validation, clock, ids, null, null);
    }

    /**
     * A publish boundary wired for the dedicated Existing Audience Reference recheck.
     *
     * <p>Both reference ports are host-only and may be absent: a reference-free Plan never consults them, and
     * a reference-bearing preparation handed none fails closed rather than inheriting a default approval.
     */
    public static PublishService publishService(
            CampaignPlanStore plans,
            LifecycleStore store,
            SegmentReadinessLookupPort readiness,
            FrozenCompatibilityPort compatibility,
            ManifestValidationPort validation,
            Clock clock,
            LifecycleIdGenerator ids,
            ReferencePreparationLookupPort referenceFacts,
            ReferenceApprovalLookupPort referenceApprovals) {
        return new PublishService(
                plans, authorization(), compatibility, validation, readiness, store, ids, clock,
                referenceFacts, referenceApprovals);
    }

    public static PublishedForkService forkService(LifecycleStore store, LifecycleIdGenerator ids) {
        return new PublishedForkService(
                authorization(), store, ids, LifecycleTestFixtures.CLOCK);
    }

    public static AuthorizationService authorization() {
        return SegmentValidationFixtures.allowAll();
    }

    /** The trusted context every publish fixture plan is compiled under. */
    public static TrustedExecutionContext context() {
        return SegmentAuthoringFixtures.context();
    }

    /** The same membership minus {@code campaign.publish}: the caller may edit but may not publish. */
    public static TrustedExecutionContext contextWithoutPublish() {
        return SegmentValidationFixtures.context(
                EnumSet.complementOf(EnumSet.of(CampaignPermission.CAMPAIGN_PUBLISH)));
    }

    /** The same membership minus {@code campaign.edit}: the caller may publish but may not fork. */
    public static TrustedExecutionContext contextWithoutEdit() {
        return SegmentValidationFixtures.context(
                EnumSet.complementOf(EnumSet.of(CampaignPermission.CAMPAIGN_EDIT)));
    }

    /** A different verified user inside the same tenant and program. */
    public static TrustedExecutionContext otherUserContext() {
        return BuilderFixtures.contextWithUser("user-002");
    }

    /** A different tenant with every permission. */
    public static TrustedExecutionContext otherTenantContext() {
        return SegmentAuthoringFixtures.foreignOwnerContext();
    }

    /** The same caller under a different DataScope: the frozen evidence no longer describes what it sees. */
    public static TrustedExecutionContext otherDataScopeContext() {
        return BuilderFixtures.context();
    }
}