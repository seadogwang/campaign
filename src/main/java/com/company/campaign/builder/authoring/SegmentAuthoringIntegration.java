package com.company.campaign.builder.authoring;

import com.company.campaign.builder.agent.SegmentAgentResult;
import com.company.campaign.builder.agent.SegmentPreviewCard;
import com.company.campaign.builder.compiler.CampaignCompiler;
import com.company.campaign.builder.compiler.CampaignPlan;
import com.company.campaign.builder.compiler.CapabilityPayload;
import com.company.campaign.builder.compiler.CompileRequest;
import com.company.campaign.builder.compiler.ResolvedComponent;
import com.company.campaign.builder.compiler.TrustedCurrentCampaign;
import com.company.campaign.builder.proposal.CampaignProposalPatch;
import com.company.campaign.builder.proposal.ProposalBaseRevisions;
import com.company.campaign.builder.proposal.ProposalClientContext;
import com.company.campaign.builder.proposal.ProposalIdentity;
import com.company.campaign.builder.proposal.ProposalMode;
import com.company.campaign.builder.proposal.ProposalOperation;
import com.company.campaign.builder.proposal.ProposalSchemaVersions;
import com.company.campaign.builder.refine.SegmentRefinementResult;
import com.company.campaign.shared.canonical.ContentHash;

import java.util.List;
import java.util.Objects;

/**
 * The one shared Segment authoring integration: a closed source union (resolved AI, resolved Visual Builder,
 * resolved refinement, and the terminal variants that carry no proof), one canonical Proposal, one accepted
 * compiler.
 *
 * <p>It is deliberately small. It does not compile anything, merge anything, produce a Manifest, a diff, a
 * ChangeSet or a Plan, and it adds no second capability semantics: it converts a {@link SegmentAuthoringSource}
 * into exactly one {@code segment.rule-based} {@code UPSERT} operation on a
 * {@link CampaignProposalPatch}, and then invokes the one existing {@link CampaignCompiler}. Everything the
 * compiler already does — revision binding, authorization, merge, composition type checking, the frozen
 * authoring policy, the semantic diff, the ChangeSet and the non-forgeable Plan — is reused unchanged.
 *
 * <p>Equal typed semantics and equal frozen pins therefore produce byte-identical Proposal, Manifest and Plan
 * material <em>regardless of source</em>: the payload is written from the proof alone, the identity is the one
 * the submission named, the presentation context is the single empty one, and no source label, conversation
 * identifier, model trace, provider output or UI detail is ever read on the semantic path.
 *
 * <p>A source that reached {@code NEEDS_INPUT}, {@code UNSUPPORTED}, {@code DENIED} or {@code INVALID} is
 * returned as a bounded {@link SegmentAuthoringResult.Terminal} with no payload, Manifest or Plan at all: the
 * terminal half of the source union carries no proof, and it is short-circuited before any payload, Proposal or
 * compile exists. A resolved source is first re-proved against the typed artifact it claims and then bound to
 * the Segment component it may replace; a result that disagrees with its proof, an EDIT of something that is not
 * this rule-based Segment, and a refinement of a stale base all fail closed instead of producing an artifact.
 */
public final class SegmentAuthoringIntegration {

    private final CampaignCompiler compiler;

    /**
     * Creates the one shared integration over the accepted T15 compiler.
     *
     * <p>The compiler is the only way this integration produces a Plan. There is no second compiler, no
     * alternate merge and no local Manifest or Plan factory anywhere in it.
     */
    public SegmentAuthoringIntegration(CampaignCompiler compiler) {
        this.compiler = Objects.requireNonNull(compiler, "compiler");
    }

    /**
     * Submits one proof-bound source and returns either the compiled Plan or a bounded terminal outcome.
     *
     * @throws SegmentAuthoringException when the submission itself is malformed, or a resolved source is not
     *     provably the artifact it claims; the failure carries one stable code and echoes no value
     */
    public SegmentAuthoringResult author(SegmentAuthoringRequest request) {
        if (request == null) {
            throw new SegmentAuthoringException(
                    SegmentAuthoringErrorCodes.REQUEST_INVALID, "request", "An authoring request is required.");
        }
        // The two halves of the closed union are handled before anything else exists: a terminal source is
        // converted straight into its own bounded outcome, so no proof, payload, Proposal or compile is ever
        // reached for it, and a resolved source is the only shape that can carry a proof to re-prove.
        return switch (request.source()) {
            case SegmentAuthoringSource.TerminalSource terminal -> terminal(terminal);
            case SegmentAuthoringSource.ResolvedSource resolved -> compile(request, resolved);
        };
    }

