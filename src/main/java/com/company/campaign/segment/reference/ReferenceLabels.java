package com.company.campaign.segment.reference;

/**
 * The one bound every host-supplied display label of the reference discovery surface must satisfy.
 *
 * <p>A label is presentation data that reaches a clarification option, so it is bounded here once rather than
 * being re-bounded at each producer. Rejected label text is never echoed: the failure says only that a label
 * is not admissible.
 */
final class ReferenceLabels {

    /** Maximum UTF-16 length of one safe display label. */
    static final int MAX_LABEL_LENGTH = 256;

    private ReferenceLabels() {
    }

    static String requireLabel(String label, String message) {
        if (label == null || label.isBlank() || label.length() > MAX_LABEL_LENGTH) {
            throw ReferenceException.invalid(message);
        }
        return label;
    }
}