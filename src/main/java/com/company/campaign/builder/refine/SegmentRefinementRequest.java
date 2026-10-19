package com.company.campaign.builder.refine;

import com.company.campaign.segment.dsl.contract.SegmentDsl;
import com.company.campaign.segment.resolution.AudienceReferenceEvidence;
import com.company.campaign.segment.resolution.ResolutionResult;

import java.util.ArrayList;
import java.util.List;

/**
 * The bounded payload of one refinement request.
 *
 * <p>All four components are host-assembled rather than model-authored. The typed {@code currentDsl} is the
 * host-owned editable document being refined; the {@code patch} is the strict typed result of reading the
 * model's patch output through {@link SegmentRefinementPatchCodec} — an already-closed value, never raw JSON;
 * {@code priorResolution} is the host's accepted T10 resolution of the conversation the refinement belongs to,
 * if the host still holds one; and {@code referenceEvidence} is the host's accepted Existing Audience
 * Reference resolutions, which are the only thing that can justify an audience reference the patch introduces
 * or re-pins.
 *
 * <p>{@code referenceEvidence} is <em>host</em> evidence and deliberately not a patch field: a model may propose
 * an exact audience-version pin in its patch, but only the host resolving a candidate against its own
 * host-owned directory may decide one. The evidence is never read from the patch, so no model output can
 * manufacture the justification for its own pin.
 *
 * <p>There is deliberately no tenant, program, user, role, permission, DataScope, Metadata/Skill/version
 * override, model or provider setting, trusted time, backend choice, SQL, sample flag, Save/Publish flag or
 * precomputed validation/plan/preview artifact. Trusted context, run/conversation identity and every time pin
 * are host inputs outside this payload, and the prior resolution is optional host evidence that is never
 * invented when it is absent.
 */
public record SegmentRefinementRequest(
        SegmentDsl currentDsl,
        SegmentRefinementPatch patch,
        ResolutionResult priorResolution,
        List<AudienceReferenceEvidence> referenceEvidence
) {

    public SegmentRefinementRequest {
        if (currentDsl == null) {
            throw SegmentRefinementException.of(
                    SegmentRefinementAdapterErrorCodes.REQUEST_INVALID,
                    "A refinement request requires the host-owned current DSL document");
        }
        if (patch == null) {
            throw SegmentRefinementException.of(
                    SegmentRefinementAdapterErrorCodes.REQUEST_INVALID,
                    "A refinement request requires the strict typed refinement patch");
        }
        referenceEvidence = referenceEvidence == null
                ? List.of()
                : List.copyOf(new ArrayList<>(referenceEvidence));
    }

    /** A request with no prior resolution and no reference resolutions. */
    public SegmentRefinementRequest(SegmentDsl currentDsl, SegmentRefinementPatch patch) {
        this(currentDsl, patch, null, List.of());
    }

    /** A request with a prior resolution and no reference resolutions. */
    public SegmentRefinementRequest(
            SegmentDsl currentDsl, SegmentRefinementPatch patch, ResolutionResult priorResolution) {
        this(currentDsl, patch, priorResolution, List.of());
    }

    /**
     * True when this request carries at least one host resolution an introduced reference could rest on.
     *
     * <p>A reference-free refinement is the common case and stays exactly as it was: with no evidence the
     * editor's reference proof has nothing to check, so reference-free documents keep their accepted behavior.
     */
    public boolean hasReferenceEvidence() {
        return !referenceEvidence.isEmpty();
    }
}