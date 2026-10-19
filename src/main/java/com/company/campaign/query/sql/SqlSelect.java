package com.company.campaign.query.sql;

import com.company.campaign.metadata.domain.SortDirection;

import java.util.List;

/**
 * One immutable PostgreSQL {@code SELECT} with a single projected expression, one allowlisted table under one
 * compiler-owned alias and one boolean predicate.
 *
 * <p>There is deliberately no join, no ordering, no limit, no grouping and no set operation: every relation
 * boundary is expressed as a correlated subquery instead of a join, so a plan's {@code EXISTS} boundaries and
 * its {@code COUNT}-over-empty semantics can never be altered by join elimination or by an outer-join rewrite.
 * The projection is a single expression so the root selects its member key and every correlated subquery
 * selects exactly one value.
 */
public record SqlSelect(
        SqlExpression projection,
        TableRef from,
        SqlIdentifier alias,
        SqlExpression where,
        List<OrderBy> orderBy,
        Integer limit) {

    public SqlSelect {
        if (projection == null || from == null || alias == null || where == null) {
            throw new SqlException(SqlErrorCodes.SQL_AST_INVALID, "sqlSelect",
                    "A SQL SELECT requires a projection, a table, an alias and a predicate.");
        }
        orderBy = orderBy == null ? List.of() : List.copyOf(orderBy);
        if (orderBy.stream().anyMatch(java.util.Objects::isNull) || (limit != null && limit <= 0)) {
            throw new SqlException(SqlErrorCodes.SQL_AST_INVALID, "sqlSelectOrdering",
                    "A SQL SELECT requires valid ordering and a positive limit.");
        }
    }

    public SqlSelect(SqlExpression projection, TableRef from, SqlIdentifier alias, SqlExpression where) {
        this(projection, from, alias, where, List.of(), null);
    }

    public record OrderBy(SqlExpression expression, SortDirection direction) {
        public OrderBy {
            if (expression == null || direction == null) {
                throw new SqlException(SqlErrorCodes.SQL_AST_INVALID, "sqlOrderBy",
                        "A SQL ordering term requires an expression and direction.");
            }
        }
    }

    @Override
    public String toString() {
        return "SqlSelect[from=" + from + ", alias=" + alias + "]";
    }
}
