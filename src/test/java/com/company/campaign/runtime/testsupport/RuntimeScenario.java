package com.company.campaign.runtime.testsupport;

import com.company.campaign.builder.compiler.CampaignPlan;
import com.company.campaign.fullpublish.FullPublishedDefinition;
import com.company.campaign.fullpublish.FullPublishScenario;
import com.company.campaign.fullpublish.FullPublishTestFixtures;
import com.company.campaign.fullpublish.JourneyPublishSourcePort;
import com.company.campaign.fullpublish.ScheduleDueCommand;
import com.company.campaign.fullpublish.ScheduleDueResult;
import com.company.campaign.fullpublish.ScheduleRegistrationOutboxEvent;
import com.company.campaign.fullpublish.ScheduledOccurrenceRecord;
import com.company.campaign.runtime.AudienceMaterializationOutcome;
import com.company.campaign.runtime.AudienceMaterializationPort;
import com.company.campaign.runtime.AudienceMaterializationResult;
import com.company.campaign.runtime.InMemoryRuntimeAuditSink;
import com.company.campaign.runtime.InMemoryRuntimeStore;
import com.company.campaign.runtime.MaterializationResult;
import com.company.campaign.runtime.RuntimeAuditRecord;
import com.company.campaign.runtime.RuntimeAuditSink;
import com.company.campaign.runtime.RuntimeCampaignStatePort;
import com.company.campaign.runtime.RuntimeFactPort;
import com.company.campaign.runtime.RuntimeIngressService;
import com.company.campaign.runtime.RuntimeMaterializationService;
import com.company.campaign.runtime.RuntimeRetryPolicy;
import com.company.campaign.runtime.RuntimeWorker;
import com.company.campaign.runtime.T21RuntimeWorkSource;
import com.company.campaign.security.context.TrustedExecutionContext;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * One deterministic T22 scenario: a genuine T21 full publication with a governance limit, the accepted T21
 * committed occurrence work it planned, and the Runtime boundaries over one synchronized runtime store.
 *
 * <p>The T17/T21 chain is the real one — nothing here fabricates a Draft, a base preparation or a committed
 * occurrence — so the Runtime boundaries are exercised against exactly the work a host would hold. The
 * publication's own clock is the accepted fixture clock, while the Runtime delivery clock is deliberately later,
 * so a test can prove that a delayed delivery still evaluates at the occurrence's nominal instant.
 */
public final class RuntimeScenario {

    /** The instant every Runtime delivery in this scenario happens at. */
    public static final Instant DELIVERED_AT = Instant.parse("2026-10-05T00:00:00Z");

    /** The instant the accepted T21 publication and scheduling fixtures run at. */
    public static final Instant PUBLISHED_AT = Instant.parse("2026-10-03T00:05:00Z");

    /** The frozen freshness policy every Runtime boundary in this scenario is constructed with. */
    public static final Duration FRESHNESS = Duration.ofHours(1);

    /** The accepted T21 scenario the full publication and its scheduling run through. */
    public final FullPublishScenario publication;

    /** The runtime audit collaborator every runtime commit records through. */
    public final RuntimeAuditSink auditSink;

    /** The one synchronized runtime store every runtime read and write crosses. */
    public final InMemoryRuntimeStore store;

    /** The instant Runtime deliveries happen at. */
    public final Clock clock;

    /** The host-owned Campaign runtime/schedule projection every worker rechecks. */
    public RuntimeCampaignStatePort campaignStates;

    /** The host-owned fact port every Condition node reads through. */
    public RuntimeFactPort facts;

    private FullPublishedDefinition definition;
    private AudienceMaterializationPort audience;

    private RuntimeScenario(
            CampaignPlan plan,
            Function<RuntimeAuditSink, InMemoryRuntimeStore> storeFactory,
            RuntimeAuditSink auditSink,
            Clock clock,
            JourneyPublishSourcePort journeySource) {
        this.publication = FullPublishScenario.of(Objects.requireNonNull(plan, "plan"));
        this.publication.withSource(Objects.requireNonNull(journeySource, "journeySource"));
        this.auditSink = auditSink;
        this.store = storeFactory.apply(auditSink);
        this.clock = clock;
        // The default answer is an honest empty audience for the requesting tenant, not a fabricated one: a
        // test that forgets to script its audience gets a successful zero-audience materialization.
        this.audience = new ScriptedAudiencePort(request -> AudienceMaterializationResult.of(
                AudienceMaterializationOutcome.COMPLETE, request.tenantId(), 0, List.of(), request.asOf(),
                request.asOf(), request.freshnessMaxAgeSeconds(), ScriptedAudiencePort.EVIDENCE));
        // The default activation projection is the accepted T21 schedule state, and the default fact answer is
        // honestly unknown rather than a fabricated value.
        this.campaignStates = new T21CampaignStatePort(publication);
        this.facts = new ScriptedFactPort();
    }

