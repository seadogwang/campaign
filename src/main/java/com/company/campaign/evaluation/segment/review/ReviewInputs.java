package com.company.campaign.evaluation.segment.review;

import com.company.campaign.contracts.ContractPatterns;
import com.company.campaign.evaluation.segment.contract.CaseReviewEntry;
import com.company.campaign.evaluation.segment.contract.EvaluationErrorCodes;
import com.company.campaign.evaluation.segment.contract.EvaluationException;
import com.company.campaign.evaluation.segment.contract.ReviewRole;

import java.util.regex.Pattern;

/**
 * Shared bounded-input checks for the review workflow contracts.
 *
 * <p>These rules are the same ones {@link CaseReviewEntry} applies to a decided ledger entry, kept in one
 * place so the command boundary and the ledger cannot drift apart. Every rejection is a stable review code
 * with prose that never echoes the rejected value, because a reviewer reference or note may itself be
 * sensitive.
 */
final class ReviewInputs {

    private static final Pattern REFERENCE_ID = Pattern.compile(ContractPatterns.REFERENCE_ID);

    private ReviewInputs() {
    }

    /** A bounded opaque reference: no email, no whitespace, no payload. */
    static String requireReference(String value, String field) {
        if (value == null || !REFERENCE_ID.matcher(value).matches()) {
            throw new EvaluationException(
                    EvaluationErrorCodes.REVIEW_REFERENCE_INVALID,
                    "Review " + field + " must be one bounded opaque reference");
        }
        return value;
    }

    /**
     * Bounded plain-prose notes.
     *
     * <p>The same shape the ledger enforces: non-blank, within the contract bound, no control characters
     * and no braces or brackets, so a note can never smuggle a structured payload into a decision.
     */
    static String requireNotes(ReviewRole role, String notes) {
        if (notes == null || notes.isBlank()) {
            throw new EvaluationException(
                    EvaluationErrorCodes.REVIEW_ENTRY_INVALID,
                    "Review decision for role " + role + " must carry notes");
        }
        if (notes.length() > CaseReviewEntry.MAX_REVIEW_NOTES_LENGTH) {
            throw new EvaluationException(
                    EvaluationErrorCodes.REVIEW_NOTES_INVALID,
                    "Review notes for role " + role
                            + " exceed the bounded review-note length");
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