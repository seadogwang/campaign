package com.company.campaign.query.sql;

/**
 * The closed set of compiler-owned scalar comparison operators.
 *
 * <p>It deliberately excludes the NULL predicates, the list/range operators and the string operators: those
 * have their own dedicated AST nodes so {@code NOT_EQUAL} can never be rewritten into {@code NOT(EQUAL)},
 * {@code NOT_IN} into a broad negation, or a string operation into an invented function call.
 */
public enum SqlComparisonOperator {

    EQUAL("="),
    NOT_EQUAL("<>"),
    LESS_THAN("<"),
    LESS_OR_EQUAL("<="),
    GREATER_THAN(">"),
    GREATER_OR_EQUAL(">=");

    private final String symbol;

    SqlComparisonOperator(String symbol) {
        this.symbol = symbol;
    }

    /** The exact PostgreSQL operator symbol; a compiler-owned constant, never caller-controlled. */
    public String symbol() {
        return symbol;
    }
}