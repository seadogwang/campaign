package com.company.campaign.evaluation.capabilities.t19;

import com.company.campaign.contracts.ContractPatterns;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The bounded document of complete-composition fixtures: the one corpus asset that drives the real T19
 * composition validator end to end.
 *
 * <p>It carries only components, slots, bounded payload maps and resolved references — never a template
 * body, a reward description, a raw variable, a credential, a member identifier or a provider response.
 */
public record T19CompositionDocument(
        String schemaVersion,
        String assetId,
        String assetVersion,
        List<T19CompositionFixture> compositions) {

    /** Largest permitted number of composition fixtures in one document. */
    public static final int MAX_COMPOSITIONS = 64;

    private static final Pattern VERSION = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._/+-]{0,63}$");
    private static final Pattern ID = Pattern.compile(ContractPatterns.REFERENCE_ID);

    public T19CompositionDocument {
        if (schemaVersion == null || !VERSION.matcher(schemaVersion).matches()) {
            throw new T19EvaluationException(
                    T19EvaluationErrorCodes.ASSET_NON_CANONICAL,
                    "A composition document requires a bounded schema version.");
        }
        if (assetId == null || !ID.matcher(assetId).matches()
                || assetVersion == null || !VERSION.matcher(assetVersion).matches()) {
            throw new T19EvaluationException(
                    T19EvaluationErrorCodes.ASSET_NON_CANONICAL,
                    "A composition document requires a bounded asset identity and version.");
        }
        if (compositions == null || compositions.isEmpty() || compositions.size() > MAX_COMPOSITIONS) {
            throw new T19EvaluationException(
                    T19EvaluationErrorCodes.FIXTURE_POLARITY,
                    "A composition document requires a bounded, non-empty composition list.");
        }
        List<T19CompositionFixture> copy = new ArrayList<>(compositions.size());
        Set<String> ids = new HashSet<>(compositions.size());
        boolean accepted = false;
        boolean rejected = false;
        for (T19CompositionFixture composition : compositions) {
            if (composition == null) {
                throw new T19EvaluationException(
                        T19EvaluationErrorCodes.ASSET_NON_CANONICAL,
                        "A composition document must not carry a null composition.");
            }
            if (!ids.add(composition.compositionId())) {
                throw new T19EvaluationException(
                        T19EvaluationErrorCodes.FIXTURE_DUPLICATE,
                        "A composition document must not repeat one composition id.");
            }
            accepted |= composition.accepted();
            rejected |= !composition.accepted();
            copy.add(composition);
        }
        if (!accepted || !rejected) {
            throw new T19EvaluationException(
                    T19EvaluationErrorCodes.FIXTURE_POLARITY,
                    "A composition document requires at least one accepted and one rejected composition.");
        }
        compositions = List.copyOf(copy);
    }
}