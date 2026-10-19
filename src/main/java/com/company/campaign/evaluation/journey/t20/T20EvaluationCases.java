package com.company.campaign.evaluation.journey.t20;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public record T20EvaluationCases(
        String schemaVersion,
        String assetId,
        String assetVersion,
        boolean productionGateEvidence,
        List<T20EvaluationCase> cases) {

    public static final int MAX_CASES = 64;

    public T20EvaluationCases {
        if (!T20EvaluationLoader.SCHEMA_VERSION.equals(schemaVersion)
                || !T20EvaluationLoader.CASES_ASSET_ID.equals(assetId)
                || !T20EvaluationLoader.ASSET_VERSION.equals(assetVersion)
                || productionGateEvidence
                || cases == null || cases.isEmpty() || cases.size() > MAX_CASES) {
            throw invalid();
        }
        Set<String> ids = new HashSet<>();
        for (T20EvaluationCase evaluationCase : cases) {
            if (evaluationCase == null || !ids.add(evaluationCase.id())) {
                throw invalid();
            }
        }
        cases = List.copyOf(cases);
    }

    private static T20EvaluationException invalid() {
        return new T20EvaluationException(
                T20EvaluationErrorCodes.CORPUS_INVALID,
                "The T20 evaluation cases document is invalid.");
    }
}
