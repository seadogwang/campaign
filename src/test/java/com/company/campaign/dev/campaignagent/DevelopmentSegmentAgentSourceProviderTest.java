package com.company.campaign.dev.campaignagent;

import com.company.campaign.builder.agent.SegmentAgentResult;
import com.company.campaign.builder.agent.SegmentAgentState;
import com.company.campaign.builder.assembly.CampaignSegmentAdapter;
import com.company.campaign.builder.assembly.CampaignSegmentStaging;
import com.company.campaign.builder.assembly.CampaignSkillArtifact;
import com.company.campaign.builder.assembly.CampaignToolCatalog;
import com.company.campaign.builder.authoring.SegmentAuthoringInputs;
import com.company.campaign.builder.authoring.SegmentAuthoringProof;
import com.company.campaign.builder.authoring.SegmentAuthoringRequest;
import com.company.campaign.builder.authoring.SegmentAuthoringSource;
import com.company.campaign.builder.authoring.SegmentAuthoringVersions;
import com.company.campaign.builder.campaignagent.CampaignAnswerOption;
import com.company.campaign.builder.campaignagent.CampaignSegmentContinuation;
import com.company.campaign.builder.campaignagent.CampaignSegmentSource;
import com.company.campaign.builder.campaignagent.CampaignSegmentSubmission;
import com.company.campaign.builder.compiler.CapabilityContract;
import com.company.campaign.builder.compiler.CapabilityReadiness;
import com.company.campaign.builder.segment.SegmentSchemaRepairPolicy;
import com.company.campaign.contracts.Disposition;
import com.company.campaign.dev.campaignagent.testsupport.DevelopmentSegmentRealHostFixture;
import com.company.campaign.dev.segment.CoreLoyaltyDevelopmentProfile;
import com.company.campaign.segment.resolution.Clarification;
import com.company.campaign.segment.resolution.ClarificationOption;
import com.company.campaign.segment.resolution.ClarificationSelectionReceipt;
import com.company.campaign.segment.resolution.QuestionCode;
import com.company.campaign.segment.resolution.ResolutionId;
import com.company.campaign.segment.resolution.ResolutionPins;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T33 requirements 1, 2 and 5: the real development Segment source runs the genuine Segment Agent, produces a
 * coherent proof/request only for a resolved result, keeps every non-resolved disposition a genuine terminal
 * source, and fails closed with one bounded redacted code when its observed evidence is incomplete or mixed.
 */
class DevelopmentSegmentAgentSourceProviderTest {

    private static final String CONVERSATION = DevelopmentSegmentRealHostFixture.CONVERSATION;
    private static final String RUN = DevelopmentSegmentRealHostFixture.RUN;
    private static final String UTTERANCE = DevelopmentSegmentRealHostFixture.UTTERANCE;

    private final DevelopmentCampaignAgentIdentity identity = DevelopmentSegmentRealHostFixture.identity();
    private final DevelopmentSegmentRealHostFixture.FakeIntentModel model =
            new DevelopmentSegmentRealHostFixture.FakeIntentModel();
    private final DevelopmentSegmentRealHostFixture.FakePreviewPort preview =
            new DevelopmentSegmentRealHostFixture.FakePreviewPort(
                    DevelopmentSegmentRealHostFixture.identity(), 3);

    private DevelopmentSegmentAgentSourceProvider provider() {
        return DevelopmentSegmentAgentSourceProvider.of(model, preview, Clock.fixed(
                DevelopmentSegmentRealHostFixture.NOW, ZoneOffset.UTC), identity);
    }

    private CampaignSegmentSource resolvedSource() {
        model.enqueue(DevelopmentSegmentRealHostFixture.resolvedIntentJson());
        CampaignSegmentSubmission submission = provider().source(CONVERSATION, RUN, UTTERANCE);
        assertThat(submission).isInstanceOf(CampaignSegmentSubmission.Source.class);
        return ((CampaignSegmentSubmission.Source) submission).source();
    }

    // ------------------------------------------------------------------ requirement 1

