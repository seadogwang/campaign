package com.company.campaign.dev.campaignagent;

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
import com.company.campaign.builder.segment.SegmentSchemaRepairPolicy;
import com.company.campaign.builder.skill.SegmentSkillDefinition;
import com.company.campaign.builder.skill.SegmentSkillLoader;
import com.company.campaign.builder.tool.SegmentPreviewPort;
import com.company.campaign.contracts.AgentEnvelope;
import com.company.campaign.contracts.ContractVersions;
import com.company.campaign.dev.segment.CoreLoyaltyDevelopmentProfile;
import com.company.campaign.metadata.domain.MetadataSnapshot;
import com.company.campaign.metadata.repository.InMemoryMetadataSnapshotRepository;
import com.company.campaign.metadata.service.MetadataQueryService;
import com.company.campaign.preview.PreviewResponse;
import com.company.campaign.query.plan.SemanticQueryPlan;
import com.company.campaign.security.authorization.AuthorizationService;
import com.company.campaign.security.context.TrustedContextProvider;
import com.company.campaign.security.context.TrustedExecutionContext;
import com.company.campaign.segment.dsl.contract.SegmentDsl;
import com.company.campaign.segment.resolution.ClarificationSelectionReceipt;
import com.company.campaign.segment.validation.SegmentValidationPolicy;
import com.company.campaign.segment.validation.SegmentValidationResult;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The real <strong>development</strong> Segment source of the opt-in local Campaign Agent host.
 *
 * <p>It is the one production-main implementation of the development Segment source boundary, and it composes
 * only accepted components: it builds the formal {@link SegmentAgentEnvironment} from the committed Skill and
 * its derived Tool catalog, the fixed development trusted context, the frozen Core Loyalty development profile,
 * the accepted Metadata query and authorization services, the accepted resolution and validation policies, the
 * shared {@code SegmentIntentModelPort} and exactly one host-owned {@link SegmentPreviewPort}. It then invokes
 * the genuine {@link SegmentAgentAdapter}.
 *
 * <p>It never parses the utterance itself, never synthesises a DSL, validation result, query plan, Preview
 * response or Agent result, and never keeps a pending-conversation map: every invocation builds a fresh,
 * run-local {@link StageCapture} observer and returns one immutable submission. Nothing here reads
 * a wall clock, a random source, a thread-local or global state; the compile instants come from the injected
 * {@link Clock}.
 *
 * <p>A resolved Agent result becomes {@link SegmentAuthoringSource.AiResolved} only after the captured
 * artifacts are present exactly once and re-proven coherent with every public Agent hash and pin, and the typed
 * {@link SegmentAuthoringProof} is assembled from those exact artifacts and the result Skill hash. Any missing,
 * duplicated, reordered or mismatched observation fails closed with the bounded
 * {@link DevelopmentCampaignAgentErrorCodes#SEGMENT_EVIDENCE_INCOMPLETE} code. A non-resolved Agent result
 * becomes {@link SegmentAuthoringSource.AiTerminal} and never fabricates a proof or a Preview.
 *
 * <p>Provider, model, Preview and observation failures are redacted: no raw exception text, prompt, model
 * output, Preview payload, URL, credential, identity or utterance is ever logged, thrown or echoed — every one
 * of them becomes one bounded {@link CampaignSegmentSubmission.Unavailable} code.
 *
 * <p>The host owns the {@link SegmentPreviewPort} it injects, so the genuine Preview must run under the same
 * fixed trusted context this provider pins (the same Metadata snapshot, instant, timezone and data watermark).
 * A Preview wired over a different context fails the authoring compile closed rather than producing an
 * incoherent artifact.
 */
public final class DevelopmentSegmentAgentSourceProvider implements DevelopmentSegmentSourceProvider {

    /** The deterministic, server-owned {@code clientRef} the local Segment slice creates under. */
    public static final String SEGMENT_CLIENT_REF = "campaign-segment";

    /** The bounded lifetime of the development compile instants; never longer than the accepted maximum. */
    private static final Duration PLAN_LIFETIME = Duration.ofMinutes(30);

    private final SegmentAgentEnvironment environment;
    private final SegmentSkillDefinition skill;
    private final Clock clock;
    private final TrustedContextProvider trustedContextProvider;

    private DevelopmentSegmentAgentSourceProvider(
            SegmentAgentEnvironment environment,
            SegmentSkillDefinition skill,
            Clock clock,
            TrustedContextProvider trustedContextProvider) {
        this.environment = environment;
        this.skill = skill;
        this.clock = clock;
        this.trustedContextProvider = trustedContextProvider;
    }

    /**
     * Builds the one real development Segment source over the accepted development profile.
     *
     * @param modelPort  the shared Segment intent model; the same object the Campaign host uses as transport
     * @param previewPort exactly one host-owned accepted Safe Preview port
     * @param clock      the injected development clock; the only time source of a compile
     * @param identity   the fixed development trusted-context provider
     */
    public static DevelopmentSegmentAgentSourceProvider of(
            SegmentIntentModelPort modelPort,
            SegmentPreviewPort previewPort,
            Clock clock,
            TrustedContextProvider identity) {
        return of(modelPort, previewPort, clock, identity, SegmentSchemaRepairPolicy.DISABLED);
    }

    /** Builds the real development Segment source with the host-selected bounded schema-repair policy. */
    public static DevelopmentSegmentAgentSourceProvider of(
            SegmentIntentModelPort modelPort,
            SegmentPreviewPort previewPort,
            Clock clock,
            TrustedContextProvider identity,
            SegmentSchemaRepairPolicy repairPolicy) {
        Objects.requireNonNull(modelPort, "modelPort");
        Objects.requireNonNull(previewPort, "previewPort");
        Objects.requireNonNull(clock, "clock");
        Objects.requireNonNull(identity, "identity");
        repairPolicy = repairPolicy == null ? SegmentSchemaRepairPolicy.DISABLED : repairPolicy;
        SegmentSkillDefinition skill = new SegmentSkillLoader().loadDefault();
        AuthorizationService authorizationService = CoreLoyaltyDevelopmentProfile.authorizationService();
        MetadataQueryService metadataQueryService = metadataQueryService(authorizationService);
        SegmentAgentEnvironment base = SegmentAgentEnvironment.of(
                skill,
                identity,
                DevelopmentSegmentAgentSourceProvider::pinnedSnapshot,
                metadataQueryService,
                authorizationService,
                modelPort,
                CoreLoyaltyDevelopmentProfile.resolutionPolicy(),
                CoreLoyaltyDevelopmentProfile.registeredDefaults(),
                SegmentValidationPolicy.standard(),
                previewPort,
                null,
                null)
                .withRepairPolicy(repairPolicy);
        return new DevelopmentSegmentAgentSourceProvider(base, skill, clock, identity);
    }

    /** The bounded diagnostic facts of this provider; no credential, URL, identity or model name. */
    @Override
    public String toString() {
        return "DevelopmentSegmentAgentSourceProvider[devOnly=true, skill=" + skill.skillId()
                + '@' + skill.skillVersion() + ']';
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
                    DevelopmentCampaignAgentErrorCodes.SEGMENT_SOURCE_FAILED);
        }
        try {
            return submissionOf(observation.result(), observation.capture());
        } catch (RuntimeException failure) {
            // A proof that cannot be re-proven is never partially returned.
            return CampaignSegmentSubmission.unavailable(
                    DevelopmentCampaignAgentErrorCodes.SEGMENT_EVIDENCE_INCOMPLETE);
        }
    }

    /**
     * Runs one genuine Agent invocation under a fresh, run-local observation.
     *
     * <p>The adapter is built per invocation, so no observation state can survive one run into the next. The
     * pair is package-visible so the evidence boundary — a resolved result whose observation is incomplete or
     * mixed — can be exercised directly.
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
     *
     * <p>Package-visible so the evidence boundary itself is directly testable: a resolved result whose
     * observation is incomplete must fail closed rather than produce a proof.
     */
    CampaignSegmentSubmission submissionOf(SegmentAgentResult result, StageCapture capture) {
        if (result == null) {
            return CampaignSegmentSubmission.unavailable(
                    DevelopmentCampaignAgentErrorCodes.SEGMENT_SOURCE_FAILED);
        }
        if (!result.isReadyForPreview()) {
            SegmentAuthoringSource terminal = new SegmentAuthoringSource.AiTerminal(result);
            return CampaignSegmentSubmission.of(
                    new CampaignSegmentSource(terminal, terminalRequest(terminal)));
        }
        SegmentAuthoringProof proof = proofOf(result, capture);
        if (proof == null) {
            return CampaignSegmentSubmission.unavailable(
                    DevelopmentCampaignAgentErrorCodes.SEGMENT_EVIDENCE_INCOMPLETE);
        }
        SegmentAuthoringSource source = new SegmentAuthoringSource.AiResolved(result, proof);
        return CampaignSegmentSubmission.of(
                new CampaignSegmentSource(source, resolvedRequest(source, result, proof)));
    }

    /**
     * Re-proves the captured artifacts against every public hash and pin of the genuine result.
     *
     * <p>Every artifact must be present exactly once, in the accepted stage order, and must carry the exact
     * DSL, canonical AST, plan, Preview card and Skill pins the result publishes. A disagreement — a mixed
     * artifact from another run, a stale card or a missing observation — returns null, so no proof is ever
     * assembled from evidence that does not describe one Segment.
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
        MetadataSnapshot snapshot = CoreLoyaltyDevelopmentProfile.snapshot();
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
                        List.of(DevelopmentCampaignAgentAssets.segmentReadiness()),
                        List.of(new SegmentAuthoringInputs(
                                CoreLoyaltyDevelopmentProfile.snapshot(), proof.previewCard(),
                                result.skillHash()))));
    }

    /**
     * The CREATE-only submission of one non-resolved run.
     *
     * <p>A terminal source carries no proof, so this request pins the frozen readiness fact alone and invents
     * no Preview card, snapshot or Skill hash it does not have. The accepted integration refuses a terminal
     * source before any compile could consult them.
     */
    private SegmentAuthoringRequest terminalRequest(SegmentAuthoringSource source) {
        return request(
                source,
                new CapabilityCompileContext(
                        List.of(DevelopmentCampaignAgentAssets.segmentReadiness()),
                        List.of()));
    }

    /**
     * One CREATE Segment authoring request over the frozen development catalog, policy and context.
     *
     * <p>It is the same frozen catalog, policy and trusted context the Campaign Tool run context and the
     * assembly context are built from, so a pin mismatch fails the compile closed instead of silently staging
     * a component against different frozen inputs. The compile instants come from the injected clock, and the
     * identity is the same deterministic server-owned {@code clientRef} the Campaign host defaults to.
     */
    private SegmentAuthoringRequest request(
            SegmentAuthoringSource source, CapabilityCompileContext capabilityContext) {
        Instant compileAt = clock.instant();
        return new SegmentAuthoringRequest(
                source,
                ProposalIdentity.ofClientRef(SEGMENT_CLIENT_REF),
                null,
                DevelopmentCampaignAgentAssets.capabilityCatalog(),
                DevelopmentCampaignAgentAssets.authoringPolicy(),
                new CompileInstants(compileAt, compileAt.plus(PLAN_LIFETIME)),
                trustedContextProvider.trustedContext(),
                capabilityContext);
    }

    // ------------------------------------------------------------------ fixed development inputs

    /**
     * The exact permitted snapshot of one run, or null when the trusted context does not pin it.
     *
     * <p>The adapter re-verifies this identifier against the trusted context pin, so returning null here makes
     * a drifting context fail closed rather than resolving a different snapshot.
     */
    private static MetadataSnapshot pinnedSnapshot(TrustedExecutionContext context) {
        if (context == null || context.snapshotRef() == null
                || !CoreLoyaltyDevelopmentProfile.SNAPSHOT_ID.equals(
                        context.snapshotRef().metadataSnapshotId())) {
            return null;
        }
        return CoreLoyaltyDevelopmentProfile.snapshot();
    }

    /** The accepted Metadata query service over a repository holding the exact pinned development snapshot. */
    private static MetadataQueryService metadataQueryService(AuthorizationService authorizationService) {
        InMemoryMetadataSnapshotRepository repository = new InMemoryMetadataSnapshotRepository();
        repository.publish(CoreLoyaltyDevelopmentProfile.snapshot());
        return new MetadataQueryService(authorizationService, repository);
    }

    /**
     * The formal Agent envelope of one invocation.
     *
     * <p>The run identity is the bounded Campaign run identity, reused deterministically; the utterance is the
     * original one a continuation replays, and the receipts are the exact opaque T10 selections already
     * validated against the pending clarification. Nothing here is derived from a model payload.
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
     * condition rather than overwriting the first observation, and {@link #isComplete()} requires every
     * artifact the accepted stage order produces exactly once.
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

    /** The exact Skill definition this provider runs, for bounded wiring assertions. */
    SegmentSkillDefinition skill() {
        return skill;
    }
}
