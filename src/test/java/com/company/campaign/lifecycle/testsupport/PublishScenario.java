package com.company.campaign.lifecycle.testsupport;

import com.company.campaign.builder.compiler.CampaignPlan;
import com.company.campaign.builder.compiler.InMemoryCampaignPlanStore;
import com.company.campaign.evaluation.segment.gate.ReferenceApprovalLookupPort;
import com.company.campaign.evaluation.segment.gate.SegmentReadinessProjection;
import com.company.campaign.lifecycle.ApplyCommand;
import com.company.campaign.lifecycle.ApplyResult;
import com.company.campaign.lifecycle.ApplyService;
import com.company.campaign.lifecycle.InMemoryLifecycleAuditSink;
import com.company.campaign.lifecycle.InMemoryLifecycleStore;
import com.company.campaign.lifecycle.LifecycleAuditSink;
import com.company.campaign.lifecycle.LifecycleStore;
import com.company.campaign.lifecycle.PreparePublishCommand;
import com.company.campaign.lifecycle.ReferencePreparationLookupPort;
import com.company.campaign.lifecycle.PreparePublishResult;
import com.company.campaign.lifecycle.PublishCommand;
import com.company.campaign.lifecycle.PublishPreparationService;
import com.company.campaign.lifecycle.PublishResult;
import com.company.campaign.lifecycle.PublishService;
import com.company.campaign.lifecycle.SavedCampaignDraft;
import com.company.campaign.lifecycle.SegmentReadinessLookupPort;
import com.company.campaign.lifecycle.PublishedForkService;
import com.company.campaign.lifecycle.SequentialLifecycleIdGenerator;
import com.company.campaign.security.context.TrustedExecutionContext;
import com.company.campaign.segment.reference.InMemoryAudienceRetentionStore;

import java.time.Clock;
import java.util.List;
import java.util.Objects;

/**
 * One deterministic end-to-end publish scenario: a genuine Segment Plan, a real host Apply that saved it, and
 * the prepare, publish and fork boundaries over the same synchronized in-memory store.
 *
 * <p>It exists so a test states the state it wants — the readiness a host reports, the instant the clock
 * reads, whether the preparation was already consumed — instead of assembling the same chain again. Nothing
 * here fabricates a Draft or a preparation: both come from the real services under test.
 *
 * <p>The store is retention-wired, so a reference-bearing publication acquires its holds through the same
 * transaction that freezes the definition. A reference-free publication names no hold and never reaches the
 * retention port at all.
 */
public final class PublishScenario {

    /** The trusted context the fixture plan was compiled under. */
    public final TrustedExecutionContext context = PublishTestFixtures.context();

    public final CampaignPlan plan;
    public final InMemoryCampaignPlanStore plans;

    /** The sink the store actually writes through; identical to {@link #audit} unless a test injected one. */
    public final LifecycleAuditSink sink;

    /**
     * The scenario's own in-memory audit sink, used by the tests that assert on recorded events.
     *
     * <p>When a test injects its own {@link LifecycleAuditSink}, this is a separate empty in-memory sink and
     * {@link #sink} is the injected one.
     */
    public final InMemoryLifecycleAuditSink audit;

    /** The retention port the scenario's publication transaction acquires holds through. */
    public final InMemoryAudienceRetentionStore retention;

    public final InMemoryLifecycleStore store;
    public final SavedCampaignDraft draft;

    /** The one identity source every boundary in this scenario allocates from. */
    private final SequentialLifecycleIdGenerator ids = new SequentialLifecycleIdGenerator();

    private SegmentReadinessLookupPort readiness = PublishTestFixtures.readiness(
            PublishTestFixtures.activeProjection());
    private Clock clock = LifecycleTestFixtures.CLOCK;

    // The host reference ports are absent by default: a reference-free Plan never consults them, and a
    // reference-bearing Plan handed no ports must fail closed rather than inherit a default approval.
    private ReferencePreparationLookupPort referenceFacts;
    private ReferenceApprovalLookupPort referenceApprovals;

    private PublishScenario(CampaignPlan plan, LifecycleAuditSink auditSink) {
        this.plan = plan;
        this.sink = Objects.requireNonNull(auditSink, "auditSink");
        this.audit = auditSink instanceof InMemoryLifecycleAuditSink inMemory
                ? inMemory : new InMemoryLifecycleAuditSink();
        this.retention = InMemoryAudienceRetentionStore.retaining();
        this.plans = PublishTestFixtures.planStore(plan);
        this.store = new InMemoryLifecycleStore(this.sink, retention);
        ApplyResult applied = applyService()
                .apply(new ApplyCommand(plan.planId(), plan.planHash()), context);
        this.draft = store.findDraft(applied.campaignId()).orElseThrow();
    }

