package com.company.campaign.segment.intent;

/**
 * Closed discriminator for an {@link IntentValue}.
 *
 * <p>They name the <em>raw shape</em> the model claimed, not a validated Metadata data type. Resolution
 * later decides whether the shape is compatible with a definition; the Intent contract never pretends a
 * raw value is an already-bound typed DSL value.
 */
public enum IntentValueKind {

    STRING,
    BOOLEAN,
    INTEGER,
    DECIMAL,
    DATE,
    TIMESTAMP,
    ENUM,
    MONEY,
    LIST,
    RANGE;

    /** True for the scalar kinds that may appear inside a LIST or as a RANGE bound. */
    public boolean isScalar() {
        return this != LIST && this != RANGE;
    }
}