package com.company.campaign.builder.assembly;

import com.company.campaign.builder.assembly.testsupport.CampaignAssemblyFixtures;
import com.company.campaign.builder.capability.governance.GovernanceLimitPack;
import com.company.campaign.builder.capability.governance.GovernanceLimitVersions;
import com.company.campaign.builder.capability.schedule.ScheduleBasicPack;
import com.company.campaign.builder.compiler.CapabilityPayload;
import com.company.campaign.builder.compiler.CapabilityRequest;
import com.company.campaign.builder.compiler.CapabilityResolution;
import com.company.campaign.builder.proposal.ProposalIdentity;
import com.company.campaign.contracts.Disposition;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T30 requirement 3: the Schedule and Governance Tools drive the accepted T19 packs, publish only bounded safe
 * results, stage only their final validation artifact and stay deterministic.
 */
class ScheduleGovernanceToolFlowTest {

    private static CampaignToolRunContext runContext() {
        return CampaignAssemblyFixtures.runContext();
    }

    private static CapabilityPayload scheduleNormalizedByTheAcceptedPack() {
        CampaignToolRunContext run = runContext();
        CapabilityResolution resolution = new ScheduleBasicPack().resolve(new CapabilityRequest(
                CampaignAssemblyFixtures.SCHEDULE_CLIENT_REF, CampaignAssemblyFixtures.scheduleDeclaration(),
                run.capabilityCatalog(), run.authoringPolicy(), run.trustedContext()));
        return ((CapabilityResolution.Resolved) resolution).value();
    }

    private static CapabilityPayload governanceNormalizedByTheAcceptedPack() {
        CampaignToolRunContext run = runContext();
        CapabilityResolution resolution = new GovernanceLimitPack().resolve(new CapabilityRequest(
                CampaignAssemblyFixtures.GOVERNANCE_CLIENT_REF, CampaignAssemblyFixtures.governanceDeclaration(),
                run.capabilityCatalog(), run.authoringPolicy(), run.trustedContext()));
        return ((CapabilityResolution.Resolved) resolution).value();
    }

    @Test
    void theScheduleGovernanceAndJourneyToolsAreImplementedAndOnlyFinalResultsAreStageable() {
        CampaignToolCatalog catalog = CampaignToolCatalog.standard();
        assertThat(catalog.catalogVersion()).isEqualTo("campaign-tool-catalog-4.0");
        assertThat(catalog.catalogHash()).isEqualTo(catalog.recomputeCatalogHash());

        for (String toolId : List.of(
                CampaignToolIds.SCHEDULE_NORMALIZE, CampaignToolIds.SCHEDULE_VALIDATE,
                CampaignToolIds.GOVERNANCE_EXPOSURE_CALCULATE, CampaignToolIds.GOVERNANCE_VALIDATE,
                CampaignToolIds.JOURNEY_NODE_CATALOG, CampaignToolIds.JOURNEY_COMPOSE,
                CampaignToolIds.JOURNEY_VALIDATE, CampaignToolIds.JOURNEY_SIMULATE)) {
            CampaignToolDescriptor descriptor = catalog.descriptor(toolId);
            assertThat(descriptor).as(toolId).isNotNull();
            assertThat(descriptor.available()).as(toolId).isTrue();
            assertThat(descriptor.modelCallable()).as(toolId).isTrue();
            assertThat(descriptor.toolVersion()).as(toolId).isEqualTo("1.0");
        }
        for (String toolId : CampaignToolIds.planned()) {
            assertThat(catalog.descriptor(toolId).available()).as(toolId).isFalse();
        }
        assertThat(catalog.descriptor(CampaignToolIds.SCHEDULE_OCCURRENCES_PREVIEW).available()).isFalse();

        // Only the final validation result of each Skill may become a staged component; in particular the
        // Journey node catalog, the composition and the pure simulation are never stageable.
        assertThat(CampaignToolIds.isStageableArtifact(
                CampaignSkillIds.SCHEDULE_BASIC, CampaignToolIds.SCHEDULE_VALIDATE)).isTrue();
        assertThat(CampaignToolIds.isStageableArtifact(
                CampaignSkillIds.SCHEDULE_BASIC, CampaignToolIds.SCHEDULE_NORMALIZE)).isFalse();
        assertThat(CampaignToolIds.isStageableArtifact(
                CampaignSkillIds.GOVERNANCE_LIMIT, CampaignToolIds.GOVERNANCE_VALIDATE)).isTrue();
        assertThat(CampaignToolIds.isStageableArtifact(
                CampaignSkillIds.GOVERNANCE_LIMIT, CampaignToolIds.GOVERNANCE_EXPOSURE_CALCULATE)).isFalse();
        assertThat(CampaignToolIds.isStageableArtifact(
                CampaignSkillIds.JOURNEY_FLOW, CampaignToolIds.JOURNEY_VALIDATE)).isTrue();
        assertThat(CampaignToolIds.isStageableArtifact(
                CampaignSkillIds.JOURNEY_FLOW, CampaignToolIds.JOURNEY_NODE_CATALOG)).isFalse();
        assertThat(CampaignToolIds.isStageableArtifact(
                CampaignSkillIds.JOURNEY_FLOW, CampaignToolIds.JOURNEY_COMPOSE)).isFalse();
        assertThat(CampaignToolIds.isStageableArtifact(
                CampaignSkillIds.JOURNEY_FLOW, CampaignToolIds.JOURNEY_SIMULATE)).isFalse();
    }

