package com.company.campaign.builder.campaignagent;

import com.company.campaign.builder.assembly.CampaignAssemblyErrorCodes;
import com.company.campaign.builder.assembly.CampaignSegmentAdapter;
import com.company.campaign.builder.assembly.CampaignSegmentStaging;
import com.company.campaign.builder.assembly.CampaignSkillArtifact;
import com.company.campaign.builder.assembly.CampaignSkillIds;
import com.company.campaign.builder.assembly.CampaignToolCatalog;
import com.company.campaign.builder.authoring.SegmentAuthoringIntegration;
import com.company.campaign.builder.authoring.SegmentAuthoringSource;
import com.company.campaign.builder.authoring.SegmentAuthoringRequest;
import com.company.campaign.builder.authoring.testsupport.SegmentAuthoringIntegrationFixtures;
import com.company.campaign.builder.compiler.CampaignCompiler;
import com.company.campaign.builder.testsupport.RecordingAuthorizationService;
import com.company.campaign.contracts.Disposition;
import com.company.campaign.segment.resolution.Clarification;
import com.company.campaign.segment.resolution.ClarificationOption;
import com.company.campaign.segment.resolution.QuestionCode;
import com.company.campaign.segment.resolution.ResolutionId;
import com.company.campaign.segment.resolution.ResolutionPins;
import com.company.campaign.shared.canonical.ContentHash;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression tests for the Segment source failure and component clarification path.
 *
 * <p>Covers: an ordinary two-clause member audience resolving to a ready Segment when metadata
 * supports it; a genuinely missing definition producing component-owned clarification, not
 * terminal invalid; main-Agent aggregation of the clarification with short safe context; and
 * internal/evidence failure remaining terminal and sanitized.
 */
class CampaignSegmentSourceRegressionTest {

    private static final String CONVERSATION = "conversation-regression";
    private static final String RUN = "run-regression";

    private static CampaignSegmentAdapter adapter() {
        return new CampaignSegmentAdapter(
                new SegmentAuthoringIntegration(new CampaignCompiler(new RecordingAuthorizationService())),
                CONVERSATION, RUN, CampaignToolCatalog.standard());
    }

    // ------------------------------------------------------------------ requirement 1: two-clause member audience ready

    @Test
    void twoOrdinaryClauseMemberIntentResolvesToReadySegmentWhenMetadataSupportsIt() {
        var proof = SegmentAuthoringIntegrationFixtures.proof();
        var ready = SegmentAuthoringIntegrationFixtures.agentResult(proof);
        var source = SegmentAuthoringIntegrationFixtures.ai(proof);
        var request = SegmentAuthoringIntegrationFixtures.createRequest(source);

        CampaignSegmentStaging staging = adapter().stageResult(ready, proof, request);

        assertThat(staging).isInstanceOf(CampaignSegmentStaging.Staged.class);
        CampaignSkillArtifact artifact = ((CampaignSegmentStaging.Staged) staging).artifact();
        assertThat(artifact.skillId()).isEqualTo(CampaignSkillIds.SEGMENT_RULE_BASED);
    }

    // ------------------------------------------------------------------ requirement 2: missing definition → component-owned clarification

    @Test
    void genuinelyMissingDefinitionProducesComponentOwnedNeedsInputClarificationNotTerminalInvalid() {
        SegmentAuthoringSource terminalSource =
                SegmentAuthoringIntegrationFixtures.terminalAi(Disposition.NEEDS_INPUT);
        SegmentAuthoringRequest request =
                SegmentAuthoringIntegrationFixtures.createRequest(terminalSource);

        CampaignSegmentStaging staging = adapter().stage(terminalSource, request);

        assertThat(staging).isInstanceOf(CampaignSegmentStaging.NotAcceptable.class);
        CampaignSegmentStaging.NotAcceptable notAcceptable =
                (CampaignSegmentStaging.NotAcceptable) staging;
        assertThat(notAcceptable.disposition()).isEqualTo(Disposition.NEEDS_INPUT);
        assertThat(notAcceptable.code()).isNotNull();
        assertThat(notAcceptable.clarification()).isNotNull();
        // The clarification has bounded options handled by the fixture default.
        assertThat(notAcceptable.clarification().optionIds()).isNotEmpty();
    }

