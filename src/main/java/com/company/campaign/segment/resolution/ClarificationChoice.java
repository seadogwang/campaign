package com.company.campaign.segment.resolution;

import com.company.campaign.shared.canonical.ContentHash;

/**
 * A follow-up selection of one clarification option.
 *
 * <p>It carries the exact receipts the host must still match: the resolution id, the base Intent hash, the
 * Metadata/policy/default-registry pins, the stable question code and the selection target. The option set is
 * covered by the resolution id, so a changed option set changes the id and the choice is refused as stale.
 */
public record ClarificationChoice(
        ResolutionId resolutionId,
        String optionId,
        ContentHash baseIntentHash,
        ResolutionPins pins,
        QuestionCode questionCode,
        String clauseId
) {

    public ClarificationChoice {
        if (resolutionId == null || optionId == null || baseIntentHash == null
                || pins == null || questionCode == null || clauseId == null) {
            throw ResolutionException.invalid(
                    ResolutionErrorCodes.CHOICE_INVALID, "A clarification choice requires every receipt");
        }
    }
}