    @Test
    void aScheduleHandlerResolvesThroughTheAcceptedPackAndPublishesOnlyBoundedFacts() {
        CampaignToolAdmission admission = CampaignAssemblyFixtures.scheduleValidationAdmission();

        assertThat(admission.resolved()).isTrue();
        assertThat(admission.toolId()).isEqualTo(CampaignToolIds.SCHEDULE_VALIDATE);
        assertThat(admission.result().outcomeCode()).isEqualTo("SCHEDULE_VALIDATED");
        // The accepted schedule.basic pack resolves no catalog reference, so the published outcome names none.
        assertThat(admission.result().stableReferences()).isEmpty();

        CampaignToolArtifact artifact = admission.claim(
                admission.callId(), admission.toolId(), CampaignToolCatalog.standard().catalogHash());
        assertThat(artifact).isInstanceOf(CampaignToolArtifact.ScheduleValidation.class);
        assertThat(((CampaignToolArtifact.ScheduleValidation) artifact).normalized())
                .isEqualTo(scheduleNormalizedByTheAcceptedPack());

        // No occurrence, execution key, run-at instant, member fact or rejected value leaves the handler.
        String rendered = admission.envelope().toString();
        assertThat(rendered).doesNotContain("Asia/Shanghai");
        assertThat(rendered).doesNotContain("09:00");
        assertThat(rendered).doesNotContain("2026-11-01");
        assertThat(rendered).doesNotContain("fields");
    }

    @Test
    void aGovernanceHandlerResolvesThroughTheAcceptedPackAndPublishesOnlyBoundedFacts() {
        CampaignToolAdmission admission = CampaignAssemblyFixtures.governanceValidationAdmission();

        assertThat(admission.resolved()).isTrue();
        assertThat(admission.toolId()).isEqualTo(CampaignToolIds.GOVERNANCE_VALIDATE);
        assertThat(admission.result().outcomeCode()).isEqualTo("GOVERNANCE_LIMITS_VALIDATED");
        assertThat(admission.result().stableReferences()).isEmpty();

        CampaignToolArtifact artifact = admission.claim(
                admission.callId(), admission.toolId(), CampaignToolCatalog.standard().catalogHash());
        assertThat(artifact).isInstanceOf(CampaignToolArtifact.GovernanceValidation.class);
        assertThat(((CampaignToolArtifact.GovernanceValidation) artifact).normalized())
                .isEqualTo(governanceNormalizedByTheAcceptedPack());

        String rendered = admission.envelope().toString();
        assertThat(rendered).doesNotContain("CNY");
        assertThat(rendered).doesNotContain("25.00");
        assertThat(rendered).doesNotContain("fields");
    }

