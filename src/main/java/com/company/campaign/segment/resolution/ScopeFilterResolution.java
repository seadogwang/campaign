package com.company.campaign.segment.resolution;

import com.company.campaign.contracts.Disposition;

import java.util.ArrayList;
import java.util.List;

/** Deterministic resolution evidence for one Metric row-scope filter. */
public record ScopeFilterResolution(
        String filterId,
        String acceptedAttributeId,
        List<CandidateEvidence> candidates,
        List<RejectedCandidate> rejected,
        Disposition disposition,
        List<QuestionCode> questionCodes
) {

    public ScopeFilterResolution {
        if (filterId == null || disposition == null) {
            throw ResolutionException.invalid(
                    ResolutionErrorCodes.INTERNAL_INCONSISTENT,
                    "A scope-filter resolution requires its filter ID and disposition");
        }
        candidates = candidates == null ? List.of() : List.copyOf(new ArrayList<>(candidates));
        rejected = rejected == null ? List.of() : List.copyOf(new ArrayList<>(rejected));
        questionCodes = questionCodes == null ? List.of() : List.copyOf(new ArrayList<>(questionCodes));
        if (disposition.isResolved() != (acceptedAttributeId != null)) {
            throw ResolutionException.invalid(
                    ResolutionErrorCodes.INTERNAL_INCONSISTENT,
                    "Only a resolved scope filter may name an accepted Attribute");
        }
    }
}
