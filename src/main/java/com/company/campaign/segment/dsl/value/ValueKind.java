package com.company.campaign.segment.dsl.value;

import com.company.campaign.segment.dsl.contract.SegmentDslErrorCodes;
import com.company.campaign.segment.dsl.contract.SegmentDslException;

/**
 * The closed discriminator set for the {@code TypedValue} union.
 *
 * <p>The wire value is the enum name exactly; there is no open or lowercase alias, so an unknown kind can
 * never be silently mapped onto a known one. This enum is the single source of truth the codec and the
 * checked-in JSON Schema are aligned against.
 */
public enum ValueKind {
    STRING,
    INTEGER,
    DECIMAL,
    BOOLEAN,
    DATE,
    DATETIME,
    ENUM,
    MONEY,
    LIST,
    RANGE;

    /** Resolves a wire kind, or throws the stable unknown-discriminator failure. */
    public static ValueKind fromWire(String wireKind) {
        for (ValueKind kind : values()) {
            if (kind.name().equals(wireKind)) {
                return kind;
            }
        }
        throw new SegmentDslException(
                SegmentDslErrorCodes.UNKNOWN_DISCRIMINATOR, "Unknown TypedValue kind");
    }
}