    @Test
    void theGovernanceExposureToolIsAuthoringOnlyArithmeticOverTheDeclaredLimits() {
        CampaignToolInvoker invoker = CampaignAssemblyFixtures.governanceInvoker();
        CampaignToolAdmission admission = invoker.invoke(CampaignAssemblyFixtures.call(
                "call-1", new CampaignToolRequest.GovernanceExposureCalculate(
                        CampaignAssemblyFixtures.GOVERNANCE_CLIENT_REF,
                        CampaignAssemblyFixtures.governanceDeclaration())));

        assertThat(admission.resolved()).isTrue();
        CampaignToolArtifact artifact = admission.claim(
                admission.callId(), admission.toolId(), CampaignToolCatalog.standard().catalogHash());
        CampaignToolArtifact.GovernanceExposure exposure =
                (CampaignToolArtifact.GovernanceExposure) artifact;
        assertThat(exposure.maxRewardGrants()).isEqualTo(100L);
        assertThat(exposure.maxRewardFaceValue()).isEqualByComparingTo("25.00");
        assertThat(exposure.declaredMaximumExposure()).isEqualByComparingTo("2500.00");
    }

    @Test
    void aDeclarationTheAcceptedPackRefusesAsksForInputWithoutEchoingIt() {
        CapabilityPayload contradictory = CapabilityPayload.of(Map.of(
                GovernanceLimitVersions.FIELD_MAX_RECIPIENTS, 1_000,
                GovernanceLimitVersions.FIELD_MAX_REWARD_GRANTS, 100,
                GovernanceLimitVersions.FIELD_MAX_REWARD_FACE_VALUE, 25,
                GovernanceLimitVersions.FIELD_CURRENCY, "cny"));
        CampaignToolInvoker invoker = CampaignAssemblyFixtures.governanceInvoker();
        invoker.invoke(CampaignAssemblyFixtures.call("call-1",
                new CampaignToolRequest.GovernanceExposureCalculate(
                        CampaignAssemblyFixtures.GOVERNANCE_CLIENT_REF, contradictory)));
        CampaignToolAdmission admission = invoker.invoke(CampaignAssemblyFixtures.call("call-2",
                new CampaignToolRequest.GovernanceValidate(
                        CampaignAssemblyFixtures.GOVERNANCE_CLIENT_REF, contradictory)));

        assertThat(admission.disposition()).isEqualTo(Disposition.INVALID);
        assertThat(admission.failure().code())
                .isEqualTo(CampaignAssemblyErrorCodes.TOOL_DECLARATION_INVALID);
        assertThat(admission.failure().message()).doesNotContain("cny");
        assertThat(admission.hasArtifact()).isFalse();
    }

