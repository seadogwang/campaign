package com.company.campaign.segment.dsl.contract;

import com.company.campaign.contracts.ContractPatterns;

import java.util.regex.Pattern;

/**
 * Bounded reference-identifier validation shared by every Segment DSL identifier.
 *
 * <p>Node IDs, attribute/metric/relation IDs and Metadata binding identifiers all use the one T01 reference
 * grammar, so an identifier cannot be valid in one place and invalid in another. The failure code is a
 * parameter because a malformed node ID and a malformed definition reference are distinct, actionable cases.
 */
public final class SegmentDslReferences {

    private static final Pattern REFERENCE_ID = Pattern.compile(ContractPatterns.REFERENCE_ID);

    private SegmentDslReferences() {
    }

    /** Validates a stable predicate node ID. */
    public static String requireNodeId(String nodeId) {
        return require(nodeId, "nodeId", SegmentDslErrorCodes.NODE_ID_INVALID);
    }

    /** Validates a bounded definition or binding reference identifier. */
    public static String requireReferenceId(String value, String field) {
        return require(value, field, SegmentDslErrorCodes.REFERENCE_ID_INVALID);
    }

    private static String require(String value, String field, String code) {
        if (value == null || !REFERENCE_ID.matcher(value).matches()) {
            throw new SegmentDslException(code, "Identifier '" + field + "' is not a valid reference identifier");
        }
        return value;
    }
}