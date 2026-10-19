package com.company.campaign.builder.authoring.testsupport;

import com.company.campaign.builder.agent.AgentTransition;
import com.company.campaign.builder.agent.GroundedExplanation;
import com.company.campaign.builder.agent.SegmentAgentResult;
import com.company.campaign.builder.agent.SegmentAgentState;
import com.company.campaign.builder.agent.SegmentPreviewCard;
import com.company.campaign.builder.authoring.SegmentAuthoringInputs;
import com.company.campaign.builder.authoring.SegmentAuthoringProof;
import com.company.campaign.builder.authoring.SegmentAuthoringReferenceContext;
import com.company.campaign.builder.authoring.SegmentAuthoringReferencePins;
import com.company.campaign.builder.authoring.SegmentAuthoringReferenceReadiness;
import com.company.campaign.builder.authoring.SegmentAuthoringRequest;
import com.company.campaign.builder.authoring.SegmentAuthoringSource;
import com.company.campaign.builder.compiler.CapabilityCompileContext;
import com.company.campaign.builder.compiler.CapabilityReadiness;
import com.company.campaign.builder.compiler.FrozenCapabilityCatalog;
import com.company.campaign.builder.compiler.TrustedCurrentCampaign;
import com.company.campaign.builder.proposal.ProposalIdentity;
import com.company.campaign.builder.refine.SegmentRefinementResult;
import com.company.campaign.builder.refine.SegmentRefinementState;
import com.company.campaign.builder.testsupport.BuilderFixtures;
import com.company.campaign.builder.tool.SegmentToolCallRecord;
import com.company.campaign.contracts.Disposition;
import com.company.campaign.metadata.domain.ComparisonOperator;
import com.company.campaign.segment.dsl.contract.SegmentDsl;
import com.company.campaign.segment.dsl.predicate.Predicate;
import com.company.campaign.segment.dsl.window.RollingWindow;
import com.company.campaign.segment.dsl.window.WindowUnit;
import com.company.campaign.segment.reference.AudienceVersionRef;
import com.company.campaign.segment.reference.InMemoryAudienceVersionCatalog;
import com.company.campaign.segment.reference.ReferenceExecutionContext;
import com.company.campaign.segment.reference.testsupport.SegmentReferenceFixtures;
import com.company.campaign.segment.resolution.Clarification;
import com.company.campaign.segment.resolution.ClarificationOption;
import com.company.campaign.segment.resolution.QuestionCode;
import com.company.campaign.segment.resolution.ResolutionId;
import com.company.campaign.segment.resolution.ResolutionPins;
import com.company.campaign.segment.validation.SegmentValidationResult;
import com.company.campaign.segment.validation.testsupport.SegmentValidationFixtures;
import com.company.campaign.query.plan.SegmentQueryPlanner;
import com.company.campaign.query.plan.SemanticQueryPlan;
import com.company.campaign.query.plan.SemanticQueryPlanCodec;
import com.company.campaign.query.testsupport.ReferenceQueryFixtures;
import com.company.campaign.shared.canonical.ContentHash;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Deterministic T16 integration fixtures: three proof-bound server-owned sources, each paired with the exact
 * typed artifact it claims.
 *
 * <p>Every artifact is derived from the genuine accepted T05/T07/T08/T11/T12 boundaries, so a test that
 * mutates one pin mutates a real artifact. No clock, network, database, model, provider, random identifier or
 * T06 corpus asset is touched.
 */
public final class SegmentAuthoringIntegrationFixtures {

    /** A bounded, opaque Intent hash; presentation-only and never part of the Segment semantics. */
    public static final ContentHash INTENT_HASH =
            ContentHash.sha256("authoring-intent-1".getBytes(StandardCharsets.UTF_8));

    /** A second Intent hash, so two AI runs of the same resolution are still distinguishable by source. */
    public static final ContentHash OTHER_INTENT_HASH =
            ContentHash.sha256("authoring-intent-2".getBytes(StandardCharsets.UTF_8));