    // ------------------------------------------------------------------ terminal outcomes

    /**
     * The bounded terminal outcome of a terminal source.
     *
     * <p>A source result's own disposition, failure code and clarification are preserved verbatim; nothing is
     * invented, no proof is consulted (a terminal source carries none) and no payload is partially built.
     */
    private static SegmentAuthoringResult terminal(SegmentAuthoringSource.TerminalSource source) {
        return new SegmentAuthoringResult.Terminal(
                source.disposition(), source.failureCode(), source.clarification(), source.kind());
    }

    // ------------------------------------------------------------------ the one resolved path

    /**
     * Re-proves one resolved source against its own typed proof and compiles the one canonical Proposal.
     *
     * <p>Order matters: the source must first prove it really carries the artifact its proof holds, then the
     * target must be compatible with the source (an EDIT may only replace an existing {@code segment.rule-based}
     * component at this exact contract version, and a refinement may only build on the current component's own
     * resolved DSL). Only then is a payload written and the one accepted compiler invoked.
     */
    private SegmentAuthoringResult compile(
            SegmentAuthoringRequest request, SegmentAuthoringSource.ResolvedSource source) {
        SegmentAuthoringProof proof = source.proof();
        requireSourceMatchesProof(source, proof);
        requireTargetCompatible(request, source);

        CampaignProposalPatch patch = proposal(request, payload(request, proof));
        CampaignPlan plan = compiler.compile(new CompileRequest(
                patch,
                request.current(),
                request.catalog(),
                request.policy(),
                request.instants(),
                request.context(),
                request.capabilityContext()));
        return new SegmentAuthoringResult.Compiled(patch, plan, referencePins(request));
    }

    /**
     * The dedicated reference evidence of this compile, read from the same frozen host inputs the capability
     * contract reads, or null when the compile pinned none.
     *
     * <p>It is read from the host-owned capability inputs and never from a proposal or a capability payload, so
     * a client or a model cannot declare reference readiness. The capability contract independently requires
     * these pins to agree with the plan's own reference dependencies, so the two cannot drift apart.
     */
    private static SegmentAuthoringReferencePins referencePins(SegmentAuthoringRequest request) {
        java.util.Optional<com.company.campaign.builder.compiler.CapabilityInputs> found =
                request.capabilityContext().inputsFor(SegmentAuthoringVersions.CAPABILITY_CODE);
        if (found.isPresent() && found.get() instanceof SegmentAuthoringInputs inputs) {
            return inputs.referencePins();
        }
        return null;
    }

    // ------------------------------------------------------------------ proof binding

    /**
     * Re-proves that a resolved source result really carries the artifact the typed proof holds.
     *
     * <p>A hash-only Agent or refinement result is never sufficient by itself: it must show the exact DSL,
     * canonical AST, semantic plan, Preview and Skill pins of the proof, and its own safe Preview card must be
     * the proof's card. A Visual Builder document needs no separate reconciliation, because the typed artifact
     * is the resolution. Any disagreement fails closed before a payload exists.
     */
    private static void requireSourceMatchesProof(
            SegmentAuthoringSource.ResolvedSource source, SegmentAuthoringProof proof) {
        switch (source) {
            case SegmentAuthoringSource.VisualBuilder ignored -> {
                // The typed proof is the resolution; there is no second claim to reconcile.
            }
            case SegmentAuthoringSource.AiResolved ai -> requireAgentMatchesProof(ai.result(), proof);
            case SegmentAuthoringSource.Refinement refinement ->
                    requireRefinementMatchesProof(refinement.result(), proof);
        }
    }

    // ------------------------------------------------------------------ target binding

