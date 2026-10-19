package com.company.campaign.evaluation.action.t23;

/** One loaded T23 evaluation corpus: its manifest and the cases document it pins by hash. */
public record T23EvaluationCorpus(
        T23EvaluationManifest manifest,
        T23EvaluationCases cases) {

    public T23EvaluationCorpus {
        if (manifest == null || cases == null
                || manifest.caseCount() != cases.cases().size()
                || !manifest.assetVersion().equals(cases.assetVersion())
                || !manifest.assetId().equals(T23EvaluationLoader.MANIFEST_ASSET_ID)) {
            throw new T23EvaluationException(
                    T23EvaluationErrorCodes.ASSET_IDENTITY_MISMATCH,
                    "The T23 evaluation assets disagree on their frozen identity.");
        }
    }
}