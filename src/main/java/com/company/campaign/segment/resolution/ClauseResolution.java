package com.company.campaign.segment.resolution;

import com.company.campaign.contracts.Disposition;
import com.company.campaign.metadata.MetadataKind;
import com.company.campaign.shared.canonical.ContentHash;

import java.util.ArrayList;
import java.util.List;

/**
 * The immutable resolution of one clause.
 *
 * <p>It names the single accepted definition or {@code null}, every compatible candidate in stable order,
 * every rejected permitted definition with a stable reason, the terminal disposition and any stable question
 * codes. Acceptance exists only when exactly one permitted definition is compatible with every populated
 * intent slot; several candidates, a recorded ambiguity or a semantic conflict yields {@code NEEDS_INPUT}.
 */
public record ClauseResolution(
        String clauseId,
        ContentHash intentHash,
        ResolutionPins pins,
        String accepted,
        MetadataKind acceptedKind,
        List<CandidateEvidence> candidates,
        List<RejectedCandidate> rejected,
        List<ScopeFilterResolution> scopeFilters,
        Disposition disposition,
        List<QuestionCode> questionCodes
) {

    public ClauseResolution {
        if (clauseId == null || intentHash == null || pins == null || disposition == null) {
            throw ResolutionException.invalid(
                    ResolutionErrorCodes.INTERNAL_INCONSISTENT,
                    "A clause resolution requires its clause id and exact pins");
        }
        if (accepted != null && acceptedKind == null) {
            throw ResolutionException.invalid(
                    ResolutionErrorCodes.INTERNAL_INCONSISTENT,
                    "An accepted definition requires its Metadata kind");
        }
        candidates = candidates == null ? List.of() : List.copyOf(new ArrayList<>(candidates));
        rejected = rejected == null ? List.of() : List.copyOf(new ArrayList<>(rejected));
        scopeFilters = scopeFilters == null ? List.of() : List.copyOf(new ArrayList<>(scopeFilters));
        questionCodes = questionCodes == null ? List.of() : List.copyOf(new ArrayList<>(questionCodes));
        if (accepted == null && disposition.isResolved()) {
            throw ResolutionException.invalid(
                    ResolutionErrorCodes.INTERNAL_INCONSISTENT,
                    "A resolved clause must name an accepted definition");
        }
        if (accepted != null && !disposition.isResolved()) {
            throw ResolutionException.invalid(
                    ResolutionErrorCodes.INTERNAL_INCONSISTENT,
                    "A non-resolved clause must not name an accepted definition");
        }
    }

    public ClauseResolution(
            String clauseId,
            ContentHash intentHash,
            ResolutionPins pins,
            String accepted,
            MetadataKind acceptedKind,
            List<CandidateEvidence> candidates,
            List<RejectedCandidate> rejected,
            Disposition disposition,
            List<QuestionCode> questionCodes) {
        this(clauseId, intentHash, pins, accepted, acceptedKind, candidates, rejected, List.of(), disposition,
                questionCodes);
    }
}