    @Test
    void oneGenuineAgentExecutionProducesACoherentProofAndStagesThroughTheRealIntegration() {
        model.enqueue(DevelopmentSegmentRealHostFixture.resolvedIntentJson());
        DevelopmentSegmentAgentSourceProvider provider = provider();

        CampaignSegmentSubmission submission = provider.source(CONVERSATION, RUN, UTTERANCE);

        assertThat(submission).isInstanceOf(CampaignSegmentSubmission.Source.class);
        CampaignSegmentSource source = ((CampaignSegmentSubmission.Source) submission).source();
        assertThat(source.source()).isInstanceOf(SegmentAuthoringSource.AiResolved.class);
        SegmentAuthoringSource.AiResolved resolved = (SegmentAuthoringSource.AiResolved) source.source();
        SegmentAgentResult result = resolved.result();
        assertThat(result.isReadyForPreview()).isTrue();
        assertThat(result.disposition()).isEqualTo(Disposition.RESOLVED);
        assertThat(result.skillId()).isEqualTo("segment.rule-based");
        assertThat(result.skillHash()).isEqualTo(provider.skill().canonicalHash());
        assertThat(model.calls()).isEqualTo(1);
        assertThat(preview.calls()).isEqualTo(1);

        SegmentAuthoringProof proof = resolved.proof();
        assertThat(proof.dslHash()).isEqualTo(result.dslEditableHash());
        assertThat(proof.canonicalAstHash()).isEqualTo(result.canonicalAstHash());
        assertThat(proof.planHash()).isEqualTo(result.planHash());
        assertThat(proof.previewHash()).isEqualTo(result.previewHash());
        assertThat(proof.skillHash()).isEqualTo(result.skillHash());

        SegmentAuthoringRequest request = source.request();
        assertThat(request.isCreate()).isTrue();
        assertThat(request.current()).isNull();
        assertThat(request.target().clientRef())
                .isEqualTo(DevelopmentSegmentAgentSourceProvider.SEGMENT_CLIENT_REF);
        assertThat(request.catalog().catalogHash())
                .isEqualTo(DevelopmentCampaignAgentAssets.capabilityCatalog().catalogHash());
        assertThat(request.policy().policyHash())
                .isEqualTo(DevelopmentCampaignAgentAssets.authoringPolicy().policyHash());
        assertThat(request.context()).isEqualTo(identity.context());
        assertThat(request.instants().compiledAt()).isEqualTo(DevelopmentSegmentRealHostFixture.NOW);

        assertThat(request.catalog().contracts())
                .extracting(CapabilityContract::capabilityCode)
                .contains(SegmentAuthoringVersions.CAPABILITY_CODE);

        assertThat(request.capabilityContext().inputsFor(SegmentAuthoringVersions.CAPABILITY_CODE))
                .isPresent()
                .get()
                .isInstanceOf(SegmentAuthoringInputs.class);
        SegmentAuthoringInputs inputs = (SegmentAuthoringInputs) request.capabilityContext()
                .inputsFor(SegmentAuthoringVersions.CAPABILITY_CODE).orElseThrow();
        assertThat(inputs.metadataSnapshot()).isEqualTo(CoreLoyaltyDevelopmentProfile.snapshot());
        assertThat(inputs.previewCard()).isEqualTo(proof.previewCard());
        assertThat(inputs.skillHash()).isEqualTo(result.skillHash());

        CapabilityReadiness readiness = request.capabilityContext()
                .readinessFor(SegmentAuthoringVersions.CAPABILITY_CODE).orElseThrow();
        assertThat(readiness.state()).isEqualTo(CapabilityReadiness.EXPERIMENTAL);
        assertThat(readiness.active()).isFalse();
        assertThat(readiness.activeReady()).isFalse();
        assertThat(readiness.hasApprovedDecision()).isFalse();

        CampaignSegmentStaging staging = new CampaignSegmentAdapter(
                DevelopmentCampaignAgentAssets.segmentIntegration(), CONVERSATION, RUN,
                CampaignToolCatalog.standard())
                .stage(source.source(), source.request());
        assertThat(staging).isInstanceOf(CampaignSegmentStaging.Staged.class);
        CampaignSkillArtifact artifact = ((CampaignSegmentStaging.Staged) staging).artifact();
        assertThat(artifact.skillId()).isEqualTo("segment.rule-based");
        assertThat(artifact.capabilityCode()).isEqualTo(SegmentAuthoringVersions.CAPABILITY_CODE);
    }

    @Test
    void theGenuineSafePreviewIsAlwaysInvokedWithoutASample() {
        resolvedSource();

        assertThat(preview.presentations()).hasSize(1);
        assertThat(preview.presentations().get(0).includeSample()).isFalse();
    }

