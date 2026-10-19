package com.company.campaign.segment.intent;

import com.company.campaign.contracts.SourceSpan;

/**
 * Grounds a clause's source span in the original utterance.
 *
 * <p>Offsets are UTF-16 code units over the half-open range {@code [start, end)}, and the span text must
 * equal the exact substring of the utterance. Empty spans, out-of-range spans, spans whose boundary falls
 * between the two halves of a surrogate pair and spans whose text does not match all fail closed with a
 * stable T10 code. The validator never echoes the utterance or the span text in a failure.
 */
public final class SourceSpanValidator {

    private SourceSpanValidator() {
    }

    /**
     * Validates a non-empty source span against the original utterance.
     *
     * @throws IntentException when the utterance or span is missing, the span is empty, out of range,
     *                         surrogate-splitting, or its text does not match the utterance substring
     */
    public static void validate(String utterance, SourceSpan span) {
        if (utterance == null) {
            throw IntentException.invalid(
                    IntentErrorCodes.UTTERANCE_MISSING, "The original utterance is required to ground a span");
        }
        if (span == null) {
            throw IntentException.invalid(
                    IntentErrorCodes.SPAN_MISSING, "A source-span clause requires a span");
        }
        int start = span.start();
        int end = span.end();
        if (start == end) {
            throw IntentException.invalid(
                    IntentErrorCodes.SPAN_EMPTY, "A clause source span must carry user text");
        }
        if (start < 0 || end < start || end > utterance.length()) {
            throw IntentException.invalid(
                    IntentErrorCodes.SPAN_OUT_OF_RANGE, "A clause source span is outside the utterance");
        }
        if (splitsSurrogate(utterance, start, end)) {
            throw IntentException.invalid(
                    IntentErrorCodes.SPAN_SURROGATE_SPLIT, "A clause source span splits a surrogate pair");
        }
        String exact = utterance.substring(start, end);
        if (span.text() == null || !span.text().equals(exact)) {
            throw IntentException.invalid(
                    IntentErrorCodes.SPAN_TEXT_MISMATCH,
                    "A clause source span text does not match the utterance substring");
        }
    }

    private static boolean splitsSurrogate(String utterance, int start, int end) {
        if (start > 0 && Character.isHighSurrogate(utterance.charAt(start - 1))
                && Character.isLowSurrogate(utterance.charAt(start))) {
            return true;
        }
        return end < utterance.length() && Character.isHighSurrogate(utterance.charAt(end - 1))
                && Character.isLowSurrogate(utterance.charAt(end));
    }
}