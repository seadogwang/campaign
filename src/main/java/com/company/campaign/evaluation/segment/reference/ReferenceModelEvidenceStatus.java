package com.company.campaign.evaluation.segment.reference;

/**
 * The closed set of model-evidence claims a reference corpus manifest may make about itself.
 *
 * <p>{@code NOT_RUN} is the honest state of a corpus that has only ever been evaluated deterministically;
 * {@code RECORDED} says a real configured-model run exists and names the external artifact it was recorded
 * to. There is no {@code PASSED} constant and no way to spell one, so a model verdict is unrepresentable
 * here: the reference corpus reports what ran, and a Gate decision — which needs the real run — is not
 * something a corpus file can assert.
 */
public enum ReferenceModelEvidenceStatus {

    /** No configured-model run has been recorded against this corpus revision. */
    NOT_RUN,

    /** A real configured-model run exists and is recorded under the manifest's evidence reference. */
    RECORDED;
}