    @Test
    void onlyTheFinalValidationArtifactOfEitherNewSkillCanBeStaged() {
        CampaignArtifactAdapter adapter = CampaignAssemblyFixtures.adapter();

        // A normalization or an exposure calculation is a partial result and is never eligible.
        CampaignToolInvoker schedule = CampaignAssemblyFixtures.scheduleInvoker();
        CampaignToolAdmission normalization = schedule.invoke(CampaignAssemblyFixtures.call("call-1",
                new CampaignToolRequest.ScheduleNormalize(
                        CampaignAssemblyFixtures.SCHEDULE_CLIENT_REF,
                        CampaignAssemblyFixtures.scheduleDeclaration())));
        assertThat(normalization.resolved()).isTrue();
        assertThat(adapter.schedule(normalization, ProposalIdentity.ofClientRef("campaign-schedule")))
                .isEmpty();

        CampaignToolInvoker governance = CampaignAssemblyFixtures.governanceInvoker();
        CampaignToolAdmission calculation = governance.invoke(CampaignAssemblyFixtures.call("call-1",
                new CampaignToolRequest.GovernanceExposureCalculate(
                        CampaignAssemblyFixtures.GOVERNANCE_CLIENT_REF,
                        CampaignAssemblyFixtures.governanceDeclaration())));
        assertThat(calculation.resolved()).isTrue();
        assertThat(adapter.governance(calculation, ProposalIdentity.ofClientRef("campaign-governance")))
                .isEmpty();

        // The final validation result of each Skill is eligible and carries no catalog reference.
        CampaignSkillArtifact stagedSchedule = adapter
                .schedule(CampaignAssemblyFixtures.scheduleValidationAdmission(),
                        ProposalIdentity.ofClientRef("campaign-schedule"))
                .orElseThrow();
        assertThat(stagedSchedule.capabilityCode()).isEqualTo("schedule.basic");
        assertThat(stagedSchedule.skillId()).isEqualTo("schedule.basic");
        assertThat(stagedSchedule.skillVersion()).isEqualTo(CampaignSkillIds.CURRENT_VERSION);
        assertThat(stagedSchedule.toolId()).isEqualTo(CampaignToolIds.SCHEDULE_VALIDATE);
        assertThat(stagedSchedule.toolVersion()).isEqualTo("1.0");
        assertThat(stagedSchedule.toolCatalogHash())
                .isEqualTo(CampaignToolCatalog.standard().catalogHash());
        assertThat(stagedSchedule.conversationId()).isEqualTo(CampaignAssemblyFixtures.CONVERSATION);
        assertThat(stagedSchedule.runId()).isEqualTo(CampaignAssemblyFixtures.RUN);
        assertThat(stagedSchedule.references()).isEmpty();

        CampaignSkillArtifact stagedGovernance = adapter
                .governance(CampaignAssemblyFixtures.governanceValidationAdmission(),
                        ProposalIdentity.ofClientRef("campaign-governance"))
                .orElseThrow();
        assertThat(stagedGovernance.capabilityCode()).isEqualTo("governance.limit");
        assertThat(stagedGovernance.references()).isEmpty();
    }