    @Test
    void needsInputWithManyCandidateOptionsPreservesNotAcceptableWithClarification() {
        // Construct a clarification with more than MAX_OPTIONS (8) candidates.
        Clarification manyOptions = clarificationWithOptionCount(12);

        SegmentAuthoringSource terminalSource =
                SegmentAuthoringIntegrationFixtures.terminalAi(
                        Disposition.NEEDS_INPUT, "SEGMENT_NEEDS_INPUT", manyOptions);
        SegmentAuthoringRequest request =
                SegmentAuthoringIntegrationFixtures.createRequest(terminalSource);

        CampaignSegmentStaging staging = adapter().stage(terminalSource, request);

        // The adapter must preserve NEEDS_INPUT; the clarification and its 12 options travel with it.
        assertThat(staging).isInstanceOf(CampaignSegmentStaging.NotAcceptable.class);
        CampaignSegmentStaging.NotAcceptable notAcceptable =
                (CampaignSegmentStaging.NotAcceptable) staging;
        assertThat(notAcceptable.disposition()).isEqualTo(Disposition.NEEDS_INPUT);
        assertThat(notAcceptable.clarification()).isNotNull();
        assertThat(notAcceptable.clarification().optionIds()).hasSize(12);
    }

    // ------------------------------------------------------------------ requirement 3: main-Agent aggregation with safe context

    @Test
    void aggregateClarificationCarriesComponentOwnershipAndBoundedCode() {
        // Build a single component clarification with Segment ownership and safe context.
        Clarification clarification = clarificationWithOptionCount(3);
        CampaignSkillOutcome.NeedsInput needsInput = new CampaignSkillOutcome.NeedsInput(
                CampaignSkillIds.SEGMENT_RULE_BASED,
                "CAMPAIGN_AGENT_SEGMENT_INPUT_REQUIRED",
                List.of("SEGMENT_AUDIENCE_REQUIRED"),
                List.of(), true, null);

        CampaignComponentClarification componentClarification =
                CampaignComponentClarification.from(CampaignComponentCode.SEGMENT, needsInput);

        assertThat(componentClarification.component()).isEqualTo(CampaignComponentCode.SEGMENT);
        assertThat(componentClarification.skillId()).isEqualTo(CampaignSkillIds.SEGMENT_RULE_BASED);
        assertThat(componentClarification.code()).matches("^[A-Z][A-Z0-9_]{0,63}$");
        // The prompt is bounded safe context, never exception text or SQL.
        assertThat(componentClarification.prompt()).isNotEmpty();
        assertThat(componentClarification.prompt().length())
                .isLessThanOrEqualTo(CampaignAgentLimits.MAX_ASSISTANT_COPY_LENGTH);
        assertThat(componentClarification.questionCodes()).isNotEmpty();
        assertThat(componentClarification.freeTextAllowed()).isTrue();
    }

    @Test
    void singleQuestionAggregateProjectsWithComponentPromptAndQuestionCodes() {
        CampaignSkillOutcome.NeedsInput needsInput = new CampaignSkillOutcome.NeedsInput(
                CampaignSkillIds.SEGMENT_RULE_BASED,
                "CAMPAIGN_AGENT_SEGMENT_INPUT_REQUIRED",
                List.of("SEGMENT_AUDIENCE_REQUIRED"),
                List.of(), true, null);

        CampaignComponentClarification request = CampaignComponentClarification.from(
                CampaignComponentCode.SEGMENT, needsInput);
        CampaignClarificationAggregate aggregate = CampaignClarificationAggregate.of(List.of(request));

        assertThat(aggregate.isEmpty()).isFalse();
        assertThat(aggregate.single()).isTrue();
        assertThat(aggregate.pendingSkillIds()).containsExactly(CampaignSkillIds.SEGMENT_RULE_BASED);

        CampaignClarification projection = aggregate.project(null, CampaignRequirementBrief.empty());
        assertThat(projection.code()).isEqualTo("CAMPAIGN_AGENT_SEGMENT_INPUT_REQUIRED");
        assertThat(projection.skillId()).isEqualTo(CampaignSkillIds.SEGMENT_RULE_BASED);
        assertThat(projection.questionCodes()).containsExactly("SEGMENT_AUDIENCE_REQUIRED");
    }

