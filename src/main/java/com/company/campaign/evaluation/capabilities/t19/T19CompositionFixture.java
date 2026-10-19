package com.company.campaign.evaluation.capabilities.t19;

import com.company.campaign.contracts.ContractPatterns;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * One composition fixture: a bounded identity, its expected outcome and the exact ordered component set.
 *
 * <p>The component order is semantic — it is the relative manifest slot order a composed campaign must
 * preserve — so it is frozen exactly as declared and never re-sorted.
 */
public record T19CompositionFixture(
        String compositionId,
        String expectation,
        String campaignId,
        List<T19CompositionComponent> components) {

    /** The polarity naming a composition the validator must accept. */
    public static final String ACCEPTED = "ACCEPTED";

    /** The polarity naming a composition the validator must reject. */
    public static final String REJECTED = "REJECTED";

    private static final Set<String> POLARITIES = Set.of(ACCEPTED, REJECTED);
    private static final Pattern ID = Pattern.compile(ContractPatterns.REFERENCE_ID);

    public T19CompositionFixture {
        if (compositionId == null || !ID.matcher(compositionId).matches()) {
            throw new T19EvaluationException(
                    T19EvaluationErrorCodes.ASSET_NON_CANONICAL,
                    "A composition fixture requires a bounded composition id.");
        }
        if (expectation == null || !POLARITIES.contains(expectation)) {
            throw new T19EvaluationException(
                    T19EvaluationErrorCodes.FIXTURE_POLARITY,
                    "A composition fixture requires an accepted or rejected polarity.");
        }
        if (campaignId != null && !ID.matcher(campaignId).matches()) {
            throw new T19EvaluationException(
                    T19EvaluationErrorCodes.ASSET_NON_CANONICAL,
                    "A composition fixture requires a bounded campaign id or none.");
        }
        List<T19CompositionComponent> copy = components == null ? List.of() : new ArrayList<>(components);
        for (T19CompositionComponent component : copy) {
            if (component == null) {
                throw new T19EvaluationException(
                        T19EvaluationErrorCodes.ASSET_NON_CANONICAL,
                        "A composition fixture must not carry a null component.");
            }
        }
        components = List.copyOf(copy);
    }

    /** True when the validator must accept this composition. */
    public boolean accepted() {
        return ACCEPTED.equals(expectation);
    }
}