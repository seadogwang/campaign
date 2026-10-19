package com.company.campaign.evaluation.segment.contract;

import com.company.campaign.contracts.ContractPatterns;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.regex.Pattern;

/**
 * The Segment a refinement case starts from, expressed as a reference plus an opaque content placeholder.
 *
 * <p>The content is deliberately a canonical JSON tree rather than a typed segment model. T05 owns the
 * typed DSL; if T03 introduced a parallel shape here, refinement cases would be written against a
 * throwaway type and would have to be rewritten — or worse, migrated silently — when the real DSL lands.
 * A case can therefore only say "refine from this document", not "this document means X".
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record CurrentSegmentRef(
        String segmentId,
        JsonNode content
) {
    private static final Pattern REFERENCE_ID = Pattern.compile(ContractPatterns.REFERENCE_ID);

    public CurrentSegmentRef {
        if (segmentId == null || !REFERENCE_ID.matcher(segmentId).matches()) {
            throw new EvaluationException(
                    EvaluationErrorCodes.ID_INVALID,
                    "Current segment id '" + segmentId + "' is not a valid reference identifier");
        }
        if (content == null) {
            throw new EvaluationException(
                    EvaluationErrorCodes.ASSET_INVALID,
                    "Current segment '" + segmentId + "' must carry its content placeholder");
        }
    }
}