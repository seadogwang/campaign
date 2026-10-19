package com.company.campaign.runtime.testsupport;

import com.company.campaign.builder.authoring.SegmentAuthoringVersions;
import com.company.campaign.builder.capability.flow.FlowEndPack;
import com.company.campaign.builder.capability.flow.FlowPackVersions;
import com.company.campaign.builder.capability.governance.GovernanceLimitPack;
import com.company.campaign.builder.capability.governance.GovernanceLimitVersions;
import com.company.campaign.builder.capability.schedule.ScheduleBasicVersions;
import com.company.campaign.builder.compiler.CampaignCompiler;
import com.company.campaign.builder.compiler.CampaignPlan;
import com.company.campaign.builder.compiler.CompileRequest;
import com.company.campaign.builder.compiler.CompositionSlot;
import com.company.campaign.builder.compiler.FrozenAuthoringPolicy;
import com.company.campaign.builder.compiler.FrozenCapabilityCatalog;
import com.company.campaign.builder.compiler.RuntimeCompatibility;
import com.company.campaign.builder.proposal.CampaignProposalPatch;
import com.company.campaign.builder.testsupport.BuilderFixtures;
import com.company.campaign.builder.testsupport.FixtureCapability;
import com.company.campaign.fullpublish.FullPublishTestFixtures;
import com.company.campaign.segment.validation.testsupport.SegmentValidationFixtures;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Deterministic T22 Runtime test Plans.
 *
 * <p>They extend the accepted T21 fixture shape with exactly one bounded {@code governance.limit/1.0}
 * declaration, because a T22 materialization resolves its recipient limit from that exact frozen component and
 * from nothing else. Nothing here fabricates production readiness, Gate approval, host evidence or catalog
 * mutation: the Plan is a genuine T15 compile under the fixture policy, and the T21 full publication freezes it
 * for real.
 */
public final class RuntimeTestPlans {

    /** The zone every fixture schedule declares. */
    public static final String ZONE = "Asia/Shanghai";

    /** The bounded recipient limit the default fixture Plan declares. */
    public static final long DEFAULT_MAX_RECIPIENTS = 1_000L;

    private RuntimeTestPlans() {
    }

    /** The frozen catalog: the accepted T21 shape plus the real {@code governance.limit} pack at 1.0. */
    public static FrozenCapabilityCatalog catalog() {
        return BuilderFixtures.catalog(
                segmentCapability(),
                FixtureCapability.passThrough(
                        FlowPackVersions.START, FlowPackVersions.CONTRACT_VERSION, FlowPackVersions.SLOT),
                FixtureCapability.passThrough(
                        FlowPackVersions.END, FlowPackVersions.CONTRACT_VERSION, FlowPackVersions.SLOT),
                FixtureCapability.passThrough(
                        ScheduleBasicVersions.CAPABILITY_CODE, ScheduleBasicVersions.CONTRACT_VERSION,
                        ScheduleBasicVersions.SLOT),
                new GovernanceLimitPack());
    }

    public static FrozenAuthoringPolicy policy() {
        return BuilderFixtures.policy();
    }

    /** An {@code IMMEDIATE} plan with the default recipient limit. */
    public static CampaignPlan immediatePlan() {
        return immediatePlan(DEFAULT_MAX_RECIPIENTS);
    }

    /** An {@code IMMEDIATE} plan declaring one exact recipient limit. */
    public static CampaignPlan immediatePlan(long maxRecipients) {
        Map<String, Object> schedule = new LinkedHashMap<>();
        schedule.put(ScheduleBasicVersions.FIELD_FORM, "IMMEDIATE");
        schedule.put(ScheduleBasicVersions.FIELD_ZONE_ID, ZONE);
        return plan(schedule, maxRecipients);
    }

    /** A {@code ONCE} plan at one canonical offset instant. */
    public static CampaignPlan oncePlan(String runAt) {
        return oncePlan(runAt, DEFAULT_MAX_RECIPIENTS);
    }

