package com.company.campaign.segment.resolution;

import com.company.campaign.metadata.MetadataKind;

import java.util.ArrayList;
import java.util.List;

/**
 * One permitted definition that was compared and rejected, with its stable reason and evidence.
 *
 * <p>The definition id is always one present in the host-authorized permitted catalog. A definition that is
 * absent or permission-filtered is never listed here, so the shape and size of this list cannot disclose that
 * a denied definition exists.
 */
public record RejectedCandidate(
        String definitionId,
        MetadataKind kind,
        RejectionReason reason,
        List<EvidenceCode> evidence
) {

    public RejectedCandidate {
        if (definitionId == null || kind == null || reason == null) {
            throw ResolutionException.invalid(
                    ResolutionErrorCodes.INTERNAL_INCONSISTENT, "Rejected candidate requires every field");
        }
        evidence = evidence == null ? List.of() : List.copyOf(new ArrayList<>(evidence));
    }
}