package com.company.campaign.evaluation.capabilities.t19;

import com.company.campaign.contracts.ContractPatterns;
import com.company.campaign.shared.ImmutableValues;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * One resolved component of a complete-composition fixture.
 *
 * <p>It carries exactly the facts a resolved {@code ResolvedComponent} carries — stable logical id, exact
 * capability and contract version, composition slot code, normalized payload value and resolved references —
 * so the fixture drives the real composition validator rather than a parallel re-implementation of it. The
 * normalized value is a bounded, reserved-free JSON object and never a template body, a reward secret, a
 * credential or a member identifier.
 */
public record T19CompositionComponent(
        String logicalId,
        String capabilityCode,
        String contractVersion,
        String slot,
        Map<String, Object> value,
        List<T19CompositionReference> references) {

    private static final Pattern ID = Pattern.compile(ContractPatterns.REFERENCE_ID);
    private static final Pattern CAPABILITY = Pattern.compile("^[a-z][a-z0-9.-]{0,63}$");
    private static final Pattern SLOT = Pattern.compile("^[a-z][a-z0-9._-]{0,63}$");

    public T19CompositionComponent {
        if (logicalId == null || !ID.matcher(logicalId).matches()) {
            throw new T19EvaluationException(
                    T19EvaluationErrorCodes.ASSET_NON_CANONICAL,
                    "A composition component requires a bounded logical id.");
        }
        if (capabilityCode == null || !CAPABILITY.matcher(capabilityCode).matches()) {
            throw new T19EvaluationException(
                    T19EvaluationErrorCodes.ASSET_NON_CANONICAL,
                    "A composition component requires a canonical capability code.");
        }
        if (contractVersion == null || !ID.matcher(contractVersion).matches()) {
            throw new T19EvaluationException(
                    T19EvaluationErrorCodes.ASSET_NON_CANONICAL,
                    "A composition component requires an exact contract version.");
        }
        if (slot == null || !SLOT.matcher(slot).matches()) {
            throw new T19EvaluationException(
                    T19EvaluationErrorCodes.ASSET_NON_CANONICAL,
                    "A composition component requires a canonical composition slot.");
        }
        value = value == null
                ? Map.of()
                : ImmutableValues.freezeMap(new LinkedHashMap<>(value));
        List<T19CompositionReference> copy = references == null ? List.of() : new ArrayList<>(references);
        for (T19CompositionReference reference : copy) {
            if (reference == null) {
                throw new T19EvaluationException(
                        T19EvaluationErrorCodes.ASSET_NON_CANONICAL,
                        "A composition component must not carry a null reference.");
            }
        }
        references = List.copyOf(copy);
    }
}