    // ------------------------------------------------------------------ requirement 4: internal/evidence failure remains terminal and sanitized

    @Test
    void unsupportedDispositionRemainsTerminalNotConvertedToClarification() {
        for (Disposition disp : new Disposition[]{
                Disposition.UNSUPPORTED, Disposition.DENIED, Disposition.INVALID}) {
            SegmentAuthoringSource source =
                    SegmentAuthoringIntegrationFixtures.terminalAi(disp);
            SegmentAuthoringRequest request =
                    SegmentAuthoringIntegrationFixtures.createRequest(source);

            CampaignSegmentStaging staging = adapter().stage(source, request);

            assertThat(staging).as(disp.name())
                    .isInstanceOf(CampaignSegmentStaging.NotAcceptable.class);
            CampaignSegmentStaging.NotAcceptable na =
                    (CampaignSegmentStaging.NotAcceptable) staging;
            assertThat(na.disposition()).as(disp.name()).isEqualTo(disp);
            // Only NEEDS_INPUT may carry a clarification.
            assertThat(na.clarification()).as(disp.name()).isNull();
        }
    }

    @Test
    void refusedSubmissionDoesNotLeakExceptionTextOrRawProviderBody() {
        // Stage a null source: the adapter returns Refused with a bounded machine code.
        CampaignSegmentStaging staging = adapter().stage(null, null);

        assertThat(staging).isInstanceOf(CampaignSegmentStaging.Refused.class);
        CampaignSegmentStaging.Refused refused = (CampaignSegmentStaging.Refused) staging;
        assertThat(refused.code()).isEqualTo(CampaignAssemblyErrorCodes.ADAPTER_NOT_ELIGIBLE);
        // The code is a bounded machine code; it contains no exception text.
        assertThat(refused.code()).matches("^[A-Z][A-Z0-9_]{0,63}$");
    }

    // ------------------------------------------------------------------ requirement 5: error-code normalization preserves bounded codes

    @Test
    void boundedSegmentFailureCodeIsPreservedThroughTheTerminalPath() {
        // A NEEDS_INPUT with a valid bounded code and a definition-selection clarification.
        Clarification definitionClarification = clarificationWithOptionCount(3);

        SegmentAuthoringSource terminalSource =
                SegmentAuthoringIntegrationFixtures.terminalAi(
                        Disposition.NEEDS_INPUT, "SEGMENT_NEEDS_INPUT", definitionClarification);
        SegmentAuthoringRequest request =
                SegmentAuthoringIntegrationFixtures.createRequest(terminalSource);

        CampaignSegmentStaging staging = adapter().stage(terminalSource, request);

        assertThat(staging).isInstanceOf(CampaignSegmentStaging.NotAcceptable.class);
        CampaignSegmentStaging.NotAcceptable na = (CampaignSegmentStaging.NotAcceptable) staging;
        // The bounded code is preserved, not erased to a generic fallback.
        assertThat(na.code()).isEqualTo("SEGMENT_NEEDS_INPUT");
        assertThat(na.disposition()).isEqualTo(Disposition.NEEDS_INPUT);
        assertThat(na.clarification()).isNotNull();
    }

    // ------------------------------------------------------------------ helpers

    private static Clarification clarificationWithOptionCount(int count) {
        List<ClarificationOption> options = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String defId = "definition-" + i;
            options.add(new ClarificationOption(
                    ClarificationOption.deriveOptionId("c1", defId), defId, "Candidate " + i));
        }
        List<String> optionIds = new ArrayList<>(count);
        for (ClarificationOption option : options) {
            optionIds.add(option.optionId());
        }
        ContentHash hash = ContentHash.sha256("test".getBytes(StandardCharsets.UTF_8));
        ResolutionPins pins = new ResolutionPins(
                hash.value(), "1", hash, "1", hash, "1", hash);
        ResolutionId resolutionId = ResolutionId.derive(
                hash, pins, QuestionCode.DISAMBIGUATE_DEFINITION, "c1", optionIds);
        return new Clarification(
                resolutionId, hash, pins, QuestionCode.DISAMBIGUATE_DEFINITION, "c1",
                "Select the correct definition", options);
    }
}