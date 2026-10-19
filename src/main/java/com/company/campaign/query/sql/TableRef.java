package com.company.campaign.query.sql;

/**
 * One validated, fully qualified PostgreSQL storage table.
 *
 * <p>Both the schema and the table are {@link SqlIdentifier} values, so neither can be an unvalidated string
 * and neither can carry a qualified or fragmentary form. The renderer always emits both, double-quoted, in
 * {@code "schema"."table"} order, so a search path can never silently resolve a different table.
 */
public record TableRef(SqlIdentifier schema, SqlIdentifier table) {

    public TableRef {
        if (schema == null || table == null) {
            throw new SqlException(SqlErrorCodes.SQL_CATALOG_INVALID, "tableRef",
                    "A storage table reference requires a schema and a table identifier.");
        }
    }

    /** The deterministic fully qualified PostgreSQL form. */
    public String render() {
        return schema.quoted() + "." + table.quoted();
    }

    @Override
    public String toString() {
        return schema.value() + "." + table.value();
    }
}