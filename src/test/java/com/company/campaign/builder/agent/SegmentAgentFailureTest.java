package com.company.campaign.builder.agent;

import com.company.campaign.builder.agent.testsupport.AgentTestFixtures;
import com.company.campaign.builder.segment.testsupport.SliceTestFixtures;
import com.company.campaign.builder.tool.SegmentToolCallRecord;
import com.company.campaign.builder.tool.SegmentToolIds;
import com.company.campaign.contracts.Disposition;
import com.company.campaign.preview.PreviewBackendException;
import com.company.campaign.preview.testsupport.PreviewAuthorizationStub;
import com.company.campaign.preview.testsupport.StubPreviewBackend;
import com.company.campaign.segment.validation.SegmentValidationLimits;
import com.company.campaign.segment.validation.SegmentValidationPolicy;
import com.company.campaign.segment.validation.testsupport.SegmentValidationFixtures;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Requirement 7: unsupported, denied, invalid model output, provider failure, validation failure, planning
 * failure and Preview failure each terminate with the correct bounded disposition and code and no later Tool
 * call. A refused protected stage leaves one audit-safe trace entry and never lets a later stage run.
 */
class SegmentAgentFailureTest {

    private static SegmentAgentEnvironment standard(String json) {
        return AgentTestFixtures.environment(AgentTestFixtures.portReturning(json));
    }

    private static void assertNoArtifact(SegmentAgentResult result) {
        assertThat(result.previewCard()).isNull();
        assertThat(result.explanation()).isNull();
        assertThat(result.dslEditableHash()).isNull();
        assertThat(result.canonicalAstHash()).isNull();
        assertThat(result.planHash()).isNull();
        assertThat(result.previewHash()).isNull();
    }

    /** Every Tool identifier the run actually admitted, in order. */
    private static List<String> admitted(SegmentAgentResult result) {
        return result.toolTrace().stream()
                .filter(call -> call.disposition().isResolved())
                .map(SegmentToolCallRecord::toolId)
                .toList();
    }

    @Test
    void anUnsupportedRequirementTerminatesAsUnsupportedAfterDiscoveryOnly() {
        String json = SliceTestFixtures.enumTierJson()
                .replace("\"unsupportedRequirements\":[]", "\"unsupportedRequirements\":[\"RAW_SQL\"]");

        SegmentAgentResult result = AgentTestFixtures.run(standard(json), new SegmentAgentRequest("Gold会员"));

        assertThat(result.disposition()).isEqualTo(Disposition.UNSUPPORTED);
        assertThat(result.terminalState()).isEqualTo(SegmentAgentState.UNSUPPORTED);
        assertThat(result.failureCode()).isEqualTo(SegmentAgentErrorCodes.UNSUPPORTED_REQUIREMENT);
        assertThat(admitted(result)).containsExactly(SegmentToolIds.METADATA_DISCOVER);
        assertNoArtifact(result);
    }

    @Test
    void aProviderFailureTerminatesAsInvalidWithTheSkillProviderCode() {
        SegmentAgentResult result = AgentTestFixtures.run(
                AgentTestFixtures.environment(AgentTestFixtures.failingPort()), new SegmentAgentRequest("Gold会员"));

        assertThat(result.disposition()).isEqualTo(Disposition.INVALID);
        assertThat(result.failureCode()).isEqualTo(AgentTestFixtures.skill().failureCode("providerFailure"));
        assertThat(admitted(result)).containsExactly(SegmentToolIds.METADATA_DISCOVER);
        assertNoArtifact(result);
    }

    @Test
    void invalidModelOutputTerminatesAsInvalidWithTheSkillOutputCode() {
        SegmentAgentResult result = AgentTestFixtures.run(
                standard("this is prose, not one raw JSON object"), new SegmentAgentRequest("Gold会员"));

        assertThat(result.disposition()).isEqualTo(Disposition.INVALID);
        assertThat(result.failureCode()).isEqualTo(AgentTestFixtures.skill().failureCode("modelOutputFailure"));
        assertThat(admitted(result)).containsExactly(SegmentToolIds.METADATA_DISCOVER);
        assertNoArtifact(result);
    }

