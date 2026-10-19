package com.company.campaign.segment.resolution;

import com.company.campaign.metadata.MetadataKind;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * One compatible permitted definition, its evidence codes and its explanatory score.
 *
 * <p>The score is explanatory only: it is a fixed decimal derived from the {@link CandidateTier} weight and
 * never a probability or a decision input. Compatibility is already established when this record exists, so
 * the score cannot override an incompatibility.
 */
public record CandidateEvidence(
        String definitionId,
        MetadataKind kind,
        CandidateTier tier,
        List<EvidenceCode> evidence,
        BigDecimal score
) {

    public CandidateEvidence {
        if (definitionId == null || kind == null || tier == null || score == null) {
            throw ResolutionException.invalid(
                    ResolutionErrorCodes.INTERNAL_INCONSISTENT, "Candidate evidence requires every field");
        }
        evidence = evidence == null ? List.of() : List.copyOf(new ArrayList<>(evidence));
    }
}