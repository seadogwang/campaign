package com.company.campaign.fullpublish;

import com.company.campaign.builder.authoring.SegmentAuthoringVersions;
import com.company.campaign.builder.authoring.testsupport.SegmentAuthoringFixtures;
import com.company.campaign.builder.capability.composition.T19PackFacts;
import com.company.campaign.builder.capability.composition.T19PackIndex;
import com.company.campaign.builder.capability.flow.FlowEndPack;
import com.company.campaign.builder.capability.flow.FlowPackVersions;
import com.company.campaign.builder.capability.schedule.ScheduleBasicVersions;
import com.company.campaign.builder.compiler.CampaignPlan;
import com.company.campaign.builder.compiler.CapabilityReadiness;
import com.company.campaign.builder.compiler.CapabilityResolution;
import com.company.campaign.builder.compiler.CompileRequest;
import com.company.campaign.builder.compiler.CompositionSlot;
import com.company.campaign.builder.compiler.FrozenAuthoringPolicy;
import com.company.campaign.builder.compiler.FrozenCapabilityCatalog;
import com.company.campaign.builder.compiler.PlanPreviewCard;
import com.company.campaign.builder.compiler.ResolvedComponent;
import com.company.campaign.builder.compiler.RuntimeCompatibility;
import com.company.campaign.builder.proposal.CampaignProposalPatch;
import com.company.campaign.builder.testsupport.BuilderFixtures;
import com.company.campaign.builder.testsupport.FixtureCapability;
import com.company.campaign.journey.graph.JourneyBranchKind;
import com.company.campaign.journey.graph.JourneyTopology;
import com.company.campaign.journey.graph.JourneyTopologyEdge;
import com.company.campaign.lifecycle.SavedCampaignDraft;
import com.company.campaign.segment.validation.testsupport.SegmentValidationFixtures;
import com.company.campaign.shared.canonical.ContentHash;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Deterministic, value-safe T21 test fixtures.
 *
 * <p>It fabricates no production availability, Gate approval, retention answer or catalog mutation: it is a
 * contract fixture that builds one genuine T15 Plan containing an ACTIVE rule-based Segment projection, a
 * minimal Flow graph and exactly one bounded {@code schedule.basic} component, together with the frozen pack
 * index and topology the T20 compiler is decided against.
 *
 * <p>The Segment projection is emitted by a fixture capability that impersonates the accepted
 * {@code SEGMENT_PREVIEW} card shape exactly, so the accepted T17 activation guard accepts the Plan without any
 * production evidence being invented. The Flow and Schedule components are plain carrier components: their
 * payloads are the frozen T19 field names and the T21 compiler is what validates them.
 */
public final class FullPublishTestFixtures {

    /** The zone every fixture schedule declares. */
    public static final String ZONE = "Asia/Shanghai";

    /** The exact bounded full-publication risk codes a confirmation must acknowledge. */
    public static final List<String> FULL_RISKS = List.of("RUNTIME_UNAVAILABLE", "SCHEDULE_WORK_DEFERRED");

    private static final Instant PINNED_AT = Instant.parse("2026-10-03T00:00:00Z");

    private FullPublishTestFixtures() {
    }

    // ------------------------------------------------------------------ the catalog

    /**
     * The frozen catalog: a Segment fixture that projects one ACTIVE accepted card, plus the two Flow carriers
     * and the one Schedule carrier.
     */
    public static FrozenCapabilityCatalog catalog() {
        return BuilderFixtures.catalog(
                segmentCapability(),
                FixtureCapability.passThrough(FlowPackVersions.START, FlowPackVersions.CONTRACT_VERSION,
                        FlowPackVersions.SLOT),
                FixtureCapability.passThrough(FlowPackVersions.END, FlowPackVersions.CONTRACT_VERSION,
                        FlowPackVersions.SLOT),
                FixtureCapability.passThrough(ScheduleBasicVersions.CAPABILITY_CODE,
                        ScheduleBasicVersions.CONTRACT_VERSION, ScheduleBasicVersions.SLOT));
    }