    @Test
    void theSameFrozenCatalogPolicyAndContextBindTheSourceRequestAndTheAssembly() {
        CampaignSegmentSource source = resolvedSource();

        assertThat(source.request().catalog().catalogHash())
                .isEqualTo(DevelopmentCampaignAgentAssets.capabilityCatalog().catalogHash());
        assertThat(source.request().policy().policyHash())
                .isEqualTo(DevelopmentCampaignAgentAssets.authoringPolicy().policyHash());
        assertThat(source.request().context()).isEqualTo(identity.context());
        assertThat(identity.context().snapshotRef().metadataSnapshotId())
                .isEqualTo(CoreLoyaltyDevelopmentProfile.SNAPSHOT_ID);
    }

    @Test
    void anAnsweredContinuationIsForwardedToTheGenuineAgentInsteadOfBeingSilentlyIgnored() {
        Clarification clarification = clarification();
        String optionId = clarification.options().get(0).optionId();
        CampaignSegmentContinuation answered = CampaignSegmentContinuation.pending(
                UTTERANCE, clarification.resolutionId(), clarification.clauseId(),
                clarification.questionCode().name(),
                List.of(new CampaignAnswerOption(optionId, "Current tier")))
                .answered(optionId);
        assertThat(answered.receipts()).containsExactly(new ClarificationSelectionReceipt(
                clarification.resolutionId(), clarification.clauseId(), optionId));

        model.enqueue(DevelopmentSegmentRealHostFixture.resolvedIntentJson());
        CampaignSegmentSubmission submission =
                provider().source(CONVERSATION, RUN, UTTERANCE, answered);

        // The same utterance resolves without any clarification, so an unneeded receipt must be refused rather
        // than accepted as a silent no-op: the genuine Agent fails the run closed instead of resolving it.
        assertThat(submission).isInstanceOf(CampaignSegmentSubmission.Source.class);
        SegmentAuthoringSource.TerminalSource terminal = (SegmentAuthoringSource.TerminalSource)
                ((CampaignSegmentSubmission.Source) submission).source().source();
        assertThat(terminal.disposition())
                .isNotEqualTo(Disposition.RESOLVED);
    }

    // ------------------------------------------------------------------ requirement 2

    @Test
    void aGenuineUnsupportedAgentResultIsATerminalSourceWithNoProofOrPreview() {
        model.enqueue(DevelopmentSegmentRealHostFixture.unsupportedIntentJson());

        CampaignSegmentSubmission submission = provider().source(CONVERSATION, RUN, UTTERANCE);

        assertThat(submission).isInstanceOf(CampaignSegmentSubmission.Source.class);
        CampaignSegmentSource source = ((CampaignSegmentSubmission.Source) submission).source();
        assertThat(source.source()).isInstanceOf(SegmentAuthoringSource.AiTerminal.class);
        assertThat(((SegmentAuthoringSource.TerminalSource) source.source()).disposition())
                .isEqualTo(Disposition.UNSUPPORTED);
        assertThat(preview.calls()).isZero();
        assertTerminalRequestInventsNothing(source.request());

        CampaignSegmentStaging staging = new CampaignSegmentAdapter(
                DevelopmentCampaignAgentAssets.segmentIntegration(), CONVERSATION, RUN,
                CampaignToolCatalog.standard()).stage(source.source(), source.request());
        assertThat(staging).isInstanceOf(CampaignSegmentStaging.NotAcceptable.class);
        assertThat(((CampaignSegmentStaging.NotAcceptable) staging).disposition())
                .isEqualTo(Disposition.UNSUPPORTED);
    }

    @Test
    void aMalformedProviderResponseIsAGenuineInvalidTerminalSource() {
        model.enqueueRaw("not json at all");

        CampaignSegmentSubmission submission = provider().source(CONVERSATION, RUN, UTTERANCE);

        assertThat(submission).isInstanceOf(CampaignSegmentSubmission.Source.class);
        CampaignSegmentSource source = ((CampaignSegmentSubmission.Source) submission).source();
        assertThat(source.source()).isInstanceOf(SegmentAuthoringSource.AiTerminal.class);
        SegmentAuthoringSource.TerminalSource terminal =
                (SegmentAuthoringSource.TerminalSource) source.source();
        assertThat(terminal.disposition()).isEqualTo(Disposition.INVALID);
        assertThat(terminal.failureCode()).isNotBlank();
        assertThat(preview.calls()).isZero();
        assertTerminalRequestInventsNothing(source.request());
    }

