package com.company.campaign.metadata.domain;

/**
 * Closed semantic data types for attributes and metric results.
 *
 * <p>This is a metadata-owned vocabulary, not the T05 Segment {@code TypedValue} union: it describes what a
 * definition means, not how a segment carries a value.
 */
public enum SemanticDataType {

    STRING,
    BOOLEAN,
    INTEGER,
    DECIMAL,
    DATE,
    TIMESTAMP,
    ENUM,
    /** Closed coded values with a unique numeric rank used for ordering comparisons. */
    ORDERED_ENUM,
    MONEY;

    /** True for the numeric types that support SUM/AVG and ordering comparisons. */
    public boolean isNumeric() {
        return this == INTEGER || this == DECIMAL || this == MONEY;
    }

    /** True when a stable total order exists and ordering comparison operators are meaningful. */
    public boolean isOrdered() {
        return isNumeric() || this == DATE || this == TIMESTAMP || this == ORDERED_ENUM;
    }

    /** True when the type carries an explicit currency policy obligation. */
    public boolean isMoney() {
        return this == MONEY;
    }
}