    public static FrozenAuthoringPolicy policy() {
        return BuilderFixtures.policy();
    }

    /**
     * The Segment fixture contract: it passes the payload through unchanged and projects exactly one accepted
     * {@code SEGMENT_PREVIEW} card bound to the component the compiler just resolved, in ACTIVE readiness with
     * the exact Gate pins the accepted activation projection carries.
     */
    private static FixtureCapability segmentCapability() {
        return new FixtureCapability(
                SegmentAuthoringVersions.CAPABILITY_CODE,
                SegmentAuthoringVersions.CONTRACT_VERSION,
                new CompositionSlot(SegmentAuthoringVersions.SLOT_CODE, false, true),
                true,
                new RuntimeCompatibility(SegmentAuthoringVersions.RUNTIME_SCHEMA_VERSION, true, false),
                request -> new CapabilityResolution.Resolved(
                        request.payload(), List.of(), List.of(activeCard(request.logicalId())), List.of()));
    }

    /** One accepted, ACTIVE {@code SEGMENT_PREVIEW} card bound to the given component. */
    public static PlanPreviewCard activeCard(String logicalId) {
        return new PlanPreviewCard(
                PlanPreviewCard.SEGMENT_PREVIEW, logicalId, 42, List.of(), List.of(),
                "metadata-17", PINNED_AT, ZONE, PINNED_AT,
                hash("dsl"), hash("ast"), hash("plan"), hash("preview"), hash("metadata"), hash("skill"),
                CapabilityReadiness.ACTIVE, SegmentAuthoringFixtures.GATE_INPUTS_HASH,
                SegmentAuthoringFixtures.APPROVED_DECISION_HASH);
    }

    // ------------------------------------------------------------------ the plan

    /** A genuine T15 Plan with an ACTIVE Segment projection, a Start/End Flow graph and one schedule. */
    public static CampaignPlan plan(Map<String, Object> schedulePayload) {
        CampaignProposalPatch patch = BuilderFixtures.createProposal()
                .pins(catalog(), policy())
                .upsertNew(SegmentAuthoringVersions.CAPABILITY_CODE, "ref-segment", Map.of("carrier", "segment"))
                .upsertNew(FlowPackVersions.START, "ref-start", Map.of())
                .upsertNew(FlowPackVersions.END, "ref-end",
                        Map.of(FlowEndPack.TERMINAL_FIELD, FlowEndPack.TERMINAL_END))
                .upsertNew(ScheduleBasicVersions.CAPABILITY_CODE, "ref-schedule", schedulePayload)
                .build();
        return new com.company.campaign.builder.compiler.CampaignCompiler(
                        SegmentValidationFixtures.allowAll())
                .compile(CompileRequest.create(
                        patch, catalog(), policy(), BuilderFixtures.instants(), BuilderFixtures.context()));
    }

    /** An IMMEDIATE schedule Plan. */
    public static CampaignPlan immediatePlan() {
        return plan(Map.of(
                ScheduleBasicVersions.FIELD_FORM, "IMMEDIATE",
                ScheduleBasicVersions.FIELD_ZONE_ID, ZONE));
    }

    /** A {@code DAILY} schedule Plan over the given inclusive window. */
    public static CampaignPlan dailyPlan(String startDate, String endDate, String localTime) {
        return plan(Map.of(
                ScheduleBasicVersions.FIELD_FORM, "DAILY",
                ScheduleBasicVersions.FIELD_ZONE_ID, ZONE,
                ScheduleBasicVersions.FIELD_LOCAL_TIME, localTime,
                ScheduleBasicVersions.FIELD_START_DATE, startDate,
                ScheduleBasicVersions.FIELD_END_DATE, endDate));
    }

    /** A {@code ONCE} schedule Plan at the given canonical offset instant. */
    public static CampaignPlan oncePlan(String runAt) {
        return plan(Map.of(
                ScheduleBasicVersions.FIELD_FORM, "ONCE",
                ScheduleBasicVersions.FIELD_ZONE_ID, ZONE,
                ScheduleBasicVersions.FIELD_RUN_AT, runAt));
    }