    @Test
    void aDeniedValidationAuthorizationTerminatesAsDeniedWithNoLaterToolCall() {
        // Discovery authorizes every permitted definition (five); the sixth Metadata decision is the genuine
        // T07 validation re-authorization, so validation is the stage that is denied.
        SegmentAgentEnvironment environment = AgentTestFixtures.environment(
                AgentTestFixtures.portReturning(SliceTestFixtures.enumTierJson()),
                AgentTestFixtures.snapshot(),
                AgentTestFixtures.context(),
                PreviewAuthorizationStub.allowFirstMetadata(5),
                AgentTestFixtures.previewPort(
                        AgentTestFixtures.snapshot(), AgentTestFixtures.context(), SegmentValidationFixtures.allowAll()),
                null,
                null);

        SegmentAgentResult result = AgentTestFixtures.run(environment, new SegmentAgentRequest("Gold会员"));

        assertThat(result.disposition()).isEqualTo(Disposition.DENIED);
        assertThat(result.terminalState()).isEqualTo(SegmentAgentState.DENIED);
        assertThat(result.failureCode()).isEqualTo(AgentTestFixtures.skill().failureCode("validationFailure"));
        assertThat(admitted(result)).containsExactly(
                SegmentToolIds.METADATA_DISCOVER, SegmentToolIds.INTENT_RESOLVE);
        assertThat(result.toolTrace()).extracting(SegmentToolCallRecord::toolId)
                .doesNotContain(SegmentToolIds.PLAN_BUILD, SegmentToolIds.PREVIEW_SAFE,
                        SegmentToolIds.EXPLANATION_GROUNDED);
        assertNoArtifact(result);
    }

    @Test
    void aValidationFailureTerminatesAsInvalidWithNoLaterToolCall() {
        SegmentValidationPolicy tiny = new SegmentValidationPolicy(
                1, SegmentValidationLimits.MAX_PREDICATE_DEPTH, 64, 256, 4);
        SegmentAgentEnvironment environment = standard(SliceTestFixtures.nestedGroupingJson())
                .withValidationPolicy(tiny);

        SegmentAgentResult result = AgentTestFixtures.run(
                environment, new SegmentAgentRequest(SliceTestFixtures.NESTED_UTTERANCE));

        assertThat(result.disposition()).isEqualTo(Disposition.INVALID);
        assertThat(result.failureCode()).isEqualTo(AgentTestFixtures.skill().failureCode("validationFailure"));
        assertThat(result.toolTrace()).extracting(SegmentToolCallRecord::toolId)
                .doesNotContain(SegmentToolIds.PLAN_BUILD, SegmentToolIds.PREVIEW_SAFE,
                        SegmentToolIds.EXPLANATION_GROUNDED);
        assertNoArtifact(result);
    }

    @Test
    void aPlanningDenialTerminatesAsDeniedWithNoPreviewOrExplanationCall() {
        // Discovery authorizes every permitted definition (five) and validation authorizes the one referenced
        // definition, so the seventh Metadata decision is the genuine T08 planning re-authorization.
        SegmentAgentEnvironment environment = AgentTestFixtures.environment(
                AgentTestFixtures.portReturning(SliceTestFixtures.enumTierJson()),
                AgentTestFixtures.snapshot(),
                AgentTestFixtures.context(),
                PreviewAuthorizationStub.allowFirstMetadata(6),
                AgentTestFixtures.previewPort(
                        AgentTestFixtures.snapshot(), AgentTestFixtures.context(), SegmentValidationFixtures.allowAll()),
                null,
                null);

        SegmentAgentResult result = AgentTestFixtures.run(environment, new SegmentAgentRequest("Gold会员"));

        assertThat(result.disposition()).isEqualTo(Disposition.DENIED);
        assertThat(result.failureCode()).isEqualTo(AgentTestFixtures.skill().failureCode("planningFailure"));
        assertThat(result.toolTrace()).extracting(SegmentToolCallRecord::toolId)
                .doesNotContain(SegmentToolIds.PREVIEW_SAFE, SegmentToolIds.EXPLANATION_GROUNDED);
        assertThat(result.toolTrace()).filteredOn(call -> SegmentToolIds.PLAN_BUILD.equals(call.toolId()))
                .singleElement()
                .satisfies(call -> assertThat(call.disposition()).isEqualTo(Disposition.DENIED));
        assertNoArtifact(result);
    }

