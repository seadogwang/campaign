package com.company.campaign.builder.agent;

import com.company.campaign.builder.agent.testsupport.AgentTestFixtures;
import com.company.campaign.builder.segment.testsupport.SliceTestFixtures;
import com.company.campaign.contracts.Disposition;
import com.company.campaign.segment.intent.SegmentIntent;
import com.company.campaign.segment.intent.SegmentIntentCodec;
import com.company.campaign.segment.resolution.Clarification;
import com.company.campaign.segment.resolution.ClarificationChoiceService;
import com.company.campaign.segment.resolution.ClarificationSelectionReceipt;
import com.company.campaign.segment.resolution.IntentResolver;
import com.company.campaign.segment.resolution.ResolutionResult;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Requirements 5 and 6: ambiguity stops at {@code NEEDS_INPUT} with the genuine T10 clarification and no
 * artifact, and an explicit continuation succeeds only for a current offered option while stale, fabricated or
 * model-selected options fail safely.
 */
class SegmentAgentClarificationTest {

    private static final String MONEY_UTTERANCE = "过去90天消费超过1000元";

    private static IntentResolver resolver() {
        return new IntentResolver(
                SliceTestFixtures.ambiguousSpendSnapshot(), SliceTestFixtures.policy(), SliceTestFixtures.registry());
    }

    private static SegmentIntent intent() {
        return new SegmentIntentCodec().parse(
                SliceTestFixtures.ambiguousSpendJson().getBytes(StandardCharsets.UTF_8), MONEY_UTTERANCE,
                SliceTestFixtures.registry());
    }

    private static SegmentAgentEnvironment environment() {
        return AgentTestFixtures.environment(
                AgentTestFixtures.portReturning(SliceTestFixtures.ambiguousSpendJson()),
                AgentTestFixtures.ambiguousSnapshot(),
                AgentTestFixtures.context());
    }

    @Test
    void ambiguityReachesNeedsInputWithTheGenuineQuestionAndNoArtifact() {
        SegmentAgentResult result = AgentTestFixtures.run(environment(), new SegmentAgentRequest(MONEY_UTTERANCE));

        assertThat(result.disposition()).isEqualTo(Disposition.NEEDS_INPUT);
        assertThat(result.terminalState()).isEqualTo(SegmentAgentState.NEEDS_INPUT);
        assertThat(result.failureCode()).isNotNull();
        assertThat(result.clarification()).isNotNull();
        assertThat(result.previewCard()).isNull();
        assertThat(result.explanation()).isNull();
        assertThat(result.dslEditableHash()).isNull();
        assertThat(result.canonicalAstHash()).isNull();
        assertThat(result.planHash()).isNull();
        assertThat(result.previewHash()).isNull();
        assertThat(result.intentHash()).isNotNull();
        assertThat(result.metadataSnapshotHash()).isNotNull();

        Clarification offered = resolver().resolve(intent()).clarification();
        assertThat(result.clarification().resolutionId()).isEqualTo(offered.resolutionId());
        assertThat(result.clarification().questionCode()).isEqualTo(offered.questionCode());
        assertThat(result.clarification().optionIds()).containsExactlyElementsOf(offered.optionIds());

        assertThat(result.stateTrace()).extracting(AgentTransition::toState).containsExactly(
                SegmentAgentState.UNDERSTANDING, SegmentAgentState.DISCOVERING, SegmentAgentState.RESOLVING,
                SegmentAgentState.NEEDS_INPUT);
        assertThat(result.toolTrace()).extracting(com.company.campaign.builder.tool.SegmentToolCallRecord::toolId)
                .doesNotContain("dsl.validate", "plan.build", "preview.safe", "explanation.grounded");
    }

    @Test
    void anExplicitContinuationOfACurrentOfferedOptionSucceeds() {
        List<ClarificationSelectionReceipt> receipts = new ClarificationChoiceService(resolver())
                .continueResolution(intent(), clarification -> clarification.optionIds().get(0))
                .selectionReceipts();
        assertThat(receipts).isNotEmpty();

        SegmentAgentResult result = AgentTestFixtures.run(
                environment(), new SegmentAgentRequest(MONEY_UTTERANCE, receipts));

        assertThat(result.disposition()).isEqualTo(Disposition.RESOLVED);
        assertThat(result.terminalState()).isEqualTo(SegmentAgentState.READY_FOR_PREVIEW);
        assertThat(result.clarification()).isNull();
        assertThat(result.dslEditableHash()).isNotNull();
        assertThat(result.previewCard()).isNotNull();
    }

    @Test
    void aFabricatedOptionFailsSafelyWithoutProgressing() {
        Clarification offered = resolver().resolve(intent()).clarification();
        ClarificationSelectionReceipt fabricated = new ClarificationSelectionReceipt(
                offered.resolutionId(), offered.clauseId(), "option:" + "0".repeat(64));

        SegmentAgentResult result = AgentTestFixtures.run(
                environment(), new SegmentAgentRequest(MONEY_UTTERANCE, List.of(fabricated)));

        assertThat(result.disposition()).isEqualTo(Disposition.NEEDS_INPUT);
        assertThat(result.clarification()).isNotNull();
        assertThat(result.previewCard()).isNull();
        assertThat(result.dslEditableHash()).isNull();
    }

    @Test
    void aStaleReceiptFromAnotherClarificationFailsSafely() {
        IntentResolver otherResolver = new IntentResolver(
                SliceTestFixtures.ambiguousSpendSnapshot(), SliceTestFixtures.otherPolicy(),
                SliceTestFixtures.registry());
        ResolutionResult otherResolution = otherResolver.resolve(intent());
        assertThat(otherResolution.disposition()).isEqualTo(Disposition.NEEDS_INPUT);
        List<ClarificationSelectionReceipt> stale = new ClarificationChoiceService(otherResolver)
                .continueResolution(intent(), clarification -> clarification.optionIds().get(0))
                .selectionReceipts();

        SegmentAgentResult result = AgentTestFixtures.run(
                environment(), new SegmentAgentRequest(MONEY_UTTERANCE, stale));

        assertThat(result.disposition()).isEqualTo(Disposition.NEEDS_INPUT);
        assertThat(result.previewCard()).isNull();
        assertThat(result.toolTrace()).extracting(com.company.campaign.builder.tool.SegmentToolCallRecord::toolId)
                .doesNotContain("dsl.validate", "plan.build", "preview.safe");
    }

    @Test
    void aContinuationSuppliedWhenTheCurrentIntentNeedsNoneIsRefused() {
        List<ClarificationSelectionReceipt> receipts = new ClarificationChoiceService(resolver())
                .continueResolution(intent(), clarification -> clarification.optionIds().get(0))
                .selectionReceipts();

        SegmentAgentResult result = AgentTestFixtures.run(
                AgentTestFixtures.environment(AgentTestFixtures.portReturning(SliceTestFixtures.enumTierJson())),
                new SegmentAgentRequest("Gold会员", receipts));

        assertThat(result.disposition()).isEqualTo(Disposition.INVALID);
        assertThat(result.previewCard()).isNull();
        assertThat(result.dslEditableHash()).isNull();
    }
}