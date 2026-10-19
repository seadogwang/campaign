package com.company.campaign.fullpublish;

import com.company.campaign.builder.compiler.CampaignPlan;
import com.company.campaign.builder.compiler.CampaignPlanStore;
import com.company.campaign.journey.graph.JourneyTopology;
import com.company.campaign.lifecycle.FrozenCompatibilityPort;
import com.company.campaign.lifecycle.LifecycleAuditSink;
import com.company.campaign.lifecycle.LifecycleIdGenerator;
import com.company.campaign.lifecycle.LifecycleStore;
import com.company.campaign.lifecycle.ManifestValidationPort;
import com.company.campaign.lifecycle.PreparePublishResult;
import com.company.campaign.lifecycle.PublishPreparation;
import com.company.campaign.lifecycle.SavedCampaignDraft;
import com.company.campaign.lifecycle.SequentialLifecycleIdGenerator;
import com.company.campaign.lifecycle.SegmentReadinessLookupPort;
import com.company.campaign.lifecycle.testsupport.LifecycleTestFixtures;
import com.company.campaign.lifecycle.testsupport.PublishScenario;
import com.company.campaign.lifecycle.testsupport.PublishTestFixtures;
import com.company.campaign.security.context.TrustedExecutionContext;

import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.UnaryOperator;

/**
 * One deterministic end-to-end T21 scenario: a genuine Plan, a real T17 Apply that saved its Draft, a real T17
 * Prepare Publish that produced the accepted base preparation, and the T21 full prepare, full publish and
 * scheduling boundaries over one shared synchronized store.
 *
 * <p>The accepted T17 chain is the real one — nothing here fabricates a Draft or a base preparation — so the
 * T21 boundaries are exercised against exactly the evidence a host would hold. A test that must fail one
 * collaborator can supply its own accepted store or its own T21 store factory, so rollback is proved against
 * the real transaction rather than against a stand-in.
 */
public final class FullPublishScenario {

    public final TrustedExecutionContext context = PublishTestFixtures.context();

    public final CampaignPlan plan;

    /** The accepted T17 scenario whose Apply and Prepare produced the Draft and the base preparation. */
    public final PublishScenario base;

    /** The accepted T17 store the T21 transaction commits its base artifacts through. */
    public final LifecycleStore baseStore;

    /** The T21 store holding every full-publication and scheduling artifact. */
    public final InMemoryFullPublicationStore store;

    /** The accepted T17 preparation every full preparation extends. */
    public final PreparePublishResult basePreparation;

    private final LifecycleIdGenerator ids = new SequentialLifecycleIdGenerator();
    private final CampaignPlanStore plans;
    private SegmentReadinessLookupPort readiness;
    private final LifecycleAuditSink auditSink;

    private JourneyPublishSourcePort source;
    private HostRuntimeAvailabilityPort runtimeAvailability = FullPublishTestFixtures.availableRuntime();
    private Clock clock = LifecycleTestFixtures.CLOCK;
    private PrepareFullPublishResult lastPreparation;

    private FullPublishScenario(
            CampaignPlan plan,
            LifecycleAuditSink auditSink,
            UnaryOperator<LifecycleStore> baseStoreWrapper,
            Function<PublishScenario, InMemoryFullPublicationStore> storeFactory) {
        this.plan = Objects.requireNonNull(plan, "plan");
        this.base = auditSink == null
                ? PublishScenario.of(plan)
                : PublishScenario.of(plan, auditSink);
        this.plans = base.plans;
        this.readiness = PublishTestFixtures.readiness(PublishTestFixtures.activeProjection());
        this.auditSink = base.sink;
        this.baseStore = baseStoreWrapper.apply(base.store);
        this.store = storeFactory == null
                ? new InMemoryFullPublicationStore(baseStore, base.sink)
                : storeFactory.apply(base);
        this.source = FullPublishTestFixtures.source(plan);
        this.basePreparation = base.prepare();
    }

    /** A scenario over an IMMEDIATE fixture Plan. */
    public static FullPublishScenario immediate() {
        return new FullPublishScenario(FullPublishTestFixtures.immediatePlan(), null, s -> s, null);
    }

    /** A scenario over a {@code DAILY} fixture Plan. */
    public static FullPublishScenario daily(String startDate, String endDate, String localTime) {
        return new FullPublishScenario(
                FullPublishTestFixtures.dailyPlan(startDate, endDate, localTime), null, s -> s, null);
    }

    /** A scenario over a {@code ONCE} fixture Plan. */
    public static FullPublishScenario once(String runAt) {
        return new FullPublishScenario(FullPublishTestFixtures.oncePlan(runAt), null, s -> s, null);
    }

    /** A scenario over an explicit fixture Plan. */
    public static FullPublishScenario of(CampaignPlan plan) {
        return new FullPublishScenario(plan, null, s -> s, null);
    }

    /** A scenario whose accepted store writes its audit events through the supplied sink. */
    public static FullPublishScenario withAuditSink(CampaignPlan plan, LifecycleAuditSink auditSink) {
        return new FullPublishScenario(plan, auditSink, s -> s, null);
    }

    /** A scenario whose accepted T17 commit runs through the supplied store decorator. */
    public static FullPublishScenario withBaseStore(CampaignPlan plan, UnaryOperator<LifecycleStore> wrapper) {
        return new FullPublishScenario(plan, null, wrapper, null);
    }

