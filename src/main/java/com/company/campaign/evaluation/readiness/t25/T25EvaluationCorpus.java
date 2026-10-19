package com.company.campaign.evaluation.readiness.t25;

/** One loaded T25 readiness evaluation corpus: its manifest and the cases document it pins by hash. */
public record T25EvaluationCorpus(
        T25EvaluationManifest manifest,
        T25EvaluationCases cases) {

    public T25EvaluationCorpus {
        if (manifest == null || cases == null
                || manifest.caseCount() != cases.cases().size()
                || !manifest.assetVersion().equals(cases.assetVersion())
                || !manifest.assetId().equals(T25EvaluationLoader.MANIFEST_ASSET_ID)) {
            throw new T25EvaluationException(
                    T25EvaluationErrorCodes.ASSET_IDENTITY_MISMATCH,
                    "The T25 evaluation assets disagree on their frozen identity.");
        }
    }
}