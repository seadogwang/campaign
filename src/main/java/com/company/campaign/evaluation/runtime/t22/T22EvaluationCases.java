package com.company.campaign.evaluation.runtime.t22;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** The bounded T22 evaluation cases document: its frozen identity and its strictly unique case collection. */
public record T22EvaluationCases(
        String schemaVersion,
        String assetId,
        String assetVersion,
        boolean productionGateEvidence,
        List<T22EvaluationCase> cases) {

    /** The largest number of cases one T22 corpus may carry. */
    public static final int MAX_CASES = 64;

    public T22EvaluationCases {
        if (!T22EvaluationLoader.SCHEMA_VERSION.equals(schemaVersion)
                || !T22EvaluationLoader.CASES_ASSET_ID.equals(assetId)
                || !T22EvaluationLoader.ASSET_VERSION.equals(assetVersion)
                || productionGateEvidence
                || cases == null || cases.isEmpty() || cases.size() > MAX_CASES) {
            throw invalid();
        }
        Set<String> ids = new HashSet<>();
        for (T22EvaluationCase evaluationCase : cases) {
            if (evaluationCase == null || !ids.add(evaluationCase.id())) {
                throw invalid();
            }
        }
        cases = List.copyOf(cases);
    }

    /** Every case with the given closed category, in document order. */
    public List<T22EvaluationCase> withCategory(String category) {
        return cases.stream().filter(evaluationCase -> evaluationCase.category().equals(category)).toList();
    }

    /** The distinct closed categories this corpus declares, in declaration order. */
    public List<String> categories() {
        Set<String> distinct = new LinkedHashSet<>();
        for (T22EvaluationCase evaluationCase : cases) {
            distinct.add(evaluationCase.category());
        }
        return List.copyOf(distinct);
    }

    /** True when a case with the given identifier exists and declares the given assertion. */
    public boolean declares(String caseId, String assertion) {
        for (T22EvaluationCase evaluationCase : cases) {
            if (evaluationCase.id().equals(caseId)) {
                return evaluationCase.asserts(assertion);
            }
        }
        return false;
    }

    private static T22EvaluationException invalid() {
        return new T22EvaluationException(
                T22EvaluationErrorCodes.CORPUS_INVALID,
                "The T22 evaluation cases document is invalid.");
    }
}