    @Test
    void anEnabledSchemaRepairRetriesOnceAndAcceptsTheCorrectedIntent() {
        model.enqueueRaw("not json at all");
        model.enqueue(DevelopmentSegmentRealHostFixture.resolvedIntentJson());
        DevelopmentSegmentAgentSourceProvider provider = DevelopmentSegmentAgentSourceProvider.of(
                model, preview, Clock.fixed(DevelopmentSegmentRealHostFixture.NOW, ZoneOffset.UTC), identity,
                SegmentSchemaRepairPolicy.ONE_SCHEMA_REPAIR);

        CampaignSegmentSubmission submission = provider.source(CONVERSATION, RUN, UTTERANCE);

        assertThat(submission).isInstanceOf(CampaignSegmentSubmission.Source.class);
        CampaignSegmentSource source = ((CampaignSegmentSubmission.Source) submission).source();
        assertThat(source.source()).isInstanceOf(SegmentAuthoringSource.AiResolved.class);
        assertThat(model.calls()).isEqualTo(2);
        assertThat(preview.calls()).isEqualTo(1);
    }

    @Test
    void everyNonResolvedDispositionStaysATerminalSourceWithNoProofInvention() {
        for (Disposition disposition : List.of(
                Disposition.NEEDS_INPUT, Disposition.UNSUPPORTED, Disposition.DENIED, Disposition.INVALID)) {
            DevelopmentSegmentAgentSourceProvider provider = provider();
            SegmentAgentResult terminal = terminalResult(provider, disposition);
            DevelopmentSegmentAgentSourceProvider.StageCapture capture =
                    new DevelopmentSegmentAgentSourceProvider.StageCapture();

            CampaignSegmentSubmission submission = provider.submissionOf(terminal, capture);

            assertThat(submission).isInstanceOf(CampaignSegmentSubmission.Source.class);
            CampaignSegmentSource source = ((CampaignSegmentSubmission.Source) submission).source();
            assertThat(source.source()).isInstanceOf(SegmentAuthoringSource.AiTerminal.class);
            assertThat(((SegmentAuthoringSource.TerminalSource) source.source()).disposition())
                    .isEqualTo(disposition);
            assertTerminalRequestInventsNothing(source.request());
        }
    }

    private static void assertTerminalRequestInventsNothing(SegmentAuthoringRequest request) {
        assertThat(request.isCreate()).isTrue();
        assertThat(request.current()).isNull();
        assertThat(request.capabilityContext().inputs()).isEmpty();
        assertThat(request.capabilityContext().readinessFor(SegmentAuthoringVersions.CAPABILITY_CODE))
                .isPresent();
    }

    // ------------------------------------------------------------------ requirement 5

    @Test
    void aResolvedResultWithoutItsObservedArtifactsFailsClosedWithOneBoundedCode() {
        DevelopmentSegmentAgentSourceProvider provider = provider();
        model.enqueue(DevelopmentSegmentRealHostFixture.resolvedIntentJson());
        CampaignSegmentSource source = ((CampaignSegmentSubmission.Source)
                provider.source(CONVERSATION, RUN, UTTERANCE)).source();
        SegmentAgentResult result = ((SegmentAuthoringSource.AiResolved) source.source()).result();

        CampaignSegmentSubmission submission = provider.submissionOf(
                result, new DevelopmentSegmentAgentSourceProvider.StageCapture());

        assertThat(submission).isInstanceOf(CampaignSegmentSubmission.Unavailable.class);
        assertThat(((CampaignSegmentSubmission.Unavailable) submission).code())
                .isEqualTo(DevelopmentCampaignAgentErrorCodes.SEGMENT_EVIDENCE_INCOMPLETE);
        assertThat(submission.toString()).doesNotContain(UTTERANCE).doesNotContain("1000");
    }

