package com.company.campaign.builder.refine;

import com.company.campaign.contracts.ContractPatterns;

import java.util.regex.Pattern;

/**
 * Stable-identity validation shared by every refinement operation identifier.
 *
 * <p>An operation identifier, a node identifier, a destination parent identifier and a sibling anchor all use
 * the one T01 reference grammar, so an identifier is never valid in one position and invalid in another.
 *
 * <p>Two further rules exist because this contract's whole point is stable identity rather than position:
 * a purely numeric identifier is indistinguishable from an array index, and a JSON Pointer or JSON Patch path
 * is a positional address by construction. Both are refused with their own stable code instead of being
 * accepted as an exotic node name, so "never array indexes, JSON Pointer or JSON Patch paths" is enforced
 * rather than merely documented.
 */
public final class RefinementIdentifiers {

    private static final Pattern REFERENCE_ID = Pattern.compile(ContractPatterns.REFERENCE_ID);
    private static final Pattern DIGITS_ONLY = Pattern.compile("^[0-9]+$");

    private RefinementIdentifiers() {
    }

    /** Validates one stable identifier, or throws the supplied stable code. */
    public static String require(String value, String invalidCode, String field) {
        if (value == null) {
            throw SegmentRefinementException.of(
                    SegmentRefinementErrorCodes.NULL_FIELD, "A stable identifier is required: " + field);
        }
        if (isPositionalPath(value)) {
            throw SegmentRefinementException.of(
                    SegmentRefinementErrorCodes.INDEX_PATH_REJECTED,
                    "A position must be addressed by stable identity, not by an index or path: " + field);
        }
        if (!REFERENCE_ID.matcher(value).matches()) {
            throw SegmentRefinementException.of(
                    invalidCode, "A stable identifier is malformed: " + field);
        }
        return value;
    }

    /** True when the value is a JSON Pointer/Patch path or a bare array index rather than a stable identity. */
    public static boolean isPositionalPath(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        if (DIGITS_ONLY.matcher(value).matches()) {
            return true;
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character == '/' || character == '[' || character == ']' || character == '$'
                    || character == '~' || character == '*') {
                return true;
            }
        }
        return false;
    }
}