    /** A scenario over one Plan, with an in-memory audit sink and the standard delivery clock. */
    public static RuntimeScenario of(CampaignPlan plan) {
        return new RuntimeScenario(
                plan, InMemoryRuntimeStore::new, new InMemoryRuntimeAuditSink(),
                Clock.fixed(DELIVERED_AT, ZoneOffset.UTC), FullPublishTestFixtures.source(plan));
    }

    /** A scenario over one Plan whose frozen Journey topology and pack index the caller supplies. */
    public static RuntimeScenario withJourney(
            CampaignPlan plan, JourneyPublishSourcePort journeySource, Clock clock) {
        return new RuntimeScenario(
                plan, InMemoryRuntimeStore::new, new InMemoryRuntimeAuditSink(),
                Objects.requireNonNull(clock, "clock"), journeySource);
    }

    /** A scenario whose runtime store is the supplied one, so a test can fail one of its commits. */
    public static RuntimeScenario withStore(
            CampaignPlan plan, Function<RuntimeAuditSink, InMemoryRuntimeStore> storeFactory) {
        return new RuntimeScenario(
                plan, storeFactory, new InMemoryRuntimeAuditSink(), Clock.fixed(DELIVERED_AT, ZoneOffset.UTC),
                FullPublishTestFixtures.source(plan));
    }

    /** A scenario whose runtime store and audit collaborator are both supplied. */
    public static RuntimeScenario withStoreAndSink(
            CampaignPlan plan,
            Function<RuntimeAuditSink, InMemoryRuntimeStore> storeFactory,
            RuntimeAuditSink sink) {
        return new RuntimeScenario(
                plan, storeFactory, Objects.requireNonNull(sink, "sink"),
                Clock.fixed(DELIVERED_AT, ZoneOffset.UTC), FullPublishTestFixtures.source(plan));
    }

    /** A scenario whose audit collaborator always fails, so rollback can be proved. */
    public static RuntimeScenario withFailingAudit(CampaignPlan plan) {
        return new RuntimeScenario(
                plan, InMemoryRuntimeStore::new, new FailingAuditSink(),
                Clock.fixed(DELIVERED_AT, ZoneOffset.UTC), FullPublishTestFixtures.source(plan));
    }

    // ------------------------------------------------------------------ host state

    /** Publishes the full definition and returns it. */
    public FullPublishedDefinition publish() {
        publication.publish();
        this.definition = publication.store.findLatestFullDefinition(publication.campaignId()).orElseThrow();
        return definition;
    }

    /** The frozen full definition this scenario published. */
    public FullPublishedDefinition definition() {
        if (definition == null) {
            throw new IllegalStateException("the scenario was not published");
        }
        return definition;
    }

    public String tenantId() {
        return definition().tenantId();
    }

    public String campaignId() {
        return publication.campaignId();
    }

    public TrustedExecutionContext context() {
        return publication.context;
    }

    /** The one accepted scheduling-registration outbox event this publication emitted. */
    public ScheduleRegistrationOutboxEvent registrationEvent() {
        List<ScheduleRegistrationOutboxEvent> events = publication.store.findRegistrationWork();
        if (events.size() != 1) {
            throw new IllegalStateException("expected exactly one registration event, found " + events.size());
        }
        return events.get(0);
    }

    /** Plans and commits the due occurrences of the Campaign's active schedule at the given instant. */
    public ScheduleDueResult planDue(Instant asOf) {
        return publication.operationService().planDue(
                new ScheduleDueCommand(campaignId(), publication.activeSchedule().stateRevision(), asOf),
                context());
    }

    /** Every committed occurrence of this Campaign, in deterministic order. */
    public List<ScheduledOccurrenceRecord> committedOccurrences() {
        return publication.store.findOccurrenceWork(campaignId());
    }

    /** The committed occurrence under one execution key. */
    public ScheduledOccurrenceRecord occurrence(String executionKey) {
        return publication.store.findOccurrenceWork(campaignId(), executionKey).orElseThrow();
    }

