package com.company.campaign.query.sql;

import java.util.regex.Pattern;

/**
 * One validated PostgreSQL schema/table/column/alias identifier.
 *
 * <p>It is the only way a physical name can enter the compiled SQL: the grammar is deliberately narrow —
 * lowercase snake_case, starting with a letter and at most 63 characters, which is PostgreSQL's own maximum
 * identifier length in bytes. Mixed case, uppercase, a leading digit, an embedded double quote, a comment
 * marker, a semicolon, whitespace and a qualified {@code schema.table} form are all rejected at construction,
 * so a caller can never smuggle a SQL fragment through a "name".
 *
 * <p>{@link #quoted()} renders the one deterministic double-quoted PostgreSQL form. Because the grammar
 * already excludes the double quote, quoting needs no escaping and always yields exactly one representation
 * for one identifier.
 */
public record SqlIdentifier(String value) {

    /** Lowercase snake_case with a bounded length; PostgreSQL truncates at 63 bytes, so this is the maximum. */
    private static final Pattern GRAMMAR = Pattern.compile("^[a-z][a-z0-9_]{0,62}$");

    public SqlIdentifier {
        if (value == null || !GRAMMAR.matcher(value).matches()) {
            throw new SqlException(SqlErrorCodes.SQL_IDENTIFIER_INVALID, "sqlIdentifier",
                    "A PostgreSQL schema, table, column or alias identifier must be lower-case snake_case "
                            + "within the bounded length.");
        }
    }

    /** Validates one identifier. */
    public static SqlIdentifier of(String value) {
        return new SqlIdentifier(value);
    }

    /** The deterministic PostgreSQL double-quoted form. */
    public String quoted() {
        return "\"" + value + "\"";
    }

    @Override
    public String toString() {
        return value;
    }
}