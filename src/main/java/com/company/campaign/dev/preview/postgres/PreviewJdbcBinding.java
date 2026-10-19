package com.company.campaign.dev.preview.postgres;

import com.company.campaign.query.sql.SqlParameter;
import com.company.campaign.query.sql.SqlParameterType;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * The one typed JDBC binding of a compiled artifact's ordered parameters.
 *
 * <p>Every {@link SqlParameterType} is bound with the JDBC call that matches it exactly. There is no
 * {@code setObject(value)} catch-all and no generic string fallback: a boolean is bound as a boolean, an
 * integer as a 64-bit integer, a decimal as an exact {@link BigDecimal}, a date as a {@link LocalDate} with the
 * SQL {@code DATE} type, an instant as an {@link OffsetDateTime} with the SQL {@code TIMESTAMP_WITH_TIMEZONE}
 * type, an enum machine code and an ISO-4217 currency as their declared object SQL types, and text and a
 * bounded context reference as text. A wrong binding therefore cannot silently coerce, and a test can inspect
 * every call.
 *
 * <p>Parameter values are already canonical by construction — {@link SqlParameter} refuses a non-canonical
 * value at creation — so a parse failure here can only come from a forged artifact, which the caller maps to
 * its one bounded backend failure.
 */
final class PreviewJdbcBinding {

    private PreviewJdbcBinding() {
    }

    /** Binds every parameter in the artifact's exact declared order, starting at index one. */
    static void bind(PreparedStatement statement, List<SqlParameter> parameters) throws SQLException {
        int index = 1;
        for (SqlParameter parameter : parameters) {
            bindOne(statement, index, parameter);
            index++;
        }
    }

    /** Binds exactly one parameter at exactly one one-based index with its matching JDBC call. */
    static void bindOne(PreparedStatement statement, int index, SqlParameter parameter) throws SQLException {
        switch (parameter.type()) {
            case BOOLEAN -> statement.setBoolean(index, Boolean.parseBoolean(parameter.canonicalValue()));
            case INTEGER -> statement.setLong(index, Long.parseLong(parameter.canonicalValue()));
            case DECIMAL -> statement.setBigDecimal(index, new BigDecimal(parameter.canonicalValue()));
            case TEXT -> statement.setString(index, parameter.canonicalValue());
            case ENUM -> statement.setObject(index, parameter.canonicalValue(), Types.OTHER);
            case DATE -> statement.setObject(
                    index, LocalDate.parse(parameter.canonicalValue()), Types.DATE);
            case TIMESTAMP -> statement.setObject(
                    index,
                    OffsetDateTime.ofInstant(Instant.parse(parameter.canonicalValue()), ZoneOffset.UTC),
                    Types.TIMESTAMP_WITH_TIMEZONE);
            case CURRENCY -> statement.setObject(index, parameter.canonicalValue(), Types.CHAR);
            case CONTEXT_ID -> statement.setString(index, parameter.canonicalValue());
        }
    }
}