    /** The refined Preview hash, distinct from the base one, so a refreshed card is observable. */
    public static final ContentHash REFINED_PREVIEW_HASH =
            ContentHash.sha256("authoring-preview-refined-1".getBytes(StandardCharsets.UTF_8));

    /** The accepted refinement patch fingerprint; opaque and never part of the Segment semantics. */
    public static final ContentHash PATCH_FINGERPRINT =
            ContentHash.sha256("authoring-patch-1".getBytes(StandardCharsets.UTF_8));

    private static final String SKILL_ID = "segment.audience.skill";
    private static final String SKILL_VERSION = "1.0";

    private SegmentAuthoringIntegrationFixtures() {
    }

    // ------------------------------------------------------------------ typed proofs

    /** The proof of the accepted baseline document. */
    public static SegmentAuthoringProof proof() {
        return proofOf(SegmentAuthoringFixtures.document());
    }

    /**
     * An independently built proof of the same typed baseline Segment.
     *
     * <p>It is a distinct proof over distinct artifact instances — document, validation, semantic plan and
     * Preview card are each derived again from the genuine accepted boundaries — so an AI/Visual equivalence
     * test cannot pass merely by sharing one object between the two sources. Only the typed meaning is equal.
     */
    public static SegmentAuthoringProof independentProof() {
        return proofOf(SegmentAuthoringFixtures.document());
    }

    /** Derives the whole artifact chain of one document, so a proof is never assembled from a shared instance. */
    private static SegmentAuthoringProof proofOf(SegmentDsl document) {
        SegmentValidationResult validation = SegmentAuthoringFixtures.validator().validate(
                document, SegmentAuthoringFixtures.snapshot(), SegmentAuthoringFixtures.context(),
                SegmentAuthoringFixtures.validationPolicy());
        SemanticQueryPlan plan = SegmentAuthoringFixtures.planner()
                .plan(validation, SegmentAuthoringFixtures.snapshot(), SegmentAuthoringFixtures.context());
        SegmentPreviewCard card = SegmentAuthoringFixtures.cardBuilder()
                .dslHash(document.editableDslHash())
                .canonicalAstHash(validation.canonicalAstHash())
                .planHash(plan.planHash())
                .build();
        return new SegmentAuthoringProof(document, validation, plan, card, SegmentAuthoringFixtures.SKILL_HASH);
    }

    /** A second, genuinely different accepted document under the same Snapshot: the refined shape. */
    public static SegmentDsl refinedDocument() {
        Predicate predicate = SegmentValidationFixtures.all("n-root", List.of(
                SegmentValidationFixtures.tierEquals("n-tier", "GOLD"),
                SegmentValidationFixtures.metric(
                        "n-spend", "order.net_paid_amount", new RollingWindow(30, WindowUnit.DAY),
                        ComparisonOperator.GREATER_THAN,
                        SegmentValidationFixtures.value(
                                SegmentValidationFixtures.moneyValue("2000.00", "CNY")))));
        return SegmentValidationFixtures.publish(predicate);
    }

    public static SegmentValidationResult refinedValidation() {
        return SegmentValidationFixtures.validator().validate(
                refinedDocument(), SegmentAuthoringFixtures.snapshot(), SegmentAuthoringFixtures.context(),
                SegmentAuthoringFixtures.validationPolicy());
    }

    public static SemanticQueryPlan refinedPlan() {
        return new SegmentQueryPlanner(SegmentValidationFixtures.allowAll())
                .plan(refinedValidation(), SegmentAuthoringFixtures.snapshot(),
                        SegmentAuthoringFixtures.context());
    }

    public static SegmentPreviewCard refinedCard() {
        return SegmentAuthoringFixtures.cardBuilder()
                .matchedCount(7)
                .previewHash(REFINED_PREVIEW_HASH)
                .planHash(refinedPlan().planHash())
                .dslHash(refinedDocument().editableDslHash())
                .canonicalAstHash(refinedValidation().canonicalAstHash())
                .build();
    }

