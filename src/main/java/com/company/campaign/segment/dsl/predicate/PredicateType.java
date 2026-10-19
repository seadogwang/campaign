package com.company.campaign.segment.dsl.predicate;

import com.company.campaign.segment.dsl.contract.SegmentDslErrorCodes;
import com.company.campaign.segment.dsl.contract.SegmentDslException;

/**
 * The closed discriminator set for the predicate union.
 *
 * <p>{@link #AUDIENCE_REFERENCE} is the one subtype T18 adds: a leaf that pins one exact immutable Audience
 * Version. It is appended rather than inserted so no existing wire name, ordinal or failure moves. An unknown
 * wire type is still rejected rather than mapped to a catch-all, so a misspelt or future discriminator cannot
 * be silently read as a reference.
 */
public enum PredicateType {
    ALL,
    ANY,
    NOT,
    ATTRIBUTE,
    METRIC,
    METRIC_EXPRESSION,
    EXISTS,
    AUDIENCE_REFERENCE;

    /** Resolves a wire predicate type, or throws the stable unknown-discriminator failure. */
    public static PredicateType fromWire(String wireType) {
        for (PredicateType type : values()) {
            if (type.name().equals(wireType)) {
                return type;
            }
        }
        throw new SegmentDslException(
                SegmentDslErrorCodes.UNKNOWN_DISCRIMINATOR, "Unknown predicate type");
    }
}