    @Test
    void wrongSkillOutOfOrderDuplicateOversizedAndMalformedCallsFailBeforeAnyHandler() {
        CampaignToolCatalog catalog = CampaignToolCatalog.standard();
        CampaignToolRunContext run = runContext();

        CampaignToolInvoker schedule = new CampaignToolInvoker(catalog, CampaignSkillIds.SCHEDULE_BASIC,
                StandardCampaignToolHandlers.all(), run);
        CampaignToolAdmission wrongSkill = schedule.invoke(CampaignAssemblyFixtures.call("call-1",
                new CampaignToolRequest.GovernanceValidate(
                        CampaignAssemblyFixtures.GOVERNANCE_CLIENT_REF,
                        CampaignAssemblyFixtures.governanceDeclaration())));
        assertThat(wrongSkill.failure().code()).isEqualTo(CampaignAssemblyErrorCodes.TOOL_WRONG_SKILL);
        assertThat(schedule.callCount()).isZero();

        CampaignToolInvoker outOfOrder = new CampaignToolInvoker(catalog, CampaignSkillIds.SCHEDULE_BASIC,
                StandardCampaignToolHandlers.all(), run);
        CampaignToolAdmission validateFirst = outOfOrder.invoke(CampaignAssemblyFixtures.call("call-1",
                new CampaignToolRequest.ScheduleValidate(
                        CampaignAssemblyFixtures.SCHEDULE_CLIENT_REF,
                        CampaignAssemblyFixtures.scheduleDeclaration())));
        assertThat(validateFirst.failure().code()).isEqualTo(CampaignAssemblyErrorCodes.TOOL_OUT_OF_ORDER);
        assertThat(outOfOrder.callCount()).isZero();

        CampaignToolInvoker duplicate = new CampaignToolInvoker(catalog, CampaignSkillIds.SCHEDULE_BASIC,
                StandardCampaignToolHandlers.all(), run);
        duplicate.invoke(CampaignAssemblyFixtures.call("call-1", new CampaignToolRequest.ScheduleNormalize(
                CampaignAssemblyFixtures.SCHEDULE_CLIENT_REF, CampaignAssemblyFixtures.scheduleDeclaration())));
        CampaignToolAdmission repeated = duplicate.invoke(CampaignAssemblyFixtures.call("call-2",
                new CampaignToolRequest.ScheduleNormalize(
                        CampaignAssemblyFixtures.SCHEDULE_CLIENT_REF,
                        CampaignAssemblyFixtures.scheduleDeclaration())));
        assertThat(repeated.failure().code()).isEqualTo(CampaignAssemblyErrorCodes.TOOL_DUPLICATE_CALL);
        assertThat(duplicate.callCount()).isEqualTo(1);

        CampaignToolInvoker oversized = new CampaignToolInvoker(catalog, CampaignSkillIds.GOVERNANCE_LIMIT,
                StandardCampaignToolHandlers.all(), run);
        CapabilityPayload huge = CapabilityPayload.of(Map.of(
                GovernanceLimitVersions.FIELD_MAX_RECIPIENTS, 1_000,
                GovernanceLimitVersions.FIELD_MAX_REWARD_GRANTS, 100,
                GovernanceLimitVersions.FIELD_MAX_REWARD_FACE_VALUE, 25,
                GovernanceLimitVersions.FIELD_CURRENCY, "x".repeat(20_000)));
        CampaignToolAdmission tooBig = oversized.invoke(CampaignAssemblyFixtures.call("call-1",
                new CampaignToolRequest.GovernanceExposureCalculate(
                        CampaignAssemblyFixtures.GOVERNANCE_CLIENT_REF, huge)));
        assertThat(tooBig.failure().code())
                .isEqualTo(CampaignAssemblyErrorCodes.TOOL_INPUT_LIMIT_EXCEEDED);
        assertThat(oversized.callCount()).isZero();

        // A Tool another Skill owns is refused before any handler, for the Journey Skill too.
        CampaignToolInvoker journey = new CampaignToolInvoker(catalog, CampaignSkillIds.JOURNEY_FLOW,
                StandardCampaignToolHandlers.all(), run);
        CampaignToolAdmission journeyCall = journey.invoke(CampaignAssemblyFixtures.call("call-1",
                CampaignToolIds.JOURNEY_VALIDATE, "1.0",
                new CampaignToolRequest.ScheduleValidate(
                        CampaignAssemblyFixtures.SCHEDULE_CLIENT_REF,
                        CampaignAssemblyFixtures.scheduleDeclaration())));
        assertThat(journeyCall.disposition()).isEqualTo(Disposition.INVALID);
        assertThat(journeyCall.failure().code()).isEqualTo(CampaignAssemblyErrorCodes.TOOL_RESULT_INCOHERENT);
        assertThat(journey.callCount()).isZero();

        CampaignToolAdmission wrongSkillForJourney = journey.invoke(CampaignAssemblyFixtures.call("call-2",
                new CampaignToolRequest.ScheduleValidate(
                        CampaignAssemblyFixtures.SCHEDULE_CLIENT_REF,
                        CampaignAssemblyFixtures.scheduleDeclaration())));
        assertThat(wrongSkillForJourney.failure().code())
                .isEqualTo(CampaignAssemblyErrorCodes.TOOL_WRONG_SKILL);
        assertThat(journey.callCount()).isZero();
    }