    /** The proof of the refined document, bound to its own Preview evidence. */
    public static SegmentAuthoringProof refinedProof() {
        return new SegmentAuthoringProof(
                refinedDocument(), refinedValidation(), refinedPlan(), refinedCard(),
                SegmentAuthoringFixtures.SKILL_HASH);
    }

    // ------------------------------------------------------------------ reference-bearing artifacts (T18 §7)

    /** The hash of the dedicated reference Gate inputs every reference-bearing authoring fixture ran against. */
    public static final ContentHash REFERENCE_INPUTS_HASH =
            ContentHash.sha256("authoring-reference-inputs-1".getBytes(StandardCharsets.UTF_8));

    /** The one standard referenced immutable version. */
    public static AudienceVersionRef referenceVersion() {
        return ReferenceQueryFixtures.version();
    }

    /** A host catalog holding exactly the standard referenced version. */
    public static InMemoryAudienceVersionCatalog referenceCatalog() {
        return ReferenceQueryFixtures.catalogOf(referenceVersion());
    }

    /** A host catalog holding no version at all, so a document's reference cannot be resolved. */
    public static InMemoryAudienceVersionCatalog emptyReferenceCatalog() {
        return ReferenceQueryFixtures.catalogWith();
    }

    /** The host reference ports for validation, planning and execution over the standard catalog. */
    public static ReferenceExecutionContext referenceExecutionContext() {
        return ReferenceQueryFixtures.executionContext(referenceCatalog());
    }

    /** The host reference ports over a catalog that cannot resolve the referenced version. */
    public static ReferenceExecutionContext unresolvableReferenceExecutionContext() {
        return ReferenceQueryFixtures.executionContext(emptyReferenceCatalog());
    }

    /** The base reference-bearing Segment: exactly one reference to the standard immutable version. */
    public static SegmentDsl referenceDocument() {
        return ReferenceQueryFixtures.referenceDocument(referenceVersion());
    }

    /** The refined reference-bearing Segment: the same reference plus one more predicate. */
    public static SegmentDsl refinedReferenceDocument() {
        return ReferenceQueryFixtures.document(SegmentValidationFixtures.all("n-root", List.of(
                ReferenceQueryFixtures.reference("n-ref", referenceVersion()),
                SegmentValidationFixtures.tierEquals("n-tier", "GOLD"))));
    }

    /** The proof of the base reference-bearing Segment. */
    public static SegmentAuthoringProof referenceProof() {
        return referenceProofOf(referenceDocument(), SegmentAuthoringFixtures.PREVIEW_HASH);
    }

    /** The proof of the refined reference-bearing Segment, bound to its own refreshed Preview hash. */
    public static SegmentAuthoringProof refinedReferenceProof() {
        return referenceProofOf(refinedReferenceDocument(), REFINED_PREVIEW_HASH);
    }

    /**
     * Derives a reference-bearing proof entirely from the genuine reference-aware boundaries.
     *
     * <p>The document is validated through the five-argument reference entry point and planned through the
     * four-argument entry point, so the proof's canonical AST, plan and card are the ones a reference-bearing
     * compile must re-derive rather than the ones a reference-free boundary could produce.
     */
    private static SegmentAuthoringProof referenceProofOf(SegmentDsl document, ContentHash previewHash) {
        SegmentValidationResult validation = SegmentAuthoringFixtures.validator().validate(
                document, SegmentAuthoringFixtures.snapshot(), SegmentAuthoringFixtures.context(),
                SegmentAuthoringFixtures.validationPolicy(),
                SegmentReferenceFixtures.referenceContext(referenceCatalog()));
        SemanticQueryPlan plan = SegmentAuthoringFixtures.planner().plan(
                validation, SegmentAuthoringFixtures.snapshot(), SegmentAuthoringFixtures.context(),
                referenceExecutionContext());
        SegmentPreviewCard card = SegmentAuthoringFixtures.cardBuilder()
                .previewHash(previewHash)
                .dslHash(document.editableDslHash())
                .canonicalAstHash(validation.canonicalAstHash())
                .planHash(plan.planHash())
                .build();
        return new SegmentAuthoringProof(document, validation, plan, card, SegmentAuthoringFixtures.SKILL_HASH);
    }

