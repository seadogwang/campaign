package com.company.campaign.builder.authoring;

import com.company.campaign.builder.agent.SegmentPreviewCard;
import com.company.campaign.query.plan.SemanticQueryPlan;
import com.company.campaign.segment.dsl.contract.SegmentDsl;
import com.company.campaign.segment.validation.SegmentValidationResult;
import com.company.campaign.shared.canonical.ContentHash;

import java.util.Objects;

/**
 * The exact typed, server-owned proof a resolved rule-based Segment is submitted as.
 *
 * <p>Every field is a genuine, non-forgeable artifact of an accepted deterministic boundary: the strict T05
 * editable DSL document, the T07 validation result (which owns the canonical AST and its hash), the T08
 * semantic query plan and the already-approved safe T09/T11 Preview card, plus the frozen Skill content hash
 * the resolution ran under. None of them can be manufactured outside its own package, so a proof can only be
 * assembled from artifacts that really were produced.
 *
 * <p>Construction re-proves that the four artifacts describe <em>one</em> Segment: the document's accepted
 * editable hash must be the one the validation result carries, the validation and the plan must agree on the
 * canonical AST hash, and the Preview card must bind exactly the document, canonical, plan and Skill pins. A
 * proof that mixes artifacts from two documents, or that carries a card from another resolution, is not
 * representable — the disagreement fails closed with one bounded code rather than entering a payload.
 *
 * <p>The proof deliberately carries no trusted time, no Snapshot, no readiness or Gate fact and no trusted
 * identity: those are host inputs of the compile, never of the artifact.
 */
public record SegmentAuthoringProof(
        SegmentDsl document,
        SegmentValidationResult validation,
        SemanticQueryPlan plan,
        SegmentPreviewCard previewCard,
        ContentHash skillHash) {

    public SegmentAuthoringProof {
        Objects.requireNonNull(document, "document");
        Objects.requireNonNull(validation, "validation");
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(previewCard, "previewCard");
        Objects.requireNonNull(skillHash, "skillHash");
        if (!validation.valid()
                || !Objects.equals(validation.editableDslHash(), document.editableDslHash())
                || !Objects.equals(plan.canonicalAstHash(), validation.canonicalAstHash())
                || !Objects.equals(plan.metadataBinding(), validation.metadataBinding())) {
            throw new SegmentAuthoringException(
                    SegmentAuthoringErrorCodes.EVIDENCE_MISMATCH,
                    "proofValidation",
                    "The typed Segment proof does not carry one coherent accepted artifact.");
        }
        if (!Objects.equals(previewCard.dslHash(), document.editableDslHash())
                || !Objects.equals(previewCard.canonicalAstHash(), validation.canonicalAstHash())
                || !Objects.equals(previewCard.planHash(), plan.planHash())
                || !Objects.equals(previewCard.skillHash(), skillHash)) {
            throw new SegmentAuthoringException(
                    SegmentAuthoringErrorCodes.EVIDENCE_MISMATCH,
                    "proofPreviewCard",
                    "The typed Segment proof's Preview evidence is not the one its artifact resolves.");
        }
    }

    /** The accepted editable DSL hash every artifact of this proof agrees on. */
    public ContentHash dslHash() {
        return document.editableDslHash();
    }

    /** The accepted canonical AST hash every artifact of this proof agrees on. */
    public ContentHash canonicalAstHash() {
        return validation.canonicalAstHash();
    }

    /** The accepted semantic query plan hash every artifact of this proof agrees on. */
    public ContentHash planHash() {
        return plan.planHash();
    }

    /** The accepted Preview hash the bound safe Preview card carries. */
    public ContentHash previewHash() {
        return previewCard.previewHash();
    }

    /** A bounded diagnostic: only already-safe hashes and a boolean, never a trusted value. */
    @Override
    public String toString() {
        return "SegmentAuthoringProof[dslHash=" + dslHash()
                + ", planHash=" + planHash()
                + ", sample=" + previewCard.hasSample() + ']';
    }
}