    /**
     * A genuine EDIT Plan of the saved Draft's own Schedule component, used to move the Draft on between a full
     * preparation and its confirmation so the frozen revision is genuinely stale rather than merely asserted to
     * be.
     */
    public static CampaignPlan editSchedulePlan(
            SavedCampaignDraft draft, Map<String, Object> schedulePayload) {
        com.company.campaign.builder.compiler.TrustedCurrentCampaign current =
                new com.company.campaign.builder.compiler.TrustedCurrentCampaign(
                        draft.tenantId(), draft.programId(), draft.campaignId(), draft.campaignRevision(),
                        draft.draftIdentity(), draft.forkBase(), draft.manifest().components());
        String logicalId = draft.manifest().components().stream()
                .filter(component -> component.capabilityCode().equals(ScheduleBasicVersions.CAPABILITY_CODE))
                .findFirst()
                .orElseThrow()
                .logicalId();
        CampaignProposalPatch patch = BuilderFixtures.editProposal()
                .pins(catalog(), policy())
                .campaignId(draft.campaignId())
                .campaignRevision(draft.campaignRevision())
                .upsert(ScheduleBasicVersions.CAPABILITY_CODE, logicalId, schedulePayload)
                .build();
        return new com.company.campaign.builder.compiler.CampaignCompiler(
                        SegmentValidationFixtures.allowAll())
                .compile(CompileRequest.edit(
                        patch, current, catalog(), policy(), BuilderFixtures.instants(),
                        BuilderFixtures.context(draft.tenantId(), draft.programId())));
    }

    /** The one component of a Plan with the given capability code. */
    public static ResolvedComponent component(CampaignPlan plan, String capabilityCode) {
        List<ResolvedComponent> matches = plan.manifest().components().stream()
                .filter(component -> component.capabilityCode().equals(capabilityCode))
                .toList();
        if (matches.size() != 1) {
            throw new IllegalStateException("expected exactly one " + capabilityCode + " component");
        }
        return matches.get(0);
    }

    // ------------------------------------------------------------------ Journey source

    /** The frozen Journey topology of a fixture Plan: one DEFAULT edge from Start to End. */
    public static JourneyTopology topology(CampaignPlan plan) {
        return JourneyTopology.of(
                JourneyTopology.TOPOLOGY_FORMAT_VERSION,
                new JourneyTopologyEdge(
                        "edge-start-end",
                        component(plan, FlowPackVersions.START).logicalId(),
                        component(plan, FlowPackVersions.END).logicalId(),
                        JourneyBranchKind.DEFAULT));
    }

    /** The frozen T19 pack index the fixture Plan is decided against. */
    public static T19PackIndex index() {
        return T19PackIndex.of(
                FlowPackVersions.RUNTIME_SCHEMA_VERSION,
                facts(FlowPackVersions.START, FlowPackVersions.SLOT, true, true),
                facts(FlowPackVersions.END, FlowPackVersions.SLOT, true, true),
                facts(ScheduleBasicVersions.CAPABILITY_CODE, ScheduleBasicVersions.SLOT, true, true));
    }

    /** An index whose Schedule pack is disabled, for the fail-closed enablement test. */
    public static T19PackIndex indexWithDisabledSchedule() {
        return T19PackIndex.of(
                FlowPackVersions.RUNTIME_SCHEMA_VERSION,
                facts(FlowPackVersions.START, FlowPackVersions.SLOT, true, true),
                facts(FlowPackVersions.END, FlowPackVersions.SLOT, true, true),
                facts(ScheduleBasicVersions.CAPABILITY_CODE, ScheduleBasicVersions.SLOT, false, true));
    }

    /** An index whose Schedule pack declares another contract version, for the mismatch test. */
    public static T19PackIndex indexWithMovedScheduleContract() {
        return T19PackIndex.of(
                FlowPackVersions.RUNTIME_SCHEMA_VERSION,
                facts(FlowPackVersions.START, FlowPackVersions.SLOT, true, true),
                facts(FlowPackVersions.END, FlowPackVersions.SLOT, true, true),
                new T19PackFacts(ScheduleBasicVersions.CAPABILITY_CODE, "2.0", ScheduleBasicVersions.SLOT,
                        true, new RuntimeCompatibility(FlowPackVersions.RUNTIME_SCHEMA_VERSION, true, false)));
    }