    /** A scenario over a genuine rule-based Segment Plan compiled under exactly {@code ACTIVE} readiness. */
    public static PublishScenario active() {
        return new PublishScenario(PublishTestFixtures.activeSegmentPlan(), new InMemoryLifecycleAuditSink());
    }

    public static PublishScenario of(CampaignPlan plan) {
        return new PublishScenario(plan, new InMemoryLifecycleAuditSink());
    }

    /** A scenario whose store writes its audit events through the supplied sink. */
    public static PublishScenario of(CampaignPlan plan, LifecycleAuditSink auditSink) {
        return new PublishScenario(plan, auditSink);
    }

    // ------------------------------------------------------------------ host state

    /** Replaces the readiness the host reports, and therefore what the production guard will see. */
    public PublishScenario withReadiness(SegmentReadinessProjection projection) {
        this.readiness = PublishTestFixtures.readiness(projection);
        return this;
    }

    public PublishScenario withReadiness(SegmentReadinessLookupPort port) {
        this.readiness = port;
        return this;
    }

    /** Replaces the instant the lifecycle clock reads. */
    public PublishScenario withClock(Clock replaced) {
        this.clock = replaced;
        return this;
    }

    /**
     * Wires the host-only reference ports this scenario's prepare and publish boundaries will ask.
     *
     * <p>A reference-free Draft never consults them, so a scenario that leaves them absent keeps exactly the
     * accepted reference-free behaviour. A reference-bearing Draft handed no ports fails closed.
     */
    public PublishScenario withReferencePorts(
            ReferencePreparationLookupPort facts, ReferenceApprovalLookupPort approvals) {
        this.referenceFacts = facts;
        this.referenceApprovals = approvals;
        return this;
    }

    // ------------------------------------------------------------------ the boundaries

    public ApplyService applyService() {
        return PublishTestFixtures.applyService(plans, store, ids);
    }

    public PublishPreparationService prepareService() {
        return PublishTestFixtures.prepareService(
                plans, store, readiness, LifecycleTestFixtures.valid(), clock, ids,
                referenceFacts, referenceApprovals);
    }

    public PublishService publishService() {
        return publishServiceAt(clock);
    }

    public PublishService publishServiceAt(Clock replaced) {
        return PublishTestFixtures.publishService(
                plans, store, readiness, LifecycleTestFixtures.compatible(),
                LifecycleTestFixtures.valid(), replaced, ids, referenceFacts, referenceApprovals);
    }

    public PublishedForkService forkService() {
        return PublishTestFixtures.forkService(store, ids);
    }

    /**
     * The same scenario's prepare boundary over a chosen store.
     *
     * <p>It shares the scenario's identity source, because a host allocates every lifecycle identity from one
     * place: a boundary over an interposing store must not hand out an identity the scenario already used.
     */
    public PublishPreparationService prepareServiceOver(LifecycleStore target) {
        return PublishTestFixtures.prepareService(
                plans, target, readiness, LifecycleTestFixtures.valid(), clock, ids,
                referenceFacts, referenceApprovals);
    }

    /** The same scenario's publish boundary over a chosen store. */
    public PublishService publishServiceOver(LifecycleStore target) {
        return PublishTestFixtures.publishService(
                plans, target, readiness, LifecycleTestFixtures.compatible(),
                LifecycleTestFixtures.valid(), clock, ids, referenceFacts, referenceApprovals);
    }

    /** The same scenario's fork boundary over a chosen store. */
    public PublishedForkService forkServiceOver(LifecycleStore target) {
        return PublishTestFixtures.forkService(target, ids);
    }

    public PreparePublishResult prepare() {
        return prepareService().prepare(prepareCommand(), context);
    }

    /** Confirms one preparation with an explicit key and confirmation list. */
    public PublishResult confirm(
            PreparePublishResult preparation, String idempotencyKey, List<String> confirmations) {
        return publishService().confirm(command(preparation, idempotencyKey, confirmations), context);
    }

    public PublishCommand command(
            PreparePublishResult preparation, String idempotencyKey, List<String> confirmations) {
        return new PublishCommand(
                preparation.preparationId(), preparation.preparationHash(), idempotencyKey, confirmations);
    }

    /** The command that prepares the Campaign's editable Draft as it currently stands. */
    public PreparePublishCommand prepareCommand() {
        SavedCampaignDraft current = store.findDraft(draft.campaignId()).orElseThrow();
        return new PreparePublishCommand(
                current.campaignId(), current.draftId(), current.draftRevision());
    }

    /** The Campaign identifier the scenario's Draft belongs to. */
    public String campaignId() {
        return draft.campaignId();
    }
}