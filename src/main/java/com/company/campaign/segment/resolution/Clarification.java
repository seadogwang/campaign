package com.company.campaign.segment.resolution;

import com.company.campaign.shared.canonical.ContentHash;

import java.util.ArrayList;
import java.util.List;

/**
 * The stale-safe clarification a {@code NEEDS_INPUT} resolution produces.
 *
 * <p>It pins the deterministic {@code resolutionId}, the base Intent hash, the Metadata snapshot, the policy
 * and the registered-default registry revision, together with the stable question code and selection target.
 * The target is a clause id for a clause definition, or a deterministic clause/scope/filter path for a nested
 * Metric filter definition. The
 * question text is presentation data generated from the code; logic keys only on the code and the exact
 * hashes, so a wording change can never apply an old choice.
 */
public record Clarification(
        ResolutionId resolutionId,
        ContentHash baseIntentHash,
        ResolutionPins pins,
        QuestionCode questionCode,
        String clauseId,
        String questionText,
        List<ClarificationOption> options
) {

    public Clarification {
        if (resolutionId == null || baseIntentHash == null || pins == null
                || questionCode == null || clauseId == null || questionText == null) {
            throw ResolutionException.invalid(
                    ResolutionErrorCodes.INTERNAL_INCONSISTENT, "A clarification requires every pin");
        }
        if (questionText.length() > ResolutionLimits.MAX_QUESTION_TEXT_LENGTH) {
            throw ResolutionException.invalid(
                    ResolutionErrorCodes.INTERNAL_INCONSISTENT, "The question text exceeds the length limit");
        }
        if (options == null || options.size() > ResolutionLimits.MAX_CLARIFICATION_OPTIONS) {
            throw ResolutionException.invalid(
                    ResolutionErrorCodes.INTERNAL_INCONSISTENT, "A clarification option list is out of bounds");
        }
        options = List.copyOf(new ArrayList<>(options));
    }

    /** The opaque option ids in stable order; the exact material the resolution id covers. */
    public List<String> optionIds() {
        List<String> ids = new ArrayList<>(options.size());
        for (ClarificationOption option : options) {
            ids.add(option.optionId());
        }
        return List.copyOf(ids);
    }
}