    private static T19PackFacts facts(
            String capabilityCode, CompositionSlot slot, boolean enabled, boolean previewSupported) {
        return new T19PackFacts(capabilityCode, "1.0", slot, enabled,
                new RuntimeCompatibility(FlowPackVersions.RUNTIME_SCHEMA_VERSION, previewSupported, false));
    }

    /** A host that resolves exactly this Plan's frozen Journey source. */
    public static JourneyPublishSourcePort source(CampaignPlan plan) {
        JourneyPublishSource source = new JourneyPublishSource(topology(plan), index());
        return (campaignId, draftId, draftRevision, manifestHash) -> Optional.of(source);
    }

    /** A host that resolves another pack index than the Plan was compiled against. */
    public static JourneyPublishSourcePort sourceWith(CampaignPlan plan, T19PackIndex index) {
        JourneyPublishSource source = new JourneyPublishSource(topology(plan), index);
        return (campaignId, draftId, draftRevision, manifestHash) -> Optional.of(source);
    }

    /** A host that resolves an explicit topology, so a test can prove a Journey drift is refused. */
    public static JourneyPublishSourcePort sourceWithTopology(CampaignPlan plan, JourneyTopology topology) {
        JourneyPublishSource source = new JourneyPublishSource(topology, index());
        return (campaignId, draftId, draftRevision, manifestHash) -> Optional.of(source);
    }

    /** A host that owns no Journey source at all: full preparation must fail closed. */
    public static JourneyPublishSourcePort missingSource() {
        return (campaignId, draftId, draftRevision, manifestHash) -> Optional.empty();
    }

    /** A host whose Journey source store cannot answer: full preparation must fail closed. */
    public static JourneyPublishSourcePort failingSource() {
        return (campaignId, draftId, draftRevision, manifestHash) -> {
            throw new IllegalStateException("journey source unavailable");
        };
    }

    // ------------------------------------------------------------------ runtime availability

    /** A host that pins the runtime available for the exact runtime schema version. */
    public static HostRuntimeAvailabilityPort availableRuntime() {
        return runtimeSchemaVersion -> Optional.of(RuntimeAvailabilityPin.of(
                runtimeSchemaVersion, RuntimeAvailabilityState.AVAILABLE, hash("runtime-evidence")));
    }

    /** A host that owns no runtime evidence at all: full preparation must fail closed. */
    public static HostRuntimeAvailabilityPort missingRuntime() {
        return runtimeSchemaVersion -> Optional.empty();
    }

    /** A host that truthfully reports the runtime unavailable: full preparation must fail closed. */
    public static HostRuntimeAvailabilityPort unavailableRuntime() {
        return runtimeSchemaVersion -> Optional.of(RuntimeAvailabilityPin.of(
                runtimeSchemaVersion, RuntimeAvailabilityState.UNAVAILABLE, hash("runtime-evidence")));
    }

    /** A host whose runtime-availability store cannot answer: full preparation must fail closed. */
    public static HostRuntimeAvailabilityPort failingRuntime() {
        return runtimeSchemaVersion -> {
            throw new IllegalStateException("runtime availability unavailable");
        };
    }

    /** A host that pins a different runtime-availability evidence than a preparation froze. */
    public static HostRuntimeAvailabilityPort otherRuntime() {
        return runtimeSchemaVersion -> Optional.of(RuntimeAvailabilityPin.of(
                runtimeSchemaVersion, RuntimeAvailabilityState.AVAILABLE, hash("other-runtime-evidence")));
    }

    // ------------------------------------------------------------------ helpers

    /** A canonical content hash of a fixture seed. */
    public static ContentHash hash(String seed) {
        return ContentHash.sha256(seed.getBytes(StandardCharsets.UTF_8));
    }
}