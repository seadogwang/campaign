package com.company.campaign.builder.agent;

import com.company.campaign.builder.agent.testsupport.AgentTestFixtures;
import com.company.campaign.builder.segment.testsupport.SliceTestFixtures;
import com.company.campaign.builder.tool.SegmentToolCallRecord;
import com.company.campaign.builder.tool.SegmentToolIds;
import com.company.campaign.contracts.Disposition;
import com.company.campaign.preview.testsupport.PreviewAuthorizationStub;
import com.company.campaign.segment.validation.testsupport.SegmentValidationFixtures;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Requirement 4, 9, 10, 11 and 12: the happy path reaches {@code READY_FOR_PREVIEW} through the exact ordered
 * state trace and genuine components, the Preview defaults to no sample, the explanation is fully grounded, and
 * identical explicit inputs replay byte-identically while a changed evidence input changes the covered hash.
 */
class SegmentAgentHappyPathTest {

    private static SegmentAgentResult happyRun() {
        return AgentTestFixtures.run(
                AgentTestFixtures.environment(AgentTestFixtures.portReturning(SliceTestFixtures.enumTierJson())),
                new SegmentAgentRequest("Gold会员"));
    }

    @Test
    void reachesReadyForPreviewThroughTheExactOrderedStateTrace() {
        SegmentAgentResult result = happyRun();

        assertThat(result.disposition()).isEqualTo(Disposition.RESOLVED);
        assertThat(result.terminalState()).isEqualTo(SegmentAgentState.READY_FOR_PREVIEW);
        assertThat(result.isReadyForPreview()).isTrue();
        assertThat(result.developmentOnly()).isTrue();
        assertThat(result.evidenceClass()).isEqualTo("DEVELOPMENT_ONLY");
        assertThat(result.failureCode()).isNull();
        assertThat(result.clarification()).isNull();

        assertThat(result.stateTrace()).extracting(AgentTransition::toState).containsExactly(
                SegmentAgentState.UNDERSTANDING,
                SegmentAgentState.DISCOVERING,
                SegmentAgentState.RESOLVING,
                SegmentAgentState.GENERATING_DSL,
                SegmentAgentState.VALIDATING,
                SegmentAgentState.PLANNING,
                SegmentAgentState.PREVIEWING,
                SegmentAgentState.READY_FOR_PREVIEW);
        assertThat(result.stateTrace()).extracting(AgentTransition::sequence).containsExactly(0, 1, 2, 3, 4, 5, 6, 7);

        assertThat(result.toolTrace()).extracting(SegmentToolCallRecord::toolId).containsExactly(
                SegmentToolIds.METADATA_DISCOVER,
                SegmentToolIds.INTENT_RESOLVE,
                SegmentToolIds.DSL_VALIDATE,
                SegmentToolIds.PLAN_BUILD,
                SegmentToolIds.PREVIEW_SAFE,
                SegmentToolIds.EXPLANATION_GROUNDED);
        assertThat(result.toolTrace()).allSatisfy(call -> {
            assertThat(call.disposition()).isEqualTo(Disposition.RESOLVED);
            assertThat(call.code()).isNull();
            assertThat(call.inputFingerprint()).isNotNull();
        });
    }

    @Test
    void resolvedResultCarriesTheImmutableHashesAndAPinCompletePreviewCard() {
        SegmentAgentResult result = happyRun();

        assertThat(result.skillId()).isEqualTo("segment.rule-based");
        assertThat(result.skillHash()).isEqualTo(AgentTestFixtures.skill().canonicalHash());
        assertThat(List.of(
                result.metadataSnapshotHash(), result.intentHash(), result.dslEditableHash(),
                result.canonicalAstHash(), result.planHash(), result.previewHash(), result.resultHash()))
                .doesNotContainNull();

        SegmentPreviewCard card = result.previewCard();
        assertThat(card).isNotNull();
        assertThat(card.matchedCount()).isEqualTo(AgentTestFixtures.MEMBERS.size());
        assertThat(card.metadataSnapshotId()).isEqualTo(AgentTestFixtures.snapshot().snapshotId());
        assertThat(card.previewHash()).isEqualTo(result.previewHash());
        assertThat(card.planHash()).isEqualTo(result.planHash());
        assertThat(card.dslHash()).isEqualTo(result.dslEditableHash());
        assertThat(card.canonicalAstHash()).isEqualTo(result.canonicalAstHash());
        assertThat(card.metadataHash()).isEqualTo(result.metadataSnapshotHash());
        assertThat(card.skillHash()).isEqualTo(result.skillHash());
    }

    @Test
    void previewDefaultsToNoSample() {
        SegmentAgentResult result = happyRun();

        assertThat(result.previewCard().sampleTokens()).isNull();
        assertThat(result.previewCard().hasSample()).isFalse();
    }

