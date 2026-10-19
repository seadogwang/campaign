package com.company.campaign.evaluation.journey.t20;

public record T20EvaluationCorpus(
        T20EvaluationManifest manifest,
        T20EvaluationCases cases) {

    public T20EvaluationCorpus {
        if (manifest == null || cases == null
                || manifest.caseCount() != cases.cases().size()
                || !manifest.assetVersion().equals(cases.assetVersion())) {
            throw new T20EvaluationException(
                    T20EvaluationErrorCodes.ASSET_IDENTITY_MISMATCH,
                    "The T20 evaluation assets disagree on their frozen identity.");
        }
    }
}
