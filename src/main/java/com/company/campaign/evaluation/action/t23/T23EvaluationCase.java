package com.company.campaign.evaluation.action.t23;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * One bounded T23 external-action evaluation case: a stable identifier, a closed category, a closed expected
 * outcome and the bounded assertions the case proves.
 *
 * <p>It carries no payload, no hash, no member value and no host evidence: a case states what must hold, and the
 * focused unit, concurrency and fault-injection tests are what actually prove it.
 */
public record T23EvaluationCase(
        String id,
        String category,
        String expectedOutcome,
        List<String> assertions) {

    /** The largest number of assertions one case may carry. */
    public static final int MAX_ASSERTIONS = 16;

    private static final Pattern CODE = Pattern.compile("^[A-Z][A-Z0-9_]{0,63}$");
    private static final Pattern ID = Pattern.compile("^[a-z][a-z0-9-]{0,63}$");

    public T23EvaluationCase {
        if (id == null || !ID.matcher(id).matches()) {
            throw invalid();
        }
        if (category == null || !CODE.matcher(category).matches()
                || expectedOutcome == null || !CODE.matcher(expectedOutcome).matches()) {
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

    private static T23EvaluationException invalid() {
        return new T23EvaluationException(
                T23EvaluationErrorCodes.CORPUS_INVALID,
                "A T23 evaluation case is not a bounded machine contract.");
    }
}