    @Test
    void aPreviewBackendFailureTerminatesAsInvalidWithNoPreviewOrExplanationCall() {
        StubPreviewBackend failing = new StubPreviewBackend((plan, context) -> {
            throw new PreviewBackendException(PreviewBackendException.Kind.UNAVAILABLE);
        });
        SegmentAgentEnvironment environment = standard(SliceTestFixtures.enumTierJson()).withPreviewPort(
                AgentTestFixtures.previewPortWithBackend(
                        AgentTestFixtures.snapshot(), AgentTestFixtures.context(),
                        SegmentValidationFixtures.allowAll(), failing));

        SegmentAgentResult result = AgentTestFixtures.run(environment, new SegmentAgentRequest("Gold会员"));

        assertThat(result.disposition()).isEqualTo(Disposition.INVALID);
        assertThat(result.failureCode()).isEqualTo(AgentTestFixtures.skill().failureCode("previewFailure"));
        assertThat(result.toolTrace()).extracting(SegmentToolCallRecord::toolId)
                .doesNotContain(SegmentToolIds.EXPLANATION_GROUNDED);
        assertThat(failing.callCount()).isEqualTo(1);
        assertNoArtifact(result);
    }

    @Test
    void aMismatchedMetadataPinTerminatesBeforeTheStateMachineStarts() {
        SegmentAgentEnvironment environment = AgentTestFixtures.environment(
                AgentTestFixtures.portReturning(SliceTestFixtures.enumTierJson()),
                SliceTestFixtures.snapshot(),
                SliceTestFixtures.contextPinning("metadata-999"));

        SegmentAgentResult result = AgentTestFixtures.run(environment, new SegmentAgentRequest("Gold会员"));

        assertThat(result.disposition()).isEqualTo(Disposition.INVALID);
        assertThat(result.failureCode()).isEqualTo(SegmentAgentErrorCodes.METADATA_PIN_MISMATCH);
        assertThat(result.stateTrace()).isEmpty();
        assertThat(result.toolTrace()).isEmpty();
        assertNoArtifact(result);
    }

    @Test
    void anUnavailableTrustedContextTerminatesAsDenied() {
        SegmentAgentEnvironment environment = SegmentAgentEnvironment.of(
                AgentTestFixtures.skill(),
                com.company.campaign.preview.testsupport.RecordingTrustedContextProvider.failing(),
                context -> SliceTestFixtures.snapshot(),
                new com.company.campaign.metadata.service.MetadataQueryService(
                        SegmentValidationFixtures.allowAll(),
                        AgentTestFixtures.repository(SliceTestFixtures.snapshot())),
                SegmentValidationFixtures.allowAll(),
                AgentTestFixtures.portReturning(SliceTestFixtures.enumTierJson()),
                SliceTestFixtures.policy(),
                SliceTestFixtures.registry(),
                SliceTestFixtures.validationPolicy(),
                AgentTestFixtures.previewPort(
                        AgentTestFixtures.snapshot(), SliceTestFixtures.context(), SegmentValidationFixtures.allowAll()),
                null,
                null);

        SegmentAgentResult result = AgentTestFixtures.run(environment, new SegmentAgentRequest("Gold会员"));

        assertThat(result.disposition()).isEqualTo(Disposition.DENIED);
        assertThat(result.failureCode()).isEqualTo(SegmentAgentErrorCodes.TRUSTED_CONTEXT_UNAVAILABLE);
        assertThat(result.stateTrace()).isEmpty();
        assertThat(result.toolTrace()).isEmpty();
    }

    @Test
    void aRequestBeyondItsAbsoluteBoundCannotEvenBeConstructed() {
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> new SegmentAgentRequest("x".repeat(SegmentAgentLimits.MAX_UTTERANCE_LENGTH + 1)))
                .isInstanceOf(SegmentAgentException.class)
                .extracting(failure -> ((SegmentAgentException) failure).code())
                .isEqualTo(SegmentAgentErrorCodes.REQUEST_INVALID);
    }
}