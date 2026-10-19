package com.company.campaign.persistence;

import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedJdbcTypes;
import org.apache.ibatis.type.MappedTypes;

import java.sql.Array;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * MyBatis type handler that maps PostgreSQL {@code TEXT[]} columns to Java {@code String[]}.
 *
 * <p>This handler is bound automatically by MyBatis when a parameter or result column is declared as
 * {@code String[]}. It does not leak raw {@link SQLException} instances through to callers; every
 * database problem is rethrown as an {@link IllegalStateException} with a stable message.
 *
 * <p>Null values are handled at every level: a null array becomes a null parameter, and a null column
 * becomes a null result.
 */
@MappedTypes(String[].class)
@MappedJdbcTypes(JdbcType.ARRAY)
public final class PostgresTextArrayTypeHandler extends BaseTypeHandler<String[]> {

    private static final String TYPE_NAME = "text";

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, String[] parameter, JdbcType jdbcType)
            throws SQLException {
        Connection connection = ps.getConnection();
        Array array = connection.createArrayOf(TYPE_NAME, parameter);
        ps.setArray(i, array);
    }

    @Override
    public String[] getNullableResult(ResultSet rs, String columnName) throws SQLException {
        Array array = rs.getArray(columnName);
        if (array == null) {
            return null;
        }
        Object raw = array.getArray();
        if (raw == null) {
            return null;
        }
        return (String[]) raw;
    }

    @Override
    public String[] getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        Array array = rs.getArray(columnIndex);
        if (array == null) {
            return null;
        }
        Object raw = array.getArray();
        if (raw == null) {
            return null;
        }
        return (String[]) raw;
    }

    @Override
    public String[] getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        Array array = cs.getArray(columnIndex);
        if (array == null) {
            return null;
        }
        Object raw = array.getArray();
        if (raw == null) {
            return null;
        }
        return (String[]) raw;
    }
}