    /** The canonical reference-dependency hash of one Semantic Query Plan. */
    public static ContentHash referenceDependenciesHash(SemanticQueryPlan plan) {
        return new SemanticQueryPlanCodec().referenceDependenciesHash(plan);
    }

    /** The dedicated development-only reference pins of one reference-bearing proof. */
    public static SegmentAuthoringReferencePins referencePins(SegmentAuthoringProof proof) {
        return SegmentAuthoringReferenceReadiness.developmentPins(
                referenceDependenciesHash(proof.plan()), REFERENCE_INPUTS_HASH);
    }

    /** The frozen host inputs of a reference-bearing compile: the host context and its dedicated pins. */
    public static SegmentAuthoringInputs referenceInputs(SegmentAuthoringProof proof) {
        return new SegmentAuthoringInputs(
                SegmentAuthoringFixtures.snapshot(), proof.previewCard(), proof.skillHash(),
                referencePins(proof), SegmentAuthoringReferenceContext.of(referenceExecutionContext()));
    }

    /** The frozen host inputs of a reference-bearing compile that pins its pins but no host reference context. */
    public static SegmentAuthoringInputs referenceInputsWithoutHostContext(SegmentAuthoringProof proof) {
        return new SegmentAuthoringInputs(
                SegmentAuthoringFixtures.snapshot(), proof.previewCard(), proof.skillHash(),
                referencePins(proof));
    }

    /** The compile context of one reference-bearing submission. */
    public static CapabilityCompileContext referenceCompileContext(SegmentAuthoringProof proof) {
        return new CapabilityCompileContext(
                List.of(SegmentAuthoringFixtures.experimental()), List.of(referenceInputs(proof)));
    }

    /** The CREATE submission of a reference-bearing source, pinned to that proof's reference evidence. */
    public static SegmentAuthoringRequest referenceCreateRequest(
            SegmentAuthoringSource source, SegmentAuthoringProof proof) {
        return new SegmentAuthoringRequest(
                source, ProposalIdentity.ofClientRef(SegmentAuthoringFixtures.CLIENT_REF), null,
                catalog(), BuilderFixtures.policy(), BuilderFixtures.instants(),
                SegmentAuthoringFixtures.context(), referenceCompileContext(proof));
    }

    /** The EDIT submission of a reference-bearing source, pinned to that proof's reference evidence. */
    public static SegmentAuthoringRequest referenceEditRequest(
            SegmentAuthoringSource source,
            String logicalId,
            TrustedCurrentCampaign current,
            SegmentAuthoringProof proof) {
        return new SegmentAuthoringRequest(
                source, ProposalIdentity.ofLogicalId(logicalId), current,
                catalog(), BuilderFixtures.policy(), BuilderFixtures.instants(),
                SegmentAuthoringFixtures.context(), referenceCompileContext(proof));
    }

    // ------------------------------------------------------------------ the three sources

    /** A resolved Segment Agent result paired with the exact typed proof it claims. */
    public static SegmentAuthoringSource ai(SegmentAuthoringProof proof) {
        return new SegmentAuthoringSource.AiResolved(agentResult(proof), proof);
    }

    /**
     * The same resolved AI source, but with a different Intent hash, state trace and Tool trace.
     *
     * <p>It exists so a test can prove conversation and model-trace facts never reach the semantic output.
     */
    public static SegmentAuthoringSource aiWithDifferentTrace(SegmentAuthoringProof proof) {
        return new SegmentAuthoringSource.AiResolved(agentResult(proof, OTHER_INTENT_HASH, true), proof);
    }

    public static SegmentAgentResult agentResult(SegmentAuthoringProof proof) {
        return agentResult(proof, INTENT_HASH, false);
    }