    /** A {@code ONCE} plan at one canonical offset instant, declaring one exact recipient limit. */
    public static CampaignPlan oncePlan(String runAt, long maxRecipients) {
        Map<String, Object> schedule = new LinkedHashMap<>();
        schedule.put(ScheduleBasicVersions.FIELD_FORM, "ONCE");
        schedule.put(ScheduleBasicVersions.FIELD_ZONE_ID, ZONE);
        schedule.put(ScheduleBasicVersions.FIELD_RUN_AT, runAt);
        return plan(schedule, maxRecipients);
    }

    /** A {@code DAILY} plan over one inclusive window. */
    public static CampaignPlan dailyPlan(String startDate, String endDate, String localTime) {
        return dailyPlan(startDate, endDate, localTime, DEFAULT_MAX_RECIPIENTS);
    }

    /** A {@code DAILY} plan over one inclusive window, declaring one exact recipient limit. */
    public static CampaignPlan dailyPlan(
            String startDate, String endDate, String localTime, long maxRecipients) {
        Map<String, Object> schedule = new LinkedHashMap<>();
        schedule.put(ScheduleBasicVersions.FIELD_FORM, "DAILY");
        schedule.put(ScheduleBasicVersions.FIELD_ZONE_ID, ZONE);
        schedule.put(ScheduleBasicVersions.FIELD_LOCAL_TIME, localTime);
        schedule.put(ScheduleBasicVersions.FIELD_START_DATE, startDate);
        schedule.put(ScheduleBasicVersions.FIELD_END_DATE, endDate);
        return plan(schedule, maxRecipients);
    }

    /** A genuine T15 Plan with an ACTIVE Segment projection, a Start/End graph, one Schedule and one limit. */
    public static CampaignPlan plan(Map<String, Object> schedulePayload, long maxRecipients) {
        CampaignProposalPatch patch = BuilderFixtures.createProposal()
                .pins(catalog(), policy())
                .upsertNew(SegmentAuthoringVersions.CAPABILITY_CODE, "ref-segment", Map.of("carrier", "segment"))
                .upsertNew(FlowPackVersions.START, "ref-start", Map.of())
                .upsertNew(FlowPackVersions.END, "ref-end",
                        Map.of(FlowEndPack.TERMINAL_FIELD, FlowEndPack.TERMINAL_END))
                .upsertNew(ScheduleBasicVersions.CAPABILITY_CODE, "ref-schedule", schedulePayload)
                .upsertNew(GovernanceLimitVersions.CAPABILITY_CODE, "ref-governance",
                        governancePayload(maxRecipients))
                .build();
        return new CampaignCompiler(SegmentValidationFixtures.allowAll())
                .compile(CompileRequest.create(
                        patch, catalog(), policy(), BuilderFixtures.instants(), BuilderFixtures.context()));
    }

    /**
     * One exactly-shaped {@code governance.limit/1.0} declaration.
     *
     * <p>A limit above the frozen policy bound is unrepresentable, so a test that needs a malformed limit builds
     * its own frozen material rather than asserting on a Plan that could never compile.
     */
    public static Map<String, Object> governancePayload(long maxRecipients) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put(GovernanceLimitVersions.FIELD_MAX_RECIPIENTS, maxRecipients);
        payload.put(GovernanceLimitVersions.FIELD_MAX_REWARD_GRANTS, 100L);
        payload.put(GovernanceLimitVersions.FIELD_MAX_REWARD_FACE_VALUE, new BigDecimal("25.00"));
        payload.put(GovernanceLimitVersions.FIELD_CURRENCY, "CNY");
        return payload;
    }

    private static FixtureCapability segmentCapability() {
        return new FixtureCapability(
                SegmentAuthoringVersions.CAPABILITY_CODE,
                SegmentAuthoringVersions.CONTRACT_VERSION,
                new CompositionSlot(SegmentAuthoringVersions.SLOT_CODE, false, true),
                true,
                new RuntimeCompatibility(SegmentAuthoringVersions.RUNTIME_SCHEMA_VERSION, true, false),
                request -> new com.company.campaign.builder.compiler.CapabilityResolution.Resolved(
                        request.payload(), java.util.List.of(),
                        java.util.List.of(FullPublishTestFixtures.activeCard(request.logicalId())),
                        java.util.List.of()));
    }
}