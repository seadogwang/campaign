package com.company.campaign.evaluation.journey.t21;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** The bounded T21 evaluation cases document: its frozen identity and its strictly unique case collection. */
public record T21EvaluationCases(
        String schemaVersion,
        String assetId,
        String assetVersion,
        boolean productionGateEvidence,
        List<T21EvaluationCase> cases) {

    /** The largest number of cases one T21 corpus may carry. */
    public static final int MAX_CASES = 64;

    public T21EvaluationCases {
        if (!T21EvaluationLoader.SCHEMA_VERSION.equals(schemaVersion)
                || !T21EvaluationLoader.CASES_ASSET_ID.equals(assetId)
                || !T21EvaluationLoader.ASSET_VERSION.equals(assetVersion)
                || productionGateEvidence
                || cases == null || cases.isEmpty() || cases.size() > MAX_CASES) {
            throw invalid();
        }
        Set<String> ids = new HashSet<>();
        for (T21EvaluationCase evaluationCase : cases) {
            if (evaluationCase == null || !ids.add(evaluationCase.id())) {
                throw invalid();
            }
        }
        cases = List.copyOf(cases);
    }

    /** Every case with the given closed category, in document order. */
    public List<T21EvaluationCase> withCategory(String category) {
        return cases.stream().filter(evaluationCase -> evaluationCase.category().equals(category)).toList();
    }

    /** The distinct closed categories this corpus declares, in declaration order. */
    public List<String> categories() {
        Set<String> distinct = new java.util.LinkedHashSet<>();
        for (T21EvaluationCase evaluationCase : cases) {
            distinct.add(evaluationCase.category());
        }
        return List.copyOf(distinct);
    }

    /** True when a case with the given identifier exists and declares the given assertion. */
    public boolean declares(String caseId, String assertion) {
        for (T21EvaluationCase evaluationCase : cases) {
            if (evaluationCase.id().equals(caseId)) {
                return evaluationCase.asserts(assertion);
            }
        }
        return false;
    }

    private static T21EvaluationException invalid() {
        return new T21EvaluationException(
                T21EvaluationErrorCodes.CORPUS_INVALID,
                "The T21 evaluation cases document is invalid.");
    }
}