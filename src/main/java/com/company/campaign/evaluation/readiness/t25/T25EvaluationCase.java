package com.company.campaign.evaluation.readiness.t25;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * One bounded T25 readiness evaluation case: a stable identifier, a closed category, a closed expected
 * outcome and the bounded assertions the case proves.
 *
 * <p>Both the category and the expected outcome are closed sets rather than free uppercase codes, so an
 * unsupported value is refused by construction rather than silently accepted as a new category. A case
 * states what must hold; the focused model, evaluator, store, migration, audit and isolation tests are what
 * actually prove it.
 */
public record T25EvaluationCase(
        String id,
        String category,
        String expectedOutcome,
        List<String> assertions) {

    /** The largest number of assertions one case may carry. */
    public static final int MAX_ASSERTIONS = 16;

    /** The closed set of T25 case categories. */
    public static final Set<String> CATEGORIES = Set.of(
            "LOCAL", "AI", "LIVE", "EVIDENCE", "ORDERING", "MIGRATION",
            "PERSISTENCE", "AUDIT", "GAPS", "ISOLATION", "INFRASTRUCTURE");

    /** The closed set of T25 expected outcomes. */
    public static final Set<String> OUTCOMES = Set.of(
            "ACCEPTED", "BLOCKED", "INVALID", "REFUSED", "CONFLICT", "DUPLICATE", "STALE");

    private static final Pattern CODE = Pattern.compile("^[A-Z][A-Z0-9_]{0,63}$");
    private static final Pattern ID = Pattern.compile("^[a-z][a-z0-9-]{0,63}$");

    public T25EvaluationCase {
        if (id == null || !ID.matcher(id).matches()) {
            throw invalid();
        }
        if (category == null || !CATEGORIES.contains(category)) {
            throw invalid();
        }
        if (expectedOutcome == null || !OUTCOMES.contains(expectedOutcome)) {
            throw invalid();
        }
        if (assertions == null || assertions.isEmpty() || assertions.size() > MAX_ASSERTIONS) {
            throw invalid();
        }
        Set<String> unique = new HashSet<>();
        for (String assertion : assertions) {
            if (assertion == null || !CODE.matcher(assertion).matches() || !unique.add(assertion)) {
                throw invalid();
            }
        }
        assertions = List.copyOf(assertions);
    }

    /** True when this case declares the given bounded assertion. */
    public boolean asserts(String assertion) {
        return assertions.contains(assertion);
    }

    private static T25EvaluationException invalid() {
        return new T25EvaluationException(
                T25EvaluationErrorCodes.CORPUS_INVALID,
                "A T25 evaluation case is not a bounded machine contract.");
    }
}