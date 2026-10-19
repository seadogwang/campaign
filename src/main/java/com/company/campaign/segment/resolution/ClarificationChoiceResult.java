package com.company.campaign.segment.resolution;

/**
 * The new deterministic resolution produced by applying a clarification choice.
 *
 * <p>It is a resolution, not a DSL and not a mutation: the caller receives the re-resolved result and the
 * exact clause and option that were applied, so a host can continue the authoring loop without trusting any
 * model output.
 */
public record ClarificationChoiceResult(
        ResolutionResult resolution,
        String appliedClauseId,
        String appliedOptionId
) {

    public ClarificationChoiceResult {
        if (resolution == null || appliedClauseId == null || appliedOptionId == null) {
            throw ResolutionException.invalid(
                    ResolutionErrorCodes.INTERNAL_INCONSISTENT, "A choice result requires every field");
        }
    }
}