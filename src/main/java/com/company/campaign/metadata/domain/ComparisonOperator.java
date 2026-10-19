package com.company.campaign.metadata.domain;

/**
 * Closed comparison operators for attributes and metric results, including the NULL operators.
 *
 * <p>Each operator declares which {@link SemanticDataType}s it applies to, so a definition that pairs an
 * ordering operator with a boolean or enum type, a string operator with a numeric type, or {@code BETWEEN}
 * with an unordered type is rejected rather than left for a future planner to misinterpret.
 *
 * <h2>Canonical ordering</h2>
 *
 * <p>The declaration order below is the canonical operator order: it is the order {@link java.util.EnumSet}
 * yields for a definition's allowed operators, so a snapshot's canonical bytes and hash never depend on the
 * order a caller listed operators in. The order is part of the contract and is stable: the operators added
 * for Segment spec section 8 ({@code BETWEEN} and the four string operators) are declared after the
 * previously published block, which keeps the relative canonical order of every operator that existed before
 * them unchanged. A future operator is appended the same way, never inserted into the existing block.
 */
public enum ComparisonOperator {

    EQUAL,
    NOT_EQUAL,
    IN,
    NOT_IN,
    GREATER_THAN,
    GREATER_OR_EQUAL,
    LESS_THAN,
    LESS_OR_EQUAL,
    IS_NULL,
    IS_NOT_NULL,
    BETWEEN,
    CONTAINS,
    NOT_CONTAINS,
    STARTS_WITH,
    ENDS_WITH;

    /** True for {@code IS_NULL}/{@code IS_NOT_NULL}, which never carry an operand value. */
    public boolean isNullOperator() {
        return this == IS_NULL || this == IS_NOT_NULL;
    }

    private boolean isOrderingOperator() {
        return switch (this) {
            case GREATER_THAN, GREATER_OR_EQUAL, LESS_THAN, LESS_OR_EQUAL -> true;
            default -> false;
        };
    }

    /**
     * True for the string operators, which are defined only for {@link SemanticDataType#STRING}.
     *
     * <p>{@code NOT_CONTAINS} is the explicit non-membership form; like every other comparison it does not
     * implicitly include NULL, which still requires an explicit {@code IS_NULL} branch.
     */
    private boolean isStringOperator() {
        return switch (this) {
            case CONTAINS, NOT_CONTAINS, STARTS_WITH, ENDS_WITH -> true;
            default -> false;
        };
    }

    /** True when this operator is meaningful for the supplied data type. */
    public boolean supports(SemanticDataType dataType) {
        if (dataType == null) {
            return false;
        }
        if (isNullOperator()) {
            return true;
        }
        if (isStringOperator()) {
            return dataType == SemanticDataType.STRING;
        }
        if (this == BETWEEN) {
            return dataType.isOrdered();
        }
        if (isOrderingOperator()) {
            return dataType.isOrdered();
        }
        return true;
    }
}