    /**
     * Binds a resolved source to the Segment component it is allowed to replace.
     *
     * <p>A CREATE introduces a brand-new Segment through a {@code clientRef}, so it has no existing component to
     * re-type; a refinement, however, preserves an existing Segment's stable identity and therefore requires an
     * EDIT. An EDIT must address an existing component that really is a {@code segment.rule-based} capability at
     * this exact contract version: a different capability is never silently re-typed in place, because that would
     * replace one capability's semantics with another's under an identity that never meant it.
     *
     * <p>A refinement must additionally prove that the base document it was derived from is the current
     * component's own resolved DSL, read from the authoritative component payload through the accepted strict
     * codec. A refinement of a stale or different base would otherwise install a refined artifact whose stated
     * provenance is false.
     */
    private static void requireTargetCompatible(
            SegmentAuthoringRequest request, SegmentAuthoringSource.ResolvedSource source) {
        if (request.isCreate()) {
            if (source instanceof SegmentAuthoringSource.Refinement) {
                throw new SegmentAuthoringException(
                        SegmentAuthoringErrorCodes.EDIT_TARGET_INVALID,
                        "refinementTarget",
                        "A refinement preserves an existing Segment's identity and requires an EDIT target.");
            }
            return;
        }
        ResolvedComponent target = currentComponent(request.current(), request.target().logicalId());
        if (target == null
                || !SegmentAuthoringVersions.CAPABILITY_CODE.equals(target.capabilityCode())
                || !SegmentAuthoringVersions.CONTRACT_VERSION.equals(target.contractVersion())) {
            throw new SegmentAuthoringException(
                    SegmentAuthoringErrorCodes.EDIT_TARGET_INVALID,
                    "editTarget",
                    "An EDIT must address an existing rule-based Segment at the supported contract version.");
        }
        if (source instanceof SegmentAuthoringSource.Refinement refinement) {
            ContentHash current = SegmentAuthoringEnvelope.dslHashOf(target.value());
            if (!Objects.equals(current, refinement.result().baseDslHash())) {
                throw new SegmentAuthoringException(
                        SegmentAuthoringErrorCodes.REFINEMENT_BASE_STALE,
                        "refinementBase",
                        "A refinement must be based on the current Segment component's own resolved DSL.");
            }
        }
    }

    /** The component a trusted Current campaign holds under one stable identity, or null when it holds none. */
    private static ResolvedComponent currentComponent(TrustedCurrentCampaign current, String logicalId) {
        for (ResolvedComponent component : current.components()) {
            if (component.logicalId().equals(logicalId)) {
                return component;
            }
        }
        return null;
    }

    private static void requireAgentMatchesProof(SegmentAgentResult result, SegmentAuthoringProof proof) {
        if (!Objects.equals(result.dslEditableHash(), proof.dslHash())
                || !Objects.equals(result.canonicalAstHash(), proof.canonicalAstHash())
                || !Objects.equals(result.planHash(), proof.planHash())
                || !Objects.equals(result.previewHash(), proof.previewHash())
                || !Objects.equals(result.skillHash(), proof.skillHash())
                || !Objects.equals(result.metadataSnapshotHash(), proof.previewCard().metadataHash())) {
            throw sourceMismatch("agentProof", "The resolved Agent result is not the artifact its proof holds.");
        }
        requireSameCard(result.previewCard(), proof.previewCard());
    }

    private static void requireRefinementMatchesProof(
            SegmentRefinementResult result, SegmentAuthoringProof proof) {
        if (!Objects.equals(result.refinedDslHash(), proof.dslHash())
                || !Objects.equals(result.canonicalAstHash(), proof.canonicalAstHash())
                || !Objects.equals(result.planHash(), proof.planHash())
                || !Objects.equals(result.previewHash(), proof.previewHash())
                || !Objects.equals(result.skillHash(), proof.skillHash())
                || !Objects.equals(result.metadataSnapshotHash(), proof.previewCard().metadataHash())
                || !Objects.equals(result.asOf(), proof.previewCard().asOf())
                || !Objects.equals(result.timezone(), proof.previewCard().timezone())
                || !Objects.equals(result.dataWatermark(), proof.previewCard().dataWatermark())) {
            throw sourceMismatch(
                    "refinementProof", "The refinement result is not the artifact its proof holds.");
        }
        requireSameCard(result.previewCard(), proof.previewCard());
    }

    /** The two cards must be one card: same safe facts and same pins, compared field by field. */
    private static void requireSameCard(SegmentPreviewCard claimed, SegmentPreviewCard proofCard) {
        if (claimed == null
                || !Objects.equals(claimed.status(), proofCard.status())
                || claimed.matchedCount() != proofCard.matchedCount()
                || !Objects.equals(claimed.metadataSnapshotId(), proofCard.metadataSnapshotId())
                || !Objects.equals(claimed.asOf(), proofCard.asOf())
                || !Objects.equals(claimed.timezone(), proofCard.timezone())
                || !Objects.equals(claimed.dataWatermark(), proofCard.dataWatermark())
                || !Objects.equals(claimed.conditionStatistics(), proofCard.conditionStatistics())
                || !Objects.equals(claimed.sampleTokens(), proofCard.sampleTokens())
                || !Objects.equals(claimed.previewHash(), proofCard.previewHash())
                || !Objects.equals(claimed.planHash(), proofCard.planHash())
                || !Objects.equals(claimed.dslHash(), proofCard.dslHash())
                || !Objects.equals(claimed.canonicalAstHash(), proofCard.canonicalAstHash())
                || !Objects.equals(claimed.metadataHash(), proofCard.metadataHash())
                || !Objects.equals(claimed.skillHash(), proofCard.skillHash())) {
            throw sourceMismatch(
                    "sourcePreviewCard", "The source result's Preview card is not the proof's own card.");
        }
    }