    @Test
    void aMixedProofPinFailsClosedAndRedactsTheEvidence() {
        DevelopmentSegmentAgentSourceProvider provider = provider();
        model.enqueue(DevelopmentSegmentRealHostFixture.resolvedIntentJson());
        DevelopmentSegmentAgentSourceProvider.Observation first =
                provider.observe(CONVERSATION, RUN, UTTERANCE, null);
        assertThat(first.result().isReadyForPreview()).isTrue();

        // A second genuine run whose Preview evidence is different, so its captured card pins another Preview.
        DevelopmentSegmentRealHostFixture.FakePreviewPort otherPreview =
                new DevelopmentSegmentRealHostFixture.FakePreviewPort(
                        DevelopmentSegmentRealHostFixture.identity(), 7);
        DevelopmentSegmentRealHostFixture.FakeIntentModel otherModel =
                new DevelopmentSegmentRealHostFixture.FakeIntentModel()
                        .enqueue(DevelopmentSegmentRealHostFixture.resolvedIntentJson());
        DevelopmentSegmentAgentSourceProvider other = DevelopmentSegmentAgentSourceProvider.of(
                otherModel, otherPreview, Clock.fixed(DevelopmentSegmentRealHostFixture.NOW, ZoneOffset.UTC),
                DevelopmentSegmentRealHostFixture.identity());
        DevelopmentSegmentAgentSourceProvider.Observation second =
                other.observe(CONVERSATION, RUN, UTTERANCE, null);
        assertThat(second.result().previewHash()).isNotEqualTo(first.result().previewHash());

        // The first run's result against the second run's observation must fail closed, not silently prove.
        CampaignSegmentSubmission mixed = provider.submissionOf(first.result(), second.capture());

        assertThat(mixed).isInstanceOf(CampaignSegmentSubmission.Unavailable.class);
        assertThat(((CampaignSegmentSubmission.Unavailable) mixed).code())
                .isEqualTo(DevelopmentCampaignAgentErrorCodes.SEGMENT_EVIDENCE_INCOMPLETE);
        assertThat(mixed.toString()).doesNotContain(UTTERANCE);
    }

    // ------------------------------------------------------------------ helpers

    private static SegmentAgentResult terminalResult(
            DevelopmentSegmentAgentSourceProvider provider, Disposition disposition) {
        Clarification clarification = disposition == Disposition.NEEDS_INPUT ? clarification() : null;
        return SegmentAgentResult.publish(
                provider.skill().skillId(), provider.skill().skillVersion(), provider.skill().canonicalHash(),
                disposition, stateFor(disposition),
                disposition == Disposition.NEEDS_INPUT
                        ? "SEGMENT_NEEDS_INPUT"
                        : "SEGMENT_" + disposition.name(),
                clarification,
                CoreLoyaltyDevelopmentProfile.snapshot().contentHash(),
                DevelopmentSegmentRealHostFixture.hash("intent-t33"),
                null, null, null, null, List.of(), List.of(), null, null);
    }

    private static SegmentAgentState stateFor(Disposition disposition) {
        return switch (disposition) {
            case RESOLVED -> SegmentAgentState.READY_FOR_PREVIEW;
            case NEEDS_INPUT -> SegmentAgentState.NEEDS_INPUT;
            case UNSUPPORTED -> SegmentAgentState.UNSUPPORTED;
            case DENIED -> SegmentAgentState.DENIED;
            case INVALID -> SegmentAgentState.INVALID;
        };
    }

    /** A genuine T10 clarification built through the accepted factories over the development snapshot. */
    private static Clarification clarification() {
        ResolutionPins pins = new ResolutionPins(
                CoreLoyaltyDevelopmentProfile.SNAPSHOT_ID,
                CoreLoyaltyDevelopmentProfile.SNAPSHOT_VERSION,
                CoreLoyaltyDevelopmentProfile.snapshot().contentHash(),
                CoreLoyaltyDevelopmentProfile.resolutionPolicy().policyVersion(),
                CoreLoyaltyDevelopmentProfile.resolutionPolicy().contentHash(),
                CoreLoyaltyDevelopmentProfile.registeredDefaults().registryVersion(),
                CoreLoyaltyDevelopmentProfile.registeredDefaults().registryHash());
        String clauseId = "clause-tier";
        String optionId = ClarificationOption.deriveOptionId(clauseId, "member.current_tier");
        ResolutionId resolutionId = ResolutionId.derive(
                DevelopmentSegmentRealHostFixture.hash("intent-t33"), pins,
                QuestionCode.DISAMBIGUATE_DEFINITION, clauseId, List.of(optionId));
        return new Clarification(
                resolutionId, DevelopmentSegmentRealHostFixture.hash("intent-t33"), pins,
                QuestionCode.DISAMBIGUATE_DEFINITION, clauseId, "Which field should apply?",
                List.of(new ClarificationOption(optionId, "member.current_tier", "Current tier")));
    }
}
