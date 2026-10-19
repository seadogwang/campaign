package com.company.campaign.query.sql;

/**
 * The closed set of typed bind-parameter kinds a compiled artifact can carry.
 *
 * <p>Every value that reaches SQL is one of these — a boolean, an integer, an exact decimal, text, an enum
 * machine code, a local date, an instant, an ISO-4217 currency or a bounded context reference identifier.
 * Nothing here names a SQL type: a later JDBC adapter owns the physical mapping, while this task only has to
 * guarantee that the marker is deterministic and the value is exact and canonical.
 */
public enum SqlParameterType {

    BOOLEAN,
    INTEGER,
    DECIMAL,
    TEXT,
    ENUM,
    DATE,
    TIMESTAMP,
    CURRENCY,
    CONTEXT_ID
}