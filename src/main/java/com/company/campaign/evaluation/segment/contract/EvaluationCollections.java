package com.company.campaign.evaluation.segment.contract;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Normalization shared by the evaluation expectation lists.
 *
 * <p>Two kinds of list exist in the expected-result contract and they must not be confused:
 *
 * <ul>
 *   <li><em>set-like</em> values — forbidden assumptions, clarification question codes, grounded claim
 *       ids, selected and rejected candidate ids. Order carries no meaning, so the list is sorted once at
 *       construction: equality becomes order-independent and canonical bytes become stable.</li>
 *   <li><em>ordered</em> values — expected validation issue codes. Segment specification section 23.1
 *       requires identical inputs to produce identical Issue <em>ordering</em>, so the declared order is
 *       part of the expectation and is preserved exactly.</li>
 * </ul>
 *
 * <p>Both kinds reject exact duplicates. A repeated element means the author intended something the list
 * cannot express, and silently collapsing it would make the corpus weaker than it reads.
 */
final class EvaluationCollections {

    private static final Comparator<String> CANONICAL_ORDER = Comparator.naturalOrder();

    private EvaluationCollections() {
    }

    /** Requires a list to be present, then normalizes it. */
    static List<String> require(List<String> values, String fieldName, Pattern elementPattern, boolean sorted) {
        if (values == null) {
            throw new EvaluationException(
                    EvaluationErrorCodes.ASSET_INVALID,
                    "Expected result field '" + fieldName + "' must be present, even when it is empty");
        }
        return normalize(values, fieldName, elementPattern, sorted);
    }

    /** Treats an absent list as an empty list, then normalizes it. */
    static List<String> optional(List<String> values, String fieldName, Pattern elementPattern, boolean sorted) {
        return normalize(values == null ? List.of() : values, fieldName, elementPattern, sorted);
    }

    private static List<String> normalize(
            List<String> values, String fieldName, Pattern elementPattern, boolean sorted) {

        List<String> checked = new ArrayList<>(values.size());
        Set<String> seen = new HashSet<>(values.size());
        for (String value : values) {
            if (value == null || !elementPattern.matcher(value).matches()) {
                throw new EvaluationException(
                        EvaluationErrorCodes.ID_INVALID,
                        "Value '" + value + "' in '" + fieldName + "' is outside its bounded grammar",
                        Map.of("field", fieldName, "value", String.valueOf(value)));
            }
            if (!seen.add(value)) {
                throw new EvaluationException(
                        EvaluationErrorCodes.DUPLICATE_SET_VALUE,
                        "Value '" + value + "' appears more than once in '" + fieldName + "'",
                        Map.of("field", fieldName, "value", value));
            }
            checked.add(value);
        }
        if (sorted) {
            checked.sort(CANONICAL_ORDER);
        }
        return List.copyOf(checked);
    }
}