    private static SegmentAgentResult agentResult(
            SegmentAuthoringProof proof, ContentHash intentHash, boolean withTrace) {
        List<AgentTransition> stateTrace = withTrace
                ? List.of(new AgentTransition(
                        SegmentAgentState.PREVIEWING, SegmentAgentState.READY_FOR_PREVIEW,
                        "preview.safe", "preview.safe", 7))
                : List.of();
        List<SegmentToolCallRecord> toolTrace = withTrace
                ? List.of(new SegmentToolCallRecord(
                        "preview.safe", 6, Disposition.RESOLVED, null,
                        ContentHash.sha256("tool-input-1".getBytes(StandardCharsets.UTF_8))))
                : List.of();
        return SegmentAgentResult.publish(
                SKILL_ID, SKILL_VERSION, proof.skillHash(),
                Disposition.RESOLVED, SegmentAgentState.READY_FOR_PREVIEW, null, null,
                proof.previewCard().metadataHash(), intentHash,
                proof.document().editableDslHash(), proof.validation().canonicalAstHash(),
                proof.plan().planHash(), proof.previewCard().previewHash(),
                stateTrace, toolTrace, proof.previewCard(), GroundedExplanation.publish(List.of()));
    }

    /** The Visual Builder typed resolved DSL, carrying the same frozen proofs. */
    public static SegmentAuthoringSource visual(SegmentAuthoringProof proof) {
        return new SegmentAuthoringSource.VisualBuilder(proof);
    }

    /** A successful stable-node refinement result paired with the refined proof it claims. */
    public static SegmentAuthoringSource refinement(SegmentAuthoringProof base, SegmentAuthoringProof refined) {
        return new SegmentAuthoringSource.Refinement(refinementResult(base, refined), refined);
    }

    public static SegmentRefinementResult refinementResult(
            SegmentAuthoringProof base, SegmentAuthoringProof refined) {
        return SegmentRefinementResult.publish(
                SKILL_ID, SKILL_VERSION, refined.skillHash(), List.of("dsl.patch"),
                Disposition.RESOLVED, SegmentRefinementState.READY_FOR_PREVIEW, null,
                base.document().editableDslHash(), refined.document().editableDslHash(),
                refined.document(), refined.validation().canonicalAstHash(), refined.plan().planHash(),
                refined.previewCard().previewHash(), refined.previewCard().metadataHash(),
                refined.previewCard().asOf(), refined.previewCard().timezone(),
                refined.previewCard().dataWatermark(),
                PATCH_FINGERPRINT, List.of(), List.of(), refined.previewCard(),
                GroundedExplanation.publish(List.of()));
    }

    // ------------------------------------------------------------------ terminal sources

    /**
     * An Agent result that reached one non-resolved terminal disposition and carries no later artifact.
     *
     * <p>It is wrapped as a terminal source, which carries <em>no proof at all</em>: a non-resolved outcome has
     * no DSL, validation, plan or Preview artifact to prove, so no fixture may manufacture a resolved proof to
     * attach to it.
     */
    public static SegmentAuthoringSource terminalAi(Disposition disposition) {
        return terminalAi(disposition, terminalCode(disposition));
    }

    /** A terminal Agent source carrying one explicitly selected stable failure code. */
    public static SegmentAuthoringSource terminalAi(Disposition disposition, String failureCode) {
        return new SegmentAuthoringSource.AiTerminal(terminalAgentResult(disposition, failureCode));
    }

    /**
     * A terminal Agent source carrying one explicitly chosen genuine clarification.
     *
     * <p>It lets a structural test reach a {@code NEEDS_INPUT} outcome whose question code is not a
     * definition selection, so the host path that projects a non-selection question beside another component's
     * question can be exercised without a model.
     */
    public static SegmentAuthoringSource terminalAi(
            Disposition disposition, String failureCode, Clarification clarification) {
        return new SegmentAuthoringSource.AiTerminal(
                terminalAgentResult(disposition, failureCode, clarification));
    }

    /** The raw non-resolved Agent result one terminal AI source wraps, for structural tests. */
    public static SegmentAgentResult terminalAgentResult(Disposition disposition) {
        return terminalAgentResult(disposition, terminalCode(disposition));
    }