    /** A scenario whose T21 store is the supplied one, so a test can fail one of its writes. */
    public static FullPublishScenario withStore(
            CampaignPlan plan, LifecycleAuditSink auditSink,
            Function<PublishScenario, InMemoryFullPublicationStore> storeFactory) {
        return new FullPublishScenario(plan, auditSink, s -> s, storeFactory);
    }

    // ------------------------------------------------------------------ host state

    /** Replaces the Journey source the T21 boundaries resolve. */
    public FullPublishScenario withSource(JourneyPublishSourcePort replaced) {
        this.source = replaced;
        return this;
    }

    /** Replaces the frozen Journey topology the T21 boundaries resolve. */
    public FullPublishScenario withTopology(JourneyTopology topology) {
        this.source = FullPublishTestFixtures.sourceWithTopology(plan, topology);
        return this;
    }

    /** Replaces the Segment readiness evidence the T21 boundaries load. */
    public FullPublishScenario withReadiness(SegmentReadinessLookupPort replaced) {
        this.readiness = replaced;
        return this;
    }

    /** Replaces the Segment readiness evidence with one exact host-owned projection. */
    public FullPublishScenario withReadiness(
            com.company.campaign.evaluation.segment.gate.SegmentReadinessProjection replaced) {
        return withReadiness(PublishTestFixtures.readiness(replaced));
    }

    /** Replaces the runtime-availability answer the T21 boundaries receive. */
    public FullPublishScenario withRuntime(HostRuntimeAvailabilityPort replaced) {
        this.runtimeAvailability = replaced;
        return this;
    }

    /** Replaces the instant both the accepted and the T21 clocks read. */
    public FullPublishScenario withClock(Clock replaced) {
        this.clock = replaced;
        this.base.withClock(replaced);
        return this;
    }

    public Clock clock() {
        return clock;
    }

    public String campaignId() {
        return base.campaignId();
    }

    public SavedCampaignDraft draft() {
        return base.draft;
    }

    /** The accepted base preparation the scenario froze. */
    public PublishPreparation frozenBasePreparation() {
        return baseStore.findPreparation(basePreparation.preparationId()).orElseThrow();
    }

    /** The audit sink every boundary in this scenario writes through. */
    public LifecycleAuditSink auditSink() {
        return auditSink;
    }

    // ------------------------------------------------------------------ the boundaries

    public FullPublishPreparationService prepareService() {
        return new FullPublishPreparationService(
                plans, PublishTestFixtures.authorization(), compatibility(), validation(), readiness, baseStore,
                store, source, runtimeAvailability, ids, clock);
    }

    public FullPublishService publishService() {
        return new FullPublishService(
                plans, PublishTestFixtures.authorization(), compatibility(), validation(), readiness, baseStore,
                store, source, runtimeAvailability, ids, clock, null, null);
    }

    /**
     * The boundary wired with two explicit store references, so a test can prove a split wiring is refused.
     *
     * <p>A host that passes the scenario's own combined store twice gets a working boundary; a host that passes
     * two distinct references gets an {@link IllegalArgumentException} before any command can run.
     */
    public FullPublishService publishService(
            FullPublicationStore publicationStore, ScheduleStore scheduleStore) {
        return new FullPublishService(
                plans, PublishTestFixtures.authorization(), compatibility(), validation(), readiness, baseStore,
                publicationStore, scheduleStore, source, runtimeAvailability, ids, clock, null, null);
    }

    public ScheduleOperationService operationService() {
        return new ScheduleOperationService(store, store, clock);
    }

    public FrozenCompatibilityPort compatibility() {
        return LifecycleTestFixtures.compatible();
    }

    public ManifestValidationPort validation() {
        return LifecycleTestFixtures.valid();
    }

    // ------------------------------------------------------------------ commands

    /** The Prepare Full Publish command for the scenario's own base preparation. */
    public PrepareFullPublishCommand prepareCommand() {
        return new PrepareFullPublishCommand(
                basePreparation.preparationId(), basePreparation.preparationHash());
    }

    public PrepareFullPublishResult prepare() {
        this.lastPreparation = prepareService().prepare(prepareCommand(), context);
        return lastPreparation;
    }

    /** The last full preparation this scenario produced, or {@code null} when it produced none. */
    public PrepareFullPublishResult lastPreparation() {
        return lastPreparation;
    }

    /** The full publication command for one preparation, with a key and an exact confirmation set. */
    public FullPublishCommand command(
            PrepareFullPublishResult preparation, String idempotencyKey, List<String> confirmations) {
        return new FullPublishCommand(
                preparation.preparationId(), preparation.preparationHash(), idempotencyKey, confirmations);
    }

    public FullPublishResult confirm(
            PrepareFullPublishResult preparation, String idempotencyKey, List<String> confirmations) {
        return publishService().confirm(command(preparation, idempotencyKey, confirmations), context);
    }

    /** Prepares and confirms in one step with the bounded fixture key and the exact risk set. */
    public FullPublishResult publish() {
        return confirm(prepare(), "idem-key-1", FullPublishTestFixtures.FULL_RISKS);
    }

    /** The active schedule state projection of the scenario's Campaign, if any. */
    public CampaignScheduleState activeSchedule() {
        return store.findScheduleState(campaignId()).orElseThrow();
    }
}