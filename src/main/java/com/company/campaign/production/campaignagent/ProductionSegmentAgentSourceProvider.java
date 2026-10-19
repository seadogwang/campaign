package com.company.campaign.production.campaignagent;

import com.company.campaign.builder.agent.GroundedExplanation;
import com.company.campaign.builder.agent.SegmentAgentAdapter;
import com.company.campaign.builder.agent.SegmentAgentEnvironment;
import com.company.campaign.builder.agent.SegmentAgentRequest;
import com.company.campaign.builder.agent.SegmentAgentResult;
import com.company.campaign.builder.agent.SegmentPreviewCard;
import com.company.campaign.builder.agent.SegmentStageObserver;
import com.company.campaign.builder.authoring.SegmentAuthoringException;
import com.company.campaign.builder.authoring.SegmentAuthoringInputs;
import com.company.campaign.builder.authoring.SegmentAuthoringProof;
import com.company.campaign.builder.authoring.SegmentAuthoringRequest;
import com.company.campaign.builder.authoring.SegmentAuthoringSource;
import com.company.campaign.builder.campaignagent.CampaignSegmentContinuation;
import com.company.campaign.builder.campaignagent.CampaignSegmentSource;
import com.company.campaign.builder.campaignagent.CampaignSegmentSubmission;
import com.company.campaign.builder.compiler.CapabilityCompileContext;
import com.company.campaign.builder.compiler.CompileInstants;
import com.company.campaign.builder.proposal.ProposalIdentity;
import com.company.campaign.builder.segment.SegmentIntentModelPort;
import com.company.campaign.builder.skill.SegmentSkillDefinition;
import com.company.campaign.builder.tool.SegmentPreviewPort;
import com.company.campaign.campaignagent.CampaignSegmentSourceProvider;
import com.company.campaign.contracts.AgentEnvelope;
import com.company.campaign.contracts.ContractVersions;
import com.company.campaign.metadata.domain.MetadataSnapshot;
import com.company.campaign.preview.PreviewResponse;
import com.company.campaign.query.plan.SemanticQueryPlan;
import com.company.campaign.security.context.TrustedContextProvider;
import com.company.campaign.segment.dsl.contract.SegmentDsl;
import com.company.campaign.segment.resolution.ClarificationSelectionReceipt;
import com.company.campaign.segment.validation.SegmentValidationResult;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The real <strong>production</strong> Segment source of the production Campaign Agent host.
 *
 * <p>It composes only accepted components and only host-owned production ports: the formal
 * {@link SegmentAgentEnvironment} built by {@link ProductionSegmentAssets} over the one production structured
 * model and the one genuine {@link SegmentPreviewPort}, the committed Skill definition, the host production
 * assets and exactly the pinned Metadata snapshot the host bound into the Agent environment. It then invokes the
 * genuine {@link SegmentAgentAdapter}.
 *
 * <p>It never parses the utterance itself, never synthesises a DSL, validation result, query plan, Preview
 * response or Agent result, and never keeps a pending-conversation map: every invocation builds a fresh,
 * run-local {@link StageCapture} observer and returns one immutable submission. Nothing here reads a wall clock,
 * a random source, a thread-local or global state; the compile instants come from the injected {@link Clock}.
 *
 * <p>A resolved Agent result becomes {@link SegmentAuthoringSource.AiResolved} only after the captured artifacts
 * are present exactly once and re-proven coherent with every public Agent hash and pin, and the typed
 * {@link SegmentAuthoringProof} is assembled from those exact artifacts and the result Skill hash. Any missing,
 * duplicated, reordered or mismatched observation fails closed with the bounded
 * {@link ProductionCampaignAgentErrorCodes#SEGMENT_EVIDENCE_INCOMPLETE} code. A non-resolved Agent result becomes
 * {@link SegmentAuthoringSource.AiTerminal} and never fabricates a proof or a Preview.
 *
 * <p>Provider, model, Preview and observation failures are redacted: no raw exception text, prompt, model
 * output, Preview payload, URL, credential, identity or utterance is ever logged, thrown or echoed — every one
 * of them becomes one bounded {@link CampaignSegmentSubmission.Unavailable} code.
 */
public final class ProductionSegmentAgentSourceProvider implements CampaignSegmentSourceProvider {

    /** The deterministic, server-owned {@code clientRef} the production Segment slice authors under. */
    public static final String SEGMENT_CLIENT_REF = "campaign-segment";

    /** The bounded lifetime of the production compile instants; never longer than the accepted maximum. */
    private static final Duration PLAN_LIFETIME = Duration.ofMinutes(30);

    private final SegmentAgentEnvironment environment;
    private final SegmentSkillDefinition skill;
    private final MetadataSnapshot snapshot;
    private final ProductionCampaignAssets campaignAssets;
    private final Clock clock;
    private final TrustedContextProvider trustedContextProvider;

    private ProductionSegmentAgentSourceProvider(
            SegmentAgentEnvironment environment,
            SegmentSkillDefinition skill,
            MetadataSnapshot snapshot,
            ProductionCampaignAssets campaignAssets,
            Clock clock,
            TrustedContextProvider trustedContextProvider) {
        this.environment = environment;
        this.skill = skill;
        this.snapshot = snapshot;
        this.campaignAssets = campaignAssets;
        this.clock = clock;
        this.trustedContextProvider = trustedContextProvider;
    }

    /**
     * Builds the one real production Segment source.
     *
     * @param segmentAssets the host Segment assets that bind the model and Preview into the formal Agent env
     * @param campaignAssets the host production assets that pin the capability catalog, policy and readiness
     * @param modelPort     the one production structured model; the same object the Campaign host uses
     * @param previewPort   exactly one genuine Preview port
     * @param clock         the injected clock; the only time source of a compile
     * @param identity      the production trusted-context provider of the request boundary
     */
    public static ProductionSegmentAgentSourceProvider of(
            ProductionSegmentAssets segmentAssets,
            ProductionCampaignAssets campaignAssets,
            SegmentIntentModelPort modelPort,
            SegmentPreviewPort previewPort,
            Clock clock,
            TrustedContextProvider identity) {
        Objects.requireNonNull(segmentAssets, "segmentAssets");
        Objects.requireNonNull(campaignAssets, "campaignAssets");
        Objects.requireNonNull(modelPort, "modelPort");
        Objects.requireNonNull(previewPort, "previewPort");
        Objects.requireNonNull(clock, "clock");
        Objects.requireNonNull(identity, "identity");
        SegmentSkillDefinition skill = Objects.requireNonNull(segmentAssets.segmentSkill(), "segmentSkill");
        MetadataSnapshot snapshot = Objects.requireNonNull(segmentAssets.snapshot(), "snapshot");
        SegmentAgentEnvironment environment =
                segmentAssets.segmentAgentEnvironment(modelPort, previewPort, identity);
        Objects.requireNonNull(environment, "segmentAgentEnvironment");
        return new ProductionSegmentAgentSourceProvider(
                environment, skill, snapshot, campaignAssets, clock, identity);
    }

    /** The bounded diagnostic facts of this provider; no credential, URL, identity or model name. */
    @Override
    public String toString() {
        return "ProductionSegmentAgentSourceProvider[provenance=" + campaignAssets.provenance()
                + ", skill=" + skill.skillId() + '@' + skill.skillVersion() + ']';
    }

    // ------------------------------------------------------------------ the source boundary

    @Override
    public CampaignSegmentSubmission source(String conversationId, String runId, String utterance) {
        return source(conversationId, runId, utterance, null);
    }

    @Override
    public CampaignSegmentSubmission source(
            String conversationId,
            String runId,
            String utterance,
            CampaignSegmentContinuation continuation) {
        Observation observation;
        try {
            observation = observe(conversationId, runId, utterance, continuation);
        } catch (RuntimeException failure) {
            return CampaignSegmentSubmission.unavailable(
                    ProductionCampaignAgentErrorCodes.SEGMENT_SOURCE_FAILED);
        }
        try {
            return submissionOf(observation.result(), observation.capture());
        } catch (RuntimeException failure) {
            // A proof that cannot be re-proven is never partially returned.
            return CampaignSegmentSubmission.unavailable(
                    ProductionCampaignAgentErrorCodes.SEGMENT_EVIDENCE_INCOMPLETE);
        }
    }

    /**
     * Runs one genuine Agent invocation under a fresh, run-local observation.
     *
     * <p>The adapter is built per invocation, so no observation state can survive one run into the next.
     */
    Observation observe(
            String conversationId,
            String runId,
            String utterance,
            CampaignSegmentContinuation continuation) {
        StageCapture capture = new StageCapture();
        SegmentAgentResult result = new SegmentAgentAdapter(environment.withStageObserver(capture))
                .invoke(envelope(conversationId, runId, utterance, continuation));
        return new Observation(result, capture);
    }

    /** One genuine Agent result together with the exact artifacts its run observed. */
    record Observation(SegmentAgentResult result, StageCapture capture) {

        Observation {
            Objects.requireNonNull(result, "result");
            Objects.requireNonNull(capture, "capture");
        }
    }

    /**
     * Builds the bounded submission of one genuine Agent result and its captured artifacts.
     */
    CampaignSegmentSubmission submissionOf(SegmentAgentResult result, StageCapture capture) {
        if (result == null) {
            return CampaignSegmentSubmission.unavailable(
                    ProductionCampaignAgentErrorCodes.SEGMENT_SOURCE_FAILED);
        }
        if (!result.isReadyForPreview()) {
            SegmentAuthoringSource terminal = new SegmentAuthoringSource.AiTerminal(result);
            return CampaignSegmentSubmission.of(
                    new CampaignSegmentSource(terminal, terminalRequest(terminal)));
        }
        SegmentAuthoringProof proof = proofOf(result, capture);
        if (proof == null) {
            return CampaignSegmentSubmission.unavailable(
                    ProductionCampaignAgentErrorCodes.SEGMENT_EVIDENCE_INCOMPLETE);
        }
        SegmentAuthoringSource source = new SegmentAuthoringSource.AiResolved(result, proof);
        return CampaignSegmentSubmission.of(
                new CampaignSegmentSource(source, resolvedRequest(source, result, proof)));
    }

    /**
     * Re-proves the captured artifacts against every public hash and pin of the genuine result.
     *
     * <p>Every artifact must be present exactly once, in the accepted stage order, and must carry the exact DSL,
     * canonical AST, plan, Preview card and Skill pins the result publishes, and the result's Metadata snapshot
     * hash must be the one the host pinned. A disagreement — a mixed artifact from another run, a stale card, a
     * drifted snapshot or a missing observation — returns null, so no proof is ever assembled from evidence that
     * does not describe one Segment.
     */
    private SegmentAuthoringProof proofOf(SegmentAgentResult result, StageCapture capture) {
        if (capture == null || !capture.isComplete()) {
            return null;
        }
        SegmentDsl document = capture.document();
        SegmentValidationResult validation = capture.validation();
        SemanticQueryPlan plan = capture.plan();
        PreviewResponse preview = capture.previewResponse();
        SegmentPreviewCard card = capture.previewCard();
        if (!Objects.equals(result.skillId(), skill.skillId())
                || !Objects.equals(result.skillVersion(), skill.skillVersion())
                || !Objects.equals(result.skillHash(), skill.canonicalHash())
                || !Objects.equals(result.metadataSnapshotHash(), snapshot.contentHash())
                || !Objects.equals(document.metadata().contentHash(), result.metadataSnapshotHash())
                || !Objects.equals(document.editableDslHash(), result.dslEditableHash())
                || !Objects.equals(validation.canonicalAstHash(), result.canonicalAstHash())
                || !Objects.equals(validation.editableDslHash(), document.editableDslHash())
                || !Objects.equals(plan.planHash(), result.planHash())
                || !Objects.equals(plan.canonicalAstHash(), validation.canonicalAstHash())
                || !Objects.equals(card, result.previewCard())
                || !Objects.equals(card.previewHash(), result.previewHash())
                || !Objects.equals(card.skillHash(), result.skillHash())
                || !Objects.equals(card.metadataHash(), result.metadataSnapshotHash())
                || !Objects.equals(card.dslHash(), document.editableDslHash())
                || !Objects.equals(card.canonicalAstHash(), validation.canonicalAstHash())
                || !Objects.equals(card.planHash(), plan.planHash())
                || !Objects.equals(preview.contentHash(), card.previewHash())
                || !Objects.equals(preview.status(), card.status())
                || preview.matchedCount() != card.matchedCount()
                || !Objects.equals(preview.conditionStats(), card.conditionStatistics())
                || !Objects.equals(preview.sample(), card.sampleTokens())
                || !Objects.equals(
                        preview.executionContext().metadataSnapshotId(), card.metadataSnapshotId())
                || !Objects.equals(preview.executionContext().asOf(), card.asOf())
                || !Objects.equals(preview.executionContext().timezone(), card.timezone())
                || !Objects.equals(preview.executionContext().dataWatermark(), card.dataWatermark())) {
            return null;
        }
        try {
            return new SegmentAuthoringProof(document, validation, plan, card, result.skillHash());
        } catch (SegmentAuthoringException incoherent) {
            return null;
        }
    }

    // ------------------------------------------------------------------ the frozen request

    /** The CREATE-only submission of one resolved run, pinned to the exact proof it carries. */
    private SegmentAuthoringRequest resolvedRequest(
            SegmentAuthoringSource source, SegmentAgentResult result, SegmentAuthoringProof proof) {
        return request(
                source,
                new CapabilityCompileContext(
                        List.of(campaignAssets.segmentReadiness()),
                        List.of(new SegmentAuthoringInputs(
                                snapshot, proof.previewCard(), result.skillHash()))));
    }

    /**
     * The CREATE-only submission of one non-resolved run.
     *
     * <p>A terminal source carries no proof, so this request pins the frozen readiness fact alone and invents no
     * Preview card, snapshot or Skill hash it does not have. The accepted integration refuses a terminal source
     * before any compile could consult them.
     */
    private SegmentAuthoringRequest terminalRequest(SegmentAuthoringSource source) {
        return request(
                source,
                new CapabilityCompileContext(
                        List.of(campaignAssets.segmentReadiness()),
                        List.of()));
    }

    /**
     * One CREATE Segment authoring request over the host production catalog, policy and context.
     *
     * <p>The compile instants come from the injected clock, and the identity is the same deterministic
     * server-owned {@code clientRef} the Campaign host defaults to.
     */
    private SegmentAuthoringRequest request(
            SegmentAuthoringSource source, CapabilityCompileContext capabilityContext) {
        Instant compileAt = clock.instant();
        return new SegmentAuthoringRequest(
                source,
                ProposalIdentity.ofClientRef(SEGMENT_CLIENT_REF),
                null,
                campaignAssets.capabilityCatalog(),
                campaignAssets.authoringPolicy(),
                new CompileInstants(compileAt, compileAt.plus(PLAN_LIFETIME)),
                trustedContextProvider.trustedContext(),
                capabilityContext);
    }

    // ------------------------------------------------------------------ the formal envelope

    /**
     * The formal Agent envelope of one invocation.
     *
     * <p>The run identity is the bounded Campaign run identity, reused deterministically; the utterance is the
     * original one a continuation replays, and the receipts are the exact opaque T10 selections already validated
     * against the pending clarification. Nothing here is derived from a model payload.
     */
    private static AgentEnvelope<SegmentAgentRequest> envelope(
            String conversationId, String runId, String utterance, CampaignSegmentContinuation continuation) {
        String replayed = continuation == null ? utterance : continuation.utterance();
        List<ClarificationSelectionReceipt> receipts =
                continuation == null ? List.of() : continuation.receipts();
        return new AgentEnvelope<>(
                ContractVersions.CURRENT, runId, conversationId,
                new SegmentAgentRequest(replayed, receipts));
    }

    /**
     * The fresh, run-local observation of one Agent invocation.
     *
     * <p>It captures only already-accepted typed artifacts, never raw provider output, and it cannot change a
     * stage result: every method returns {@code void}. A duplicated callback is recorded as a fail-closed
     * condition rather than overwriting the first observation, and {@link #isComplete()} requires every artifact
     * the accepted stage order produces exactly once.
     */
    static final class StageCapture implements SegmentStageObserver {

        private static final List<String> EXPECTED_ORDER = List.of("dsl", "validation", "plan", "preview");

        private final List<String> order = new ArrayList<>(EXPECTED_ORDER.size());
        private SegmentDsl document;
        private SegmentValidationResult validation;
        private SemanticQueryPlan plan;
        private PreviewResponse previewResponse;
        private SegmentPreviewCard previewCard;
        private GroundedExplanation explanation;
        private boolean duplicated;

        @Override
        public void dslCompiled(SegmentDsl compiled) {
            duplicated |= document != null || compiled == null;
            document = document == null ? compiled : document;
            order.add("dsl");
        }

        @Override
        public void validationAccepted(SegmentValidationResult result) {
            duplicated |= validation != null || result == null;
            validation = validation == null ? result : validation;
            order.add("validation");
        }

        @Override
        public void planAccepted(SemanticQueryPlan accepted) {
            duplicated |= plan != null || accepted == null;
            plan = plan == null ? accepted : plan;
            order.add("plan");
        }

        @Override
        public void previewAccepted(PreviewResponse response, SegmentPreviewCard card) {
            duplicated |= previewCard != null || response == null || card == null;
            previewResponse = previewResponse == null ? response : previewResponse;
            previewCard = previewCard == null ? card : previewCard;
            order.add("preview");
        }

        @Override
        public void explanationAccepted(GroundedExplanation accepted) {
            explanation = explanation == null ? accepted : explanation;
        }

        SegmentDsl document() {
            return document;
        }

        SegmentValidationResult validation() {
            return validation;
        }

        SemanticQueryPlan plan() {
            return plan;
        }

        SegmentPreviewCard previewCard() {
            return previewCard;
        }

        PreviewResponse previewResponse() {
            return previewResponse;
        }

        /** True when every required artifact was observed exactly once, in the accepted stage order. */
        boolean isComplete() {
            return !duplicated
                    && document != null && validation != null && plan != null
                    && previewResponse != null && previewCard != null && explanation != null
                    && order.equals(EXPECTED_ORDER);
        }
    }
}