    /** A raw non-resolved Agent result carrying one explicitly selected stable failure code. */
    public static SegmentAgentResult terminalAgentResult(Disposition disposition, String failureCode) {
        return terminalAgentResult(disposition, failureCode,
                disposition == Disposition.NEEDS_INPUT ? clarification() : null);
    }

    /** A raw non-resolved Agent result carrying one explicitly chosen genuine clarification. */
    public static SegmentAgentResult terminalAgentResult(
            Disposition disposition, String failureCode, Clarification clarification) {
        return SegmentAgentResult.publish(
                SKILL_ID, SKILL_VERSION, SegmentAuthoringFixtures.SKILL_HASH,
                disposition, terminalState(disposition), failureCode, clarification,
                SegmentAuthoringFixtures.snapshot().contentHash(), INTENT_HASH,
                null, null, null, null, List.of(), List.of(), null, null);
    }

    /**
     * A refinement result that was denied or invalid and carries no refined artifact.
     *
     * <p>Like the terminal Agent result it carries no proof: there is no refined artifact to prove.
     */
    public static SegmentAuthoringSource terminalRefinement(Disposition disposition) {
        return new SegmentAuthoringSource.RefinementTerminal(terminalRefinementResult(disposition));
    }

    /** The raw non-resolved refinement result one terminal refinement source wraps, for structural tests. */
    public static SegmentRefinementResult terminalRefinementResult(Disposition disposition) {
        return SegmentRefinementResult.publish(
                SKILL_ID, SKILL_VERSION, SegmentAuthoringFixtures.SKILL_HASH, List.of("dsl.patch"),
                disposition, disposition == Disposition.DENIED
                        ? SegmentRefinementState.DENIED : SegmentRefinementState.INVALID,
                terminalCode(disposition), SegmentAuthoringFixtures.document().editableDslHash(),
                null, null, null, null, null, null, null, null, null,
                PATCH_FINGERPRINT, List.of(), List.of(), null, null);
    }

    private static SegmentAgentState terminalState(Disposition disposition) {
        return switch (disposition) {
            case RESOLVED -> SegmentAgentState.READY_FOR_PREVIEW;
            case NEEDS_INPUT -> SegmentAgentState.NEEDS_INPUT;
            case UNSUPPORTED -> SegmentAgentState.UNSUPPORTED;
            case DENIED -> SegmentAgentState.DENIED;
            case INVALID -> SegmentAgentState.INVALID;
        };
    }

    private static String terminalCode(Disposition disposition) {
        return "SEGMENT_" + disposition.name();
    }

    /** A genuine, stale-safe clarification built through the accepted T10 factories. */
    public static Clarification clarification() {
        ResolutionPins pins = new ResolutionPins(
                SegmentAuthoringFixtures.snapshot().snapshotId(),
                SegmentAuthoringFixtures.snapshot().snapshotVersion(),
                SegmentAuthoringFixtures.snapshot().contentHash(),
                "resolution-policy-1", ContentHash.sha256("resolution-policy-1".getBytes(StandardCharsets.UTF_8)),
                "registry-1", ContentHash.sha256("registry-1".getBytes(StandardCharsets.UTF_8)));
        String clauseId = "clause-1";
        String optionId = ClarificationOption.deriveOptionId(clauseId, "member.age");
        QuestionCode questionCode = QuestionCode.SELECT_DEFINITION;
        ResolutionId resolutionId = ResolutionId.derive(
                INTENT_HASH, pins, questionCode, clauseId, List.of(optionId));
        return new Clarification(
                resolutionId, INTENT_HASH, pins, questionCode, clauseId,
                "Which definition should apply?",
                List.of(new ClarificationOption(optionId, "member.age", "Age")));
    }

