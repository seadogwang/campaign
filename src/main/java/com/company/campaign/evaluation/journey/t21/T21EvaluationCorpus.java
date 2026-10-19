package com.company.campaign.evaluation.journey.t21;

/** One loaded T21 evaluation corpus: its manifest and the cases document it pins by hash. */
public record T21EvaluationCorpus(
        T21EvaluationManifest manifest,
        T21EvaluationCases cases) {

    public T21EvaluationCorpus {
        if (manifest == null || cases == null
                || manifest.caseCount() != cases.cases().size()
                || !manifest.assetVersion().equals(cases.assetVersion())) {
            throw new T21EvaluationException(
                    T21EvaluationErrorCodes.ASSET_IDENTITY_MISMATCH,
                    "The T21 evaluation assets disagree on their frozen identity.");
        }
    }
}