    @Test
    void theSafePreviewIsAlwaysInvokedWithTheSkillSampleProhibition() {
        AgentTestFixtures.RecordingPreviewPort recording = new AgentTestFixtures.RecordingPreviewPort(
                AgentTestFixtures.previewPort(
                        AgentTestFixtures.snapshot(), AgentTestFixtures.context(),
                        SegmentValidationFixtures.allowAll()));
        SegmentAgentEnvironment environment = AgentTestFixtures.environment(
                AgentTestFixtures.portReturning(SliceTestFixtures.enumTierJson()),
                AgentTestFixtures.snapshot(),
                AgentTestFixtures.context(),
                SegmentValidationFixtures.allowAll(),
                recording,
                null,
                null);

        SegmentAgentResult result = AgentTestFixtures.run(environment, new SegmentAgentRequest("Gold会员"));

        assertThat(result.disposition()).isEqualTo(Disposition.RESOLVED);
        assertThat(AgentTestFixtures.skill().sampleOutputPermitted()).isFalse();
        assertThat(recording.presentations()).hasSize(1);
        assertThat(recording.presentations()).allSatisfy(presentation -> {
            assertThat(presentation.includeSample()).isFalse();
            assertThat(presentation.includeConditionStats()).isTrue();
        });
    }

    @Test
    void noSampleTokenCanAppearInASuccessfulResult() {
        SegmentAgentResult result = happyRun();

        assertThat(result.disposition()).isEqualTo(Disposition.RESOLVED);
        assertThat(result.previewCard().sampleTokens()).isNull();
        assertThat(result.previewCard().hasSample()).isFalse();
        assertThat(result.canonicalBytes()).hasSizeGreaterThan(0);
        String rendered = new String(result.canonicalBytes(), java.nio.charset.StandardCharsets.UTF_8);
        for (String member : AgentTestFixtures.MEMBERS) {
            assertThat(rendered).doesNotContain(member);
        }
        assertThat(result.resultHash()).isNotNull();
    }

    @Test
    void anUnauthorizedSampleIsAbsent() {
        SegmentAgentEnvironment environment = AgentTestFixtures.environment(
                AgentTestFixtures.portReturning(SliceTestFixtures.enumTierJson()),
                AgentTestFixtures.snapshot(),
                AgentTestFixtures.context(),
                SegmentValidationFixtures.allowAll(),
                AgentTestFixtures.previewPort(
                        AgentTestFixtures.snapshot(), AgentTestFixtures.context(),
                        PreviewAuthorizationStub.sample(SegmentValidationFixtures.allowAll(), null)),
                null,
                null);

        SegmentAgentResult result = AgentTestFixtures.run(environment, new SegmentAgentRequest("Gold会员"));

        assertThat(result.disposition()).isEqualTo(Disposition.RESOLVED);
        assertThat(result.previewCard().sampleTokens()).isNull();
        assertThat(result.previewCard().hasSample()).isFalse();
    }

    @Test
    void aPreviewPortReturningASampleDespiteNoRequestFailsClosed() {
        com.company.campaign.preview.PreviewResponse withSample = new com.company.campaign.preview.PreviewResponse(
                "OK",
                "sha256:" + "1".repeat(64),
                "sha256:" + "2".repeat(64),
                "sha256:" + "3".repeat(64),
                AgentTestFixtures.MEMBERS.size(),
                null,
                List.of("sha256:" + "4".repeat(64)),
                new com.company.campaign.preview.PreviewExecutionContext(
                        AgentTestFixtures.snapshot().snapshotId(), "2026-01-01T00:00:00Z", "UTC", "wm-1"));
        SegmentAgentEnvironment environment = AgentTestFixtures.environment(
                AgentTestFixtures.portReturning(SliceTestFixtures.enumTierJson()),
                AgentTestFixtures.snapshot(),
                AgentTestFixtures.context(),
                SegmentValidationFixtures.allowAll(),
                AgentTestFixtures.previewPortReturning(withSample),
                null,
                null);

        SegmentAgentResult result = AgentTestFixtures.run(environment, new SegmentAgentRequest("Gold会员"));

        assertThat(result.disposition()).isEqualTo(Disposition.INVALID);
        assertThat(result.failureCode()).isEqualTo(AgentTestFixtures.skill().failureCode("previewFailure"));
        assertThat(result.previewCard()).isNull();
        assertThat(result.explanation()).isNull();
        assertThat(result.toolTrace()).extracting(com.company.campaign.builder.tool.SegmentToolCallRecord::toolId)
                .doesNotContain(com.company.campaign.builder.tool.SegmentToolIds.EXPLANATION_GROUNDED);
    }