    @Test
    void repeatedIdenticalScheduleAndGovernanceInputsProduceByteIdenticalArtifacts() {
        CampaignSkillArtifact scheduleFirst = CampaignAssemblyFixtures.adapter()
                .schedule(CampaignAssemblyFixtures.scheduleValidationAdmission(),
                        ProposalIdentity.ofClientRef("campaign-schedule"))
                .orElseThrow();
        CampaignSkillArtifact scheduleSecond = CampaignAssemblyFixtures.adapter()
                .schedule(CampaignAssemblyFixtures.scheduleValidationAdmission(),
                        ProposalIdentity.ofClientRef("campaign-schedule"))
                .orElseThrow();
        assertThat(scheduleFirst.artifactHash()).isEqualTo(scheduleSecond.artifactHash());

        CampaignSkillArtifact governanceFirst = CampaignAssemblyFixtures.adapter()
                .governance(CampaignAssemblyFixtures.governanceValidationAdmission(),
                        ProposalIdentity.ofClientRef("campaign-governance"))
                .orElseThrow();
        CampaignSkillArtifact governanceSecond = CampaignAssemblyFixtures.adapter()
                .governance(CampaignAssemblyFixtures.governanceValidationAdmission(),
                        ProposalIdentity.ofClientRef("campaign-governance"))
                .orElseThrow();
        assertThat(governanceFirst.artifactHash()).isEqualTo(governanceSecond.artifactHash());

        CampaignAssemblyWorkspace first =
                new CampaignAssemblyWorkspace(CampaignAssemblyFixtures.createContext());
        assertThat(first.accept(scheduleFirst, 0).accepted()).isTrue();
        assertThat(first.accept(governanceFirst, 1).accepted()).isTrue();

        CampaignAssemblyWorkspace second =
                new CampaignAssemblyWorkspace(CampaignAssemblyFixtures.createContext());
        assertThat(second.accept(governanceSecond, 0).accepted()).isTrue();
        assertThat(second.accept(scheduleSecond, 1).accepted()).isTrue();

        // Acceptance order does not matter: the emitted Proposal is canonically ordered and byte-identical.
        assertThat(first.patch().proposalHash()).isEqualTo(second.patch().proposalHash());
        assertThat(first.patch().operations())
                .extracting(operation -> operation.target().value())
                .containsExactly("campaign-governance", "campaign-schedule");
    }

    @Test
    void anAbsentPackEvidenceFailsClosedWithoutInventingAnArtifact() {
        CampaignToolRunContext withoutPacks = new CampaignToolRunContext() {
            @Override
            public com.company.campaign.builder.compiler.FrozenCapabilityCatalog capabilityCatalog() {
                return runContext().capabilityCatalog();
            }

            @Override
            public com.company.campaign.builder.compiler.FrozenAuthoringPolicy authoringPolicy() {
                return runContext().authoringPolicy();
            }

            @Override
            public com.company.campaign.security.context.TrustedExecutionContext trustedContext() {
                return runContext().trustedContext();
            }
        };
        CampaignToolInvoker invoker = new CampaignToolInvoker(
                CampaignToolCatalog.standard(), CampaignSkillIds.SCHEDULE_BASIC,
                StandardCampaignToolHandlers.all(), withoutPacks);
        invoker.invoke(CampaignAssemblyFixtures.call("call-1", new CampaignToolRequest.ScheduleNormalize(
                CampaignAssemblyFixtures.SCHEDULE_CLIENT_REF, CampaignAssemblyFixtures.scheduleDeclaration())));
        CampaignToolAdmission admission = invoker.invoke(CampaignAssemblyFixtures.call("call-2",
                new CampaignToolRequest.ScheduleValidate(
                        CampaignAssemblyFixtures.SCHEDULE_CLIENT_REF,
                        CampaignAssemblyFixtures.scheduleDeclaration())));

        assertThat(admission.failure().code())
                .isEqualTo(CampaignAssemblyErrorCodes.TOOL_EVIDENCE_MISSING);
        assertThat(admission.hasArtifact()).isFalse();
    }
}