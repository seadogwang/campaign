package com.company.campaign.segment.intent;

/**
 * The two commutative group operators of the logical expression tree.
 *
 * <p>Unary negation is not a member: it is a separate {@link LogicalExpression.Negation} node whose
 * cardinality must be exactly one, so "NOT with two children" is structurally impossible to accept.
 */
public enum LogicalOperator {

    ALL,
    ANY
}