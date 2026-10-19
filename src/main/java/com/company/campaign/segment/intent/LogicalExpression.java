package com.company.campaign.segment.intent;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The closed logical-expression tree that structures the clauses.
 *
 * <p>Leaves reference clause ids that must exist; {@code ALL}/{@code ANY} preserve their declared grouping
 * rather than flattening into a precedence guess; {@code NOT} is unary by construction. The whole tree is
 * bounded in depth and node count, a clause id may be referenced exactly once, and every declared clause
 * must be referenced exactly once, so dangling and repeated references both fail closed.
 */
public sealed interface LogicalExpression
        permits LogicalExpression.Clause, LogicalExpression.Group, LogicalExpression.Negation {

    /** A leaf referencing one clause id. */
    record Clause(String clauseId) implements LogicalExpression {

        public Clause {
            clauseId = IntentTexts.requireReferenceId(clauseId, "logicalExpression.clauseId");
        }
    }

    /** A commutative group preserving the declared {@code ALL}/{@code ANY} structure. */
    record Group(LogicalOperator operator, List<LogicalExpression> children) implements LogicalExpression {

        public Group {
            if (operator == null || children == null || children.size() < 2) {
                throw IntentException.invalid(
                        IntentErrorCodes.EXPRESSION_WIDTH_INVALID,
                        "A logical group requires an operator and at least two children");
            }
            if (children.size() > IntentLimits.MAX_EXPRESSION_WIDTH) {
                throw new IntentException(
                        IntentErrorCodes.LIMIT_EXCEEDED, "A logical group exceeds the width limit");
            }
            List<LogicalExpression> copy = new ArrayList<>(children.size());
            for (LogicalExpression child : children) {
                if (child == null) {
                    throw IntentException.invalid(
                            IntentErrorCodes.EXPRESSION_INVALID, "A logical group child must not be null");
                }
                copy.add(child);
            }
            children = List.copyOf(copy);
        }
    }

    /** Unary negation with exactly one child. */
    record Negation(LogicalExpression child) implements LogicalExpression {

        public Negation {
            if (child == null) {
                throw IntentException.invalid(
                        IntentErrorCodes.EXPRESSION_NOT_ARITY,
                        "A unary NOT requires exactly one child");
            }
        }
    }

    /**
     * Validates the tree against the declared clause ids.
     *
     * @throws IntentException with a stable code when the tree is too deep, too large, references an unknown
     *                         clause, repeats a clause reference, or leaves a declared clause unreferenced
     */
    default void validateAgainst(Set<String> clauseIds) {
        Set<String> referenced = new LinkedHashSet<>();
        countNodes(this, 1, referenced, clauseIds);
        Set<String> unreferenced = new HashSet<>(clauseIds);
        unreferenced.removeAll(referenced);
        if (!unreferenced.isEmpty()) {
            throw IntentException.invalid(
                    IntentErrorCodes.EXPRESSION_UNREFERENCED_CLAUSE,
                    "A declared clause is not referenced by the logical expression");
        }
    }

    private static int countNodes(LogicalExpression node, int depth, Set<String> referenced, Set<String> clauseIds) {
        if (depth > IntentLimits.MAX_EXPRESSION_DEPTH) {
            throw new IntentException(
                    IntentErrorCodes.LIMIT_EXCEEDED, "The logical expression exceeds the depth limit");
        }
        if (node instanceof Clause clause) {
            if (!clauseIds.contains(clause.clauseId())) {
                throw IntentException.invalid(
                        IntentErrorCodes.EXPRESSION_DANGLING_REFERENCE,
                        "The logical expression references an undeclared clause");
            }
            if (!referenced.add(clause.clauseId())) {
                throw IntentException.invalid(
                        IntentErrorCodes.EXPRESSION_DUPLICATE_REFERENCE,
                        "The logical expression references one clause more than once");
            }
            return 1;
        }
        if (node instanceof Negation negation) {
            return 1 + countNodes(negation.child(), depth + 1, referenced, clauseIds);
        }
        Group group = (Group) node;
        int nodes = 1;
        for (LogicalExpression child : group.children()) {
            nodes += countNodes(child, depth + 1, referenced, clauseIds);
        }
        if (nodes > IntentLimits.MAX_EXPRESSION_NODES) {
            throw new IntentException(
                    IntentErrorCodes.LIMIT_EXCEEDED, "The logical expression exceeds the node limit");
        }
        return nodes;
    }
}