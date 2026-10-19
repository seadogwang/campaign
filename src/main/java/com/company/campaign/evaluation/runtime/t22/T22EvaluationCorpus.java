package com.company.campaign.evaluation.runtime.t22;

/** One loaded T22 evaluation corpus: its manifest and the cases document it pins by hash. */
public record T22EvaluationCorpus(
        T22EvaluationManifest manifest,
        T22EvaluationCases cases) {

    public T22EvaluationCorpus {
        if (manifest == null || cases == null
                || manifest.caseCount() != cases.cases().size()
                || !manifest.assetVersion().equals(cases.assetVersion())
                || !manifest.assetId().equals(T22EvaluationLoader.MANIFEST_ASSET_ID)) {
            throw new T22EvaluationException(
                    T22EvaluationErrorCodes.ASSET_IDENTITY_MISMATCH,
                    "The T22 evaluation assets disagree on their frozen identity.");
        }
    }
}