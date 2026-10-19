package com.company.campaign.evaluation.journey.t20;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

public record T20EvaluationCase(
        String id,
        String category,
        String expectedOutcome,
        List<String> assertions) {

    private static final Pattern CODE = Pattern.compile("^[A-Z][A-Z0-9_]{0,63}$");
    private static final Pattern ID = Pattern.compile("^[a-z][a-z0-9-]{0,63}$");

    public T20EvaluationCase {
        if (id == null || !ID.matcher(id).matches()) {
            throw invalid();
        }
        if (category == null || !CODE.matcher(category).matches()
                || expectedOutcome == null || !CODE.matcher(expectedOutcome).matches()) {
            throw invalid();
        }
        if (assertions == null || assertions.isEmpty() || assertions.size() > 16) {
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

    private static T20EvaluationException invalid() {
        return new T20EvaluationException(
                T20EvaluationErrorCodes.CORPUS_INVALID,
                "A T20 evaluation case is not a bounded machine contract.");
    }
}
