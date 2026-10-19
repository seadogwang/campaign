package com.company.campaign.evaluation.action.t23;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** The bounded T23 evaluation cases document: its frozen identity and its strictly unique case collection. */
public record T23EvaluationCases(
        String schemaVersion,
        String assetId,
        String assetVersion,
        boolean productionGateEvidence,
        List<T23EvaluationCase> cases) {

    /** The largest number of cases one T23 corpus may carry. */
    public static final int MAX_CASES = 64;

    public T23EvaluationCases {
        if (!T23EvaluationLoader.SCHEMA_VERSION.equals(schemaVersion)
                || !T23EvaluationLoader.CASES_ASSET_ID.equals(assetId)
                || !T23EvaluationLoader.ASSET_VERSION.equals(assetVersion)
                || productionGateEvidence
                || cases == null || cases.isEmpty() || cases.size() > MAX_CASES) {
            throw invalid();
        }
        Set<String> ids = new HashSet<>();
        for (T23EvaluationCase evaluationCase : cases) {
            if (evaluationCase == null || !ids.add(evaluationCase.id())) {
                throw invalid();
            }
        }
        cases = List.copyOf(cases);
    }

    /** Every case with the given closed category, in document order. */
    public List<T23EvaluationCase> withCategory(String category) {
        return cases.stream().filter(evaluationCase -> evaluationCase.category().equals(category)).toList();
    }

    /** The distinct closed categories this corpus declares, in declaration order. */
    public List<String> categories() {
        Set<String> distinct = new LinkedHashSet<>();
        for (T23EvaluationCase evaluationCase : cases) {
            distinct.add(evaluationCase.category());
        }
        return List.copyOf(distinct);
    }

    /** True when a case with the given identifier exists and declares the given assertion. */
    public boolean declares(String caseId, String assertion) {
        for (T23EvaluationCase evaluationCase : cases) {
            if (evaluationCase.id().equals(caseId)) {
                return evaluationCase.asserts(assertion);
            }
        }
        return false;
    }

    private static T23EvaluationException invalid() {
        return new T23EvaluationException(
                T23EvaluationErrorCodes.CORPUS_INVALID,
                "The T23 evaluation cases document is invalid.");
    }
}