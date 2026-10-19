package com.company.campaign.readiness;

/**
 * The outcome of one append attempt against the evidence log.
 *
 * <p>{@link #evidence()} is always present: for {@link ReadinessDisposition#RECORDED} it is the committed
 * record, for {@link ReadinessDisposition#DUPLICATE} the already-stored record with the same identity, and
 * for a conflict the stored material the caller collided with. {@link #revision()} is the committed scope
 * revision after the attempt, so a caller learns the value to use next even when it wrote nothing.
 */
public record EvidenceAppendResult(
        ReadinessDisposition disposition,
        RequirementEvidence evidence,
        long revision) {

    public EvidenceAppendResult {
        ReadinessSupport.requireNonNull(disposition, "disposition");
        ReadinessSupport.requireNonNull(evidence, "evidence");
        if (revision < 0) {
            throw new ReadinessException(
                    ReadinessErrorCodes.READINESS_ASSESSMENT_INVALID, "A committed revision cannot be negative.");
        }
    }

    /** True when this attempt committed a new record. */
    public boolean committed() {
        return disposition.committed();
    }
}