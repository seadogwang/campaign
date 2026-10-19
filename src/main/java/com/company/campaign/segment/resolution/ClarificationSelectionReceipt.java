package com.company.campaign.segment.resolution;

/**
 * Replayable receipt for one option selected from one exact clarification.
 *
 * <p>The resolution id covers the Intent hash, Metadata/policy/default pins, question, target and complete
 * option set. Replaying this receipt must reproduce that exact clarification before its option can be applied.
 */
public record ClarificationSelectionReceipt(
        ResolutionId resolutionId,
        String selectionTarget,
        String optionId
) {

    public ClarificationSelectionReceipt {
        if (resolutionId == null || selectionTarget == null || optionId == null) {
            throw ResolutionException.invalid(
                    ResolutionErrorCodes.CHOICE_INVALID,
                    "A clarification selection receipt requires every field");
        }
    }
}
