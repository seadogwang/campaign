package com.company.campaign.segment.resolution;

import com.company.campaign.contracts.Disposition;
import com.company.campaign.shared.canonical.ContentHash;

import java.util.ArrayList;
import java.util.List;

/**
 * The immutable outcome of resolving one Intent against one permitted snapshot.
 *
 * <p>It carries the terminal disposition, the base Intent hash, the exact pins, the per-clause resolutions
 * in declaration order, the optional clarification and any ordered replayable host-selection receipts. It is
 * intent-level output only: it never contains a Segment DSL, a plan, a query or any mutation, and it never
 * discloses a denied definition.
 */
public record ResolutionResult(
        Disposition disposition,
        ContentHash baseIntentHash,
        ResolutionPins pins,
        List<ClauseResolution> clauses,
        Clarification clarification,
        List<ClarificationSelectionReceipt> selectionReceipts,
        List<AudienceReferenceResolution> references
) {

    public ResolutionResult {
        if (disposition == null || baseIntentHash == null || pins == null) {
            throw ResolutionException.invalid(
                    ResolutionErrorCodes.INTERNAL_INCONSISTENT, "A resolution result requires every pin");
        }
        clauses = clauses == null ? List.of() : List.copyOf(new ArrayList<>(clauses));
        selectionReceipts = selectionReceipts == null
                ? List.of()
                : List.copyOf(new ArrayList<>(selectionReceipts));
        references = references == null ? List.of() : List.copyOf(new ArrayList<>(references));
        if (disposition.isResolved() && clarification != null) {
            throw ResolutionException.invalid(
                    ResolutionErrorCodes.INTERNAL_INCONSISTENT,
                    "A resolved result must not carry a clarification");
        }
        if (disposition == Disposition.NEEDS_INPUT && clarification == null) {
            throw ResolutionException.invalid(
                    ResolutionErrorCodes.INTERNAL_INCONSISTENT,
                    "A NEEDS_INPUT result must carry a clarification");
        }
    }

    public ResolutionResult(
            Disposition disposition,
            ContentHash baseIntentHash,
            ResolutionPins pins,
            List<ClauseResolution> clauses,
            Clarification clarification,
            List<ClarificationSelectionReceipt> selectionReceipts) {
        this(disposition, baseIntentHash, pins, clauses, clarification, selectionReceipts, List.of());
    }

    public ResolutionResult(
            Disposition disposition,
            ContentHash baseIntentHash,
            ResolutionPins pins,
            List<ClauseResolution> clauses,
            Clarification clarification) {
        this(disposition, baseIntentHash, pins, clauses, clarification, List.of(), List.of());
    }

    /** True only for a fully resolved result. */
    public boolean isResolved() {
        return disposition.isResolved();
    }

    /** True when this resolution answered at least one Existing Audience Reference. */
    public boolean hasAudienceReferences() {
        return !references.isEmpty();
    }

    /** The resolution of one clause, or null when no such clause exists. */
    public ClauseResolution clause(String clauseId) {
        for (ClauseResolution clause : clauses) {
            if (clause.clauseId().equals(clauseId)) {
                return clause;
            }
        }
        return null;
    }

    /** The resolution of one audience reference, or null when this Intent carries no such reference. */
    public AudienceReferenceResolution audienceReference(String referenceId) {
        for (AudienceReferenceResolution reference : references) {
            if (reference.referenceId().equals(referenceId)) {
                return reference;
            }
        }
        return null;
    }
}