    private static SegmentAuthoringException sourceMismatch(String invariant, String message) {
        return new SegmentAuthoringException(SegmentAuthoringErrorCodes.SOURCE_MISMATCH, invariant, message);
    }

    // ------------------------------------------------------------------ the one canonical payload

    /**
     * The one canonical capability payload every source converges on.
     *
     * <p>The semantics are written from the proof alone through the envelope's one writer, so the accepted
     * DSL, canonical, plan, Preview and Skill pins are the only semantic inputs; there is no room for a
     * source-specific field to enter, and the same writer produces the normalized component payload the
     * capability contract emits. The trusted time pins are deliberately <em>not</em> written here: they are
     * trusted compile/context facts, so the contract reads them only from the accepted
     * {@code TrustedExecutionContext} and re-proves the host-pinned Preview card against them.
     *
     * <p>A trusted context whose own time pins are absent or malformed fails closed before a payload exists:
     * nothing this integration can do would make such a compile honest, and the three names are reserved on
     * the Proposal surface precisely so they can never be supplied from here.
     */
    private static CapabilityPayload payload(SegmentAuthoringRequest request, SegmentAuthoringProof proof) {
        var reference = request.context().snapshotRef();
        if (reference == null
                || reference.asOf() == null
                || reference.timezone() == null
                || reference.dataWatermark() == null) {
            throw new SegmentAuthoringException(
                    SegmentAuthoringErrorCodes.TIME_PIN_MISMATCH,
                    "trustedTimePins",
                    "A Segment submission requires the trusted context's own time pins.");
        }
        return SegmentAuthoringEnvelope.payloadOf(
                proof.document(), proof.canonicalAstHash(), proof.planHash(), proof.previewHash(),
                proof.skillHash());
    }

    // ------------------------------------------------------------------ the one proposal

    /**
     * Builds the single-operation Proposal a submission is submitted as.
     *
     * <p>The identity form decides the mode, so a CREATE is always an upsert of a new {@code clientRef} and an
     * EDIT is always an upsert of the existing stable {@code logicalId}: a replacement after a refinement
     * keeps the Segment's logical identity and can never become a delete plus a re-create. The base revisions
     * and campaign identifier come from the trusted Current of an EDIT and from the frozen catalog/policy of a
     * CREATE. The presentation context is the single empty one, so no UI detail can reach the canonical bytes.
     */
    private static CampaignProposalPatch proposal(SegmentAuthoringRequest request, CapabilityPayload payload) {
        ProposalIdentity target = request.target();
        TrustedCurrentCampaign current = request.current();
        boolean create = request.isCreate();
        ProposalBaseRevisions baseRevisions = new ProposalBaseRevisions(
                create ? null : current.campaignRevision(),
                request.catalog().catalogVersion(),
                request.catalog().catalogHash(),
                request.policy().policyVersion(),
                request.policy().policyHash());
        ProposalOperation operation = create
                ? ProposalOperation.upsertNew(
                        SegmentAuthoringVersions.CAPABILITY_CODE, target.clientRef(), payload)
                : ProposalOperation.upsertExisting(
                        SegmentAuthoringVersions.CAPABILITY_CODE, target.logicalId(), payload);
        return new CampaignProposalPatch(
                ProposalSchemaVersions.LEGACY_V1_3,
                create ? ProposalMode.CREATE : ProposalMode.EDIT,
                create ? null : current.campaignId(),
                baseRevisions,
                List.of(operation),
                ProposalClientContext.EMPTY);
    }

    /** A bounded diagnostic: this integration holds no value that could leak, so it prints only its identity. */
    @Override
    public String toString() {
        return "SegmentAuthoringIntegration[compiler=" + compiler.getClass().getSimpleName() + ']';
    }
}
