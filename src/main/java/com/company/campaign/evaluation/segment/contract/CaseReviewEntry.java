package com.company.campaign.evaluation.segment.contract;

import com.company.campaign.contracts.ContractPatterns;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * One owner role's review entry on one Golden Corpus case.
 *
 * <p>An entry is either a <em>pending</em> placeholder or a real decision, and the contract refuses to let
 * the two be confused. A {@link ReviewStatus#PENDING} entry may not carry a reviewer, an evidence revision
 * or a decision time, because a generated ledger that fabricated any of them would read as human approval
 * that never happened. An {@link ReviewStatus#APPROVED} or {@link ReviewStatus#REJECTED} entry must carry
 * all three, because a decision without an attributable reviewer and a frozen evidence revision is not
 * reviewable evidence.
 *
 * <p>References are bounded identifiers, never free-form data. {@link ContractPatterns#REFERENCE_ID} admits
 * neither {@code @} nor whitespace, so a reviewer email address or a pasted payload cannot be stored here;
 * a reviewer is named by an opaque revision-controlled reference instead. Review notes are bounded prose
 * that may not contain braces or brackets, so they cannot be used to smuggle a raw JSON payload into the
 * ledger.
 */
@JsonIgnoreProperties(ignoreUnknown = false)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CaseReviewEntry(
        ReviewRole role,
        ReviewStatus status,
        String reviewerRef,
        String evidenceRef,
        Instant decisionTime,
        String reviewNotes
) {

    /** Maximum length of a review note, in UTF-16 code units. */
    public static final int MAX_REVIEW_NOTES_LENGTH = 280;

    private static final Pattern REFERENCE_ID = Pattern.compile(ContractPatterns.REFERENCE_ID);

    public CaseReviewEntry {
        if (role == null) {
            throw new EvaluationException(
                    EvaluationErrorCodes.REVIEW_ENTRY_INVALID, "A review entry must declare its role");
        }
        if (status == null) {
            throw new EvaluationException(
                    EvaluationErrorCodes.REVIEW_ENTRY_INVALID,
                    "Review entry for role " + role + " must declare its status");
        }
        reviewNotes = requireNotes(role, reviewNotes);

        if (status == ReviewStatus.PENDING) {
            if (reviewerRef != null || evidenceRef != null || decisionTime != null) {
                throw new EvaluationException(
                        EvaluationErrorCodes.REVIEW_DECISION_FABRICATED,
                        "Pending review entry for role " + role
                                + " must not carry a reviewer, evidence reference or decision time",
                        Map.of("role", role.name()));
            }
        } else {
            reviewerRef = requireReference(role, "reviewerRef", reviewerRef);
            evidenceRef = requireReference(role, "evidenceRef", evidenceRef);
            if (decisionTime == null) {
                throw new EvaluationException(
                        EvaluationErrorCodes.REVIEW_ENTRY_INVALID,
                        "Review entry for role " + role + " is " + status + " but declares no decision time",
                        Map.of("role", role.name()));
            }
        }
    }

    /** A generated pending entry: review is due, and no decision field is present. */
    public static CaseReviewEntry pending(ReviewRole role, String notes) {
        return new CaseReviewEntry(role, ReviewStatus.PENDING, null, null, null, notes);
    }

    /** A real decision. The decision time is supplied by the reviewer, never read from a clock. */
    public static CaseReviewEntry decided(
            ReviewRole role, ReviewStatus status, String reviewerRef, String evidenceRef,
            Instant decisionTime, String notes) {
        return new CaseReviewEntry(role, status, reviewerRef, evidenceRef, decisionTime, notes);
    }

    /**
     * True when this entry records a real human decision rather than a due review.
     *
     * <p>{@link JsonIgnore} keeps this derived predicate out of the wire shape: without it Jackson would
     * write a synthetic {@code decided} property that the strict reader would then reject as unknown.
     */
    @JsonIgnore
    public boolean isDecided() {
        return status.isDecided();
    }

    private static String requireReference(ReviewRole role, String field, String value) {
        if (value == null || !REFERENCE_ID.matcher(value).matches()) {
            throw new EvaluationException(
                    EvaluationErrorCodes.REVIEW_REFERENCE_INVALID,
                    "Review entry for role " + role + " has no usable " + field,
                    Map.of("role", role.name(), "field", field));
        }
        return value;
    }

    private static String requireNotes(ReviewRole role, String notes) {
        if (notes == null || notes.isBlank()) {
            throw new EvaluationException(
                    EvaluationErrorCodes.REVIEW_ENTRY_INVALID,
                    "Review entry for role " + role + " must carry review notes");
        }
        if (notes.length() > MAX_REVIEW_NOTES_LENGTH) {
            throw new EvaluationException(
                    EvaluationErrorCodes.REVIEW_NOTES_INVALID,
                    "Review notes for role " + role + " exceed " + MAX_REVIEW_NOTES_LENGTH + " characters");
        }
        for (int index = 0; index < notes.length(); index++) {
            char character = notes.charAt(index);
            if (Character.isISOControl(character)
                    || character == '{' || character == '}' || character == '[' || character == ']') {
                throw new EvaluationException(
                        EvaluationErrorCodes.REVIEW_NOTES_INVALID,
                        "Review notes for role " + role
                                + " must be plain prose, not a control character or a structured payload");
            }
        }
        return notes;
    }
}