    /**
     * A genuine, stale-safe clarification of one explicitly chosen question code that offers no option.
     *
     * <p>It mirrors what the resolver produces for an ambiguity no bounded field choice can answer — such as
     * {@code MISSING_METADATA_DEFINITION} — so a test can project that question beside another component's
     * option-bearing question.
     */
    public static Clarification clarification(QuestionCode questionCode) {
        ResolutionPins pins = new ResolutionPins(
                SegmentAuthoringFixtures.snapshot().snapshotId(),
                SegmentAuthoringFixtures.snapshot().snapshotVersion(),
                SegmentAuthoringFixtures.snapshot().contentHash(),
                "resolution-policy-1", ContentHash.sha256("resolution-policy-1".getBytes(StandardCharsets.UTF_8)),
                "registry-1", ContentHash.sha256("registry-1".getBytes(StandardCharsets.UTF_8)));
        String clauseId = "clause-1";
        ResolutionId resolutionId = ResolutionId.derive(
                INTENT_HASH, pins, questionCode, clauseId, List.of());
        return new Clarification(
                resolutionId, INTENT_HASH, pins, questionCode, clauseId,
                switch (questionCode) {
                    case MISSING_METADATA_DEFINITION ->
                            "当前 Metadata 没有能直接表达该业务分类的字段，请说明它由哪个字段或计算规则判断。";
                    default -> "请补充这条条件的定义。";
                },
                List.of());
    }

    // ------------------------------------------------------------------ requests

    public static SegmentAuthoringRequest createRequest(SegmentAuthoringSource source) {
        return createRequest(source, SegmentAuthoringFixtures.experimental());
    }

    public static SegmentAuthoringRequest createRequest(
            SegmentAuthoringSource source, CapabilityReadiness readiness) {
        return new SegmentAuthoringRequest(
                source, ProposalIdentity.ofClientRef(SegmentAuthoringFixtures.CLIENT_REF), null,
                catalog(), BuilderFixtures.policy(),
                BuilderFixtures.instants(), SegmentAuthoringFixtures.context(),
                compileContextOf(source, readiness));
    }

    public static SegmentAuthoringRequest editRequest(
            SegmentAuthoringSource source, String logicalId, TrustedCurrentCampaign current) {
        return editRequest(source, logicalId, current, SegmentAuthoringFixtures.experimental());
    }

    public static SegmentAuthoringRequest editRequest(
            SegmentAuthoringSource source,
            String logicalId,
            TrustedCurrentCampaign current,
            CapabilityReadiness readiness) {
        return new SegmentAuthoringRequest(
                source, ProposalIdentity.ofLogicalId(logicalId), current,
                catalog(), BuilderFixtures.policy(),
                BuilderFixtures.instants(), SegmentAuthoringFixtures.context(),
                compileContextOf(source, readiness));
    }

    /**
     * The compile context of one submission.
     *
     * <p>Only a resolved source has a proof to pin, so only a resolved source contributes typed frozen evidence.
     * A terminal source carries none by construction and pins no inputs at all, because the integration refuses
     * it before any compile could consult them.
     */
    private static CapabilityCompileContext compileContextOf(
            SegmentAuthoringSource source, CapabilityReadiness readiness) {
        return source instanceof SegmentAuthoringSource.ResolvedSource resolved
                ? compileContext(resolved.proof(), readiness)
                : new CapabilityCompileContext(List.of(readiness), List.of());
    }

    /** The frozen capability catalog the resolved rule-based Segment is compiled against. */
    public static FrozenCapabilityCatalog catalog() {
        return BuilderFixtures.catalog(SegmentAuthoringFixtures.contract());
    }

    /** The host-owned typed inputs a compile must pin to prove the proof's own Preview evidence. */
    public static CapabilityCompileContext compileContext(
            SegmentAuthoringProof proof, CapabilityReadiness readiness) {
        return new CapabilityCompileContext(
                List.of(readiness),
                List.of(new SegmentAuthoringInputs(
                        SegmentAuthoringFixtures.snapshot(), proof.previewCard(), proof.skillHash())));
    }

    /** The deterministic compile instants every fixture request uses. */
    public static com.company.campaign.builder.compiler.CompileInstants instants() {
        return BuilderFixtures.instants();
    }
}