    @Test
    void explanationClaimsAreTypedAndFullyGrounded() {
        SegmentAgentResult result = happyRun();
        GroundedExplanation explanation = result.explanation();

        assertThat(explanation).isNotNull();
        assertThat(explanation.claims()).isNotEmpty();
        assertThat(explanation.explanationHash()).isNotNull();

        java.util.Set<String> knownNodes = new java.util.HashSet<>();
        java.util.Set<String> knownDefinitions = new java.util.HashSet<>();
        for (AgentTransition transition : result.stateTrace()) {
            assertThat(transition.evidenceCode()).isNotBlank();
        }
        for (ExplanationClaim claim : explanation.claims()) {
            assertThat(claim.claimId()).isNotBlank();
            assertThat(claim.kind()).isNotNull();
            knownNodes.addAll(claim.nodeIds());
            knownDefinitions.addAll(claim.definitionIds());
            for (String text : List.of(
                    String.valueOf(claim.operator()), String.valueOf(claim.valueSummary()),
                    String.valueOf(claim.windowSummary()), String.valueOf(claim.previewStatisticSummary()))) {
                assertThat(text.toUpperCase(java.util.Locale.ROOT))
                        .doesNotContain("SELECT").doesNotContain(" FROM ").doesNotContain("WHERE ");
            }
        }
        assertThat(knownDefinitions).contains("member.tier");
        assertThat(knownNodes).isNotEmpty();
    }

    @Test
    void identicalExplicitInputsReplayByteIdenticallyAtLeastOneHundredTimes() {
        byte[] expected = happyRun().canonicalBytes();
        String expectedHash = happyRun().resultHash().value();
        for (int iteration = 0; iteration < 100; iteration++) {
            SegmentAgentResult replay = happyRun();
            assertThat(replay.canonicalBytes()).isEqualTo(expected);
            assertThat(replay.resultHash().value()).isEqualTo(expectedHash);
        }
    }

    @Test
    void changingTheIntentEvidenceChangesTheCoveredHash() {
        SegmentAgentResult tier = happyRun();
        SegmentAgentResult count = AgentTestFixtures.run(
                AgentTestFixtures.environment(AgentTestFixtures.portReturning(SliceTestFixtures.integerCountJson())),
                new SegmentAgentRequest("订单数超过3"));

        assertThat(count.disposition()).isEqualTo(Disposition.RESOLVED);
        assertThat(count.intentHash()).isNotEqualTo(tier.intentHash());
        assertThat(count.dslEditableHash()).isNotEqualTo(tier.dslEditableHash());
        assertThat(count.canonicalAstHash()).isNotEqualTo(tier.canonicalAstHash());
        assertThat(count.planHash()).isNotEqualTo(tier.planHash());
        assertThat(count.resultHash()).isNotEqualTo(tier.resultHash());
    }

    @Test
    void changingThePreviewEvidenceChangesTheCoveredHash() {
        SegmentAgentResult full = happyRun();
        com.company.campaign.preview.testsupport.StubPreviewBackend smaller =
                new com.company.campaign.preview.testsupport.StubPreviewBackend((plan, context) ->
                        com.company.campaign.preview.testsupport.StubPreviewBackend.coherent(
                                plan, context, List.of("member-1"), "preview-stub-backend",
                                com.company.campaign.preview.testsupport.StubPreviewBackend.DEFAULT_VERSION));
        SegmentAgentResult reduced = AgentTestFixtures.run(
                AgentTestFixtures.environment(AgentTestFixtures.portReturning(SliceTestFixtures.enumTierJson()))
                        .withPreviewPort(AgentTestFixtures.previewPortWithBackend(
                                AgentTestFixtures.snapshot(), AgentTestFixtures.context(),
                                SegmentValidationFixtures.allowAll(), smaller)),
                new SegmentAgentRequest("Gold会员"));

        assertThat(reduced.disposition()).isEqualTo(Disposition.RESOLVED);
        assertThat(reduced.previewCard().matchedCount()).isEqualTo(1);
        assertThat(reduced.previewHash()).isNotEqualTo(full.previewHash());
        assertThat(reduced.resultHash()).isNotEqualTo(full.resultHash());
        assertThat(reduced.skillHash()).isEqualTo(full.skillHash());
    }

    @Test
    void changingTheMetadataSnapshotChangesTheCoveredHash() {
        SegmentAgentResult standard = happyRun();
        SegmentAgentResult ambiguous = AgentTestFixtures.run(
                AgentTestFixtures.environment(
                        AgentTestFixtures.portReturning(SliceTestFixtures.enumTierJson()),
                        AgentTestFixtures.ambiguousSnapshot(),
                        AgentTestFixtures.context()),
                new SegmentAgentRequest("Gold会员"));

        assertThat(ambiguous.disposition()).isEqualTo(Disposition.RESOLVED);
        assertThat(ambiguous.metadataSnapshotHash()).isNotEqualTo(standard.metadataSnapshotHash());
        assertThat(ambiguous.resultHash()).isNotEqualTo(standard.resultHash());
    }
}