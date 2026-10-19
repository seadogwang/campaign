package com.company.campaign.evaluation.readiness.t25;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** The bounded T25 evaluation cases document: its frozen identity and its strictly unique case collection. */
public record T25EvaluationCases(
        String schemaVersion,
        String assetId,
        String assetVersion,
        boolean productionGateEvidence,
        List<T25EvaluationCase> cases) {

    /** The largest number of cases one T25 corpus may carry. */
    public static final int MAX_CASES = 64;

    public T25EvaluationCases {
        if (!T25EvaluationLoader.SCHEMA_VERSION.equals(schemaVersion)
                || !T25EvaluationLoader.CASES_ASSET_ID.equals(assetId)
                || !T25EvaluationLoader.ASSET_VERSION.equals(assetVersion)
                || productionGateEvidence
                || cases == null || cases.isEmpty() || cases.size() > MAX_CASES) {
            throw invalid();
        }
        Set<String> ids = new HashSet<>();
        for (T25EvaluationCase evaluationCase : cases) {
            if (evaluationCase == null || !ids.add(evaluationCase.id())) {
                throw invalid();
            }
        }
        cases = List.copyOf(cases);
    }

    /** Every case with the given closed category, in document order. */
    public List<T25EvaluationCase> withCategory(String category) {
        return cases.stream().filter(evaluationCase -> evaluationCase.category().equals(category)).toList();
    }

    /** The distinct closed categories this corpus declares, in declaration order. */
    public List<String> categories() {
        Set<String> distinct = new LinkedHashSet<>();
        for (T25EvaluationCase evaluationCase : cases) {
            distinct.add(evaluationCase.category());
        }
        return List.copyOf(distinct);
    }

    /** True when a case with the given identifier exists and declares the given assertion. */
    public boolean declares(String caseId, String assertion) {
        for (T25EvaluationCase evaluationCase : cases) {
            if (evaluationCase.id().equals(caseId)) {
                return evaluationCase.asserts(assertion);
            }
        }
        return false;
    }

    /** Every case whose identifier contains the given fragment, in document order. */
    public List<T25EvaluationCase> withIdContaining(String fragment) {
        return cases.stream().filter(evaluationCase -> evaluationCase.id().contains(fragment)).toList();
    }

    private static T25EvaluationException invalid() {
        return new T25EvaluationException(
                T25EvaluationErrorCodes.CORPUS_INVALID,
                "The T25 evaluation cases document is invalid.");
    }
}