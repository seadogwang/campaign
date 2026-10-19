package com.company.campaign.readiness;

/**
 * The outcome of one append attempt against the assessment log.
 *
 * <p>{@link #assessment()} is always present, and {@link #revision()} is the committed scope revision after
 * the attempt, exactly as for {@link EvidenceAppendResult}.
 */
public record AssessmentAppendResult(
        ReadinessDisposition disposition,
        ReadinessAssessment assessment,
        long revision) {

    public AssessmentAppendResult {
        ReadinessSupport.requireNonNull(disposition, "disposition");
        ReadinessSupport.requireNonNull(assessment, "assessment");
        if (revision < 0) {
            throw new ReadinessException(
                    ReadinessErrorCodes.READINESS_ASSESSMENT_INVALID, "A committed revision cannot be negative.");
        }
    }

    /** True when this attempt committed a new assessment. */
    public boolean committed() {
        return disposition.committed();
    }
}