    /** Every runtime Outbox/Audit record the store retained for this tenant. */
    public List<RuntimeAuditRecord> audits() {
        return store.findAudits(tenantId());
    }

    // ------------------------------------------------------------------ boundaries

    /** The Runtime ingress boundary over this scenario's store and the accepted T21 work. */
    public RuntimeIngressService ingress() {
        T21RuntimeWorkSource source = new T21RuntimeWorkSource(publication.store);
        return new RuntimeIngressService(source, source, store, clock);
    }

    /** The Runtime materialization boundary over this scenario's store, with the frozen freshness policy. */
    public RuntimeMaterializationService materialization() {
        return new RuntimeMaterializationService(
                new T21RuntimeWorkSource(publication.store), audience, store, clock, FRESHNESS);
    }

    /** Publishes if necessary and delivers the first due occurrence, returning its stable execution key. */
    public String bootstrap() {
        if (definition == null) {
            publish();
        }
        String executionKey = planDue(PUBLISHED_AT).dueExecutionKeys().get(0);
        ingress().deliverOccurrence(campaignId(), executionKey);
        return executionKey;
    }

    /** Materializes one Execution over an exact member set observed at its own frozen nominal instant. */
    public MaterializationResult materialize(String executionKey, List<String> members) {
        java.time.Instant nominal = occurrence(executionKey).nominalInstant();
        withAudience(new ScriptedAudiencePort(ScriptedAudiencePort.complete(
                tenantId(), members, nominal, nominal.minusSeconds(60))));
        return materialization().materialize(tenantId(), executionKey);
    }

    /** The lease duration every worker this scenario creates takes. */
    public static final java.time.Duration LEASE = java.time.Duration.ofSeconds(30);

    /** The bounded fact timeout every worker this scenario creates declares. */
    public static final long FACT_TIMEOUT_MILLIS = 500L;

    /** The claim scan bound every worker this scenario creates uses. */
    public static final int CLAIM_SCAN = 256;

    /** The leased Runtime worker over this scenario's store, pins, activation projection and fact port. */
    public RuntimeWorker worker() {
        return worker(RuntimeRetryPolicy.defaults());
    }

    /** The leased Runtime worker over this scenario's store under one explicit retry policy. */
    public RuntimeWorker worker(RuntimeRetryPolicy retryPolicy) {
        T21RuntimeWorkSource source = new T21RuntimeWorkSource(publication.store);
        return new RuntimeWorker(
                source, campaignStates, facts, store, clock, retryPolicy, LEASE, FACT_TIMEOUT_MILLIS,
                CLAIM_SCAN);
    }

    /** Replaces the host-owned Campaign activation projection every worker rechecks. */
    public RuntimeScenario withCampaignState(RuntimeCampaignStatePort replaced) {
        this.campaignStates = Objects.requireNonNull(replaced, "replaced");
        return this;
    }

    /** Replaces the host-owned fact port every Condition node reads through. */
    public RuntimeScenario withFacts(RuntimeFactPort replaced) {
        this.facts = Objects.requireNonNull(replaced, "replaced");
        return this;
    }

    /** The scripted fact port this scenario answers through by default. */
    public ScriptedFactPort scriptedFacts() {
        return (ScriptedFactPort) facts;
    }

    /** Replaces the audience port every materialization this scenario creates answers through. */
    public RuntimeScenario withAudience(AudienceMaterializationPort replaced) {
        this.audience = Objects.requireNonNull(replaced, "replaced");
        return this;
    }

    /** Replaces the audience answer with one scripted behaviour, so a test can inspect the request it got. */
    public RuntimeScenario withScriptedAudience(ScriptedAudiencePort.Behaviour behaviour) {
        return withAudience(new ScriptedAudiencePort(behaviour));
    }

    /** The audience port this scenario currently answers through. */
    public AudienceMaterializationPort audience() {
        return audience;
    }

    /** The scripted audience port a scenario uses by default, so a test can inspect the request it received. */
    public ScriptedAudiencePort scriptedAudience() {
        return (ScriptedAudiencePort) audience;
    }

    /** An audit collaborator that never accepts anything, so a transaction must roll back. */
    private static final class FailingAuditSink implements RuntimeAuditSink {

        @Override
        public void record(RuntimeAuditRecord record) {
            throw new IllegalStateException("scripted runtime audit sink unavailable");
        }
    }

    /** Redacted diagnostics: the scenario holds no owner value. */
    @Override
    public String toString() {
        return "RuntimeScenario[campaign=" + campaignId() + ']';
    }
}