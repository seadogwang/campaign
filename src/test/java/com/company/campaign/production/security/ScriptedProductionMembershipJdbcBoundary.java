package com.company.campaign.production.security;

import javax.sql.DataSource;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A strict scripted JDBC boundary for the production membership adapter.
 *
 * <p>It is deliberately <strong>not</strong> a database, an embedded engine, an H2 substitute or PostgreSQL
 * Scripted JDBC fixture retained for mapper-boundary tests and records
 * every one of them in order, so the read-only connection, the query timeout, the maximum row bound, the exact
 * parameter bindings and the constant statements can be asserted deterministically without a network.
 */
final class ScriptedProductionMembershipJdbcBoundary {

    static final String PERMISSION_MARKER = "campaign_membership_permission";

    private final List<String> calls = new ArrayList<>();
    private final List<String> bindings = new ArrayList<>();
    private final List<String> preparedSql = new ArrayList<>();

    private List<Object[]> membershipRows = List.of();
    private List<Object[]> permissionRows = List.of();
    private SQLException executeFailure;
    private boolean acquireFails;

    // ------------------------------------------------------------------ scripting

    ScriptedProductionMembershipJdbcBoundary withMembershipRows(Object[]... rows) {
        this.membershipRows = new ArrayList<>(Arrays.asList(rows));
        return this;
    }

    ScriptedProductionMembershipJdbcBoundary withPermissionRows(String... permissions) {
        List<Object[]> rows = new ArrayList<>();
        for (String permission : permissions) {
            rows.add(new Object[] {permission});
        }
        this.permissionRows = rows;
        return this;
    }

    ScriptedProductionMembershipJdbcBoundary failingOnExecute(SQLException failure) {
        this.executeFailure = failure;
        return this;
    }

    ScriptedProductionMembershipJdbcBoundary failingToConnect() {
        this.acquireFails = true;
        return this;
    }

    // ------------------------------------------------------------------ observations

    List<String> calls() {
        return List.copyOf(calls);
    }

    List<String> bindings() {
        return List.copyOf(bindings);
    }

    List<String> preparedSql() {
        return List.copyOf(preparedSql);
    }

    // ------------------------------------------------------------------ proxies

    DataSource dataSource() {
        return proxy(DataSource.class, (proxy, method, args) -> {
            if ("getConnection".equals(method.getName())) {
                if (acquireFails) {
                    throw new SQLTimeoutException("The scripted boundary refused the connection.");
                }
                calls.add("getConnection");
                return connection();
            }
            return defaultAnswer(proxy, method, args);
        });
    }

    private Connection connection() {
        return proxy(Connection.class, (proxy, method, args) -> switch (method.getName()) {
            case "setReadOnly" -> {
                calls.add("setReadOnly(" + args[0] + ")");
                yield null;
            }
            case "prepareStatement" -> {
                String sql = (String) args[0];
                preparedSql.add(sql);
                calls.add("prepareStatement");
                yield statement(sql);
            }
            case "close" -> {
                calls.add("connectionClose");
                yield null;
            }
            case "isReadOnly" -> false;
            case "isClosed" -> false;
            default -> defaultAnswer(proxy, method, args);
        });
    }

    private PreparedStatement statement(String sql) {
        boolean permissionQuery = sql.contains(PERMISSION_MARKER);
        return proxy(PreparedStatement.class, (proxy, method, args) -> {
            switch (method.getName()) {
                case "setQueryTimeout" -> calls.add("setQueryTimeout(" + args[0] + ")");
                case "setMaxRows" -> calls.add("setMaxRows(" + args[0] + ")");
                case "setString" -> bindings.add("setString[" + args[0] + "]=" + args[1]);
                case "setNull" -> bindings.add("setNull[" + args[0] + "]=" + args[1]);
                case "executeQuery" -> {
                    calls.add("executeQuery");
                    if (executeFailure != null) {
                        throw executeFailure;
                    }
                    return resultSet(permissionQuery ? permissionRows : membershipRows);
                }
                case "close" -> calls.add("statementClose");
                default -> {
                    return defaultAnswer(proxy, method, args);
                }
            }
            return null;
        });
    }

    private ResultSet resultSet(List<Object[]> rows) {
        java.util.Iterator<Object[]> iterator = rows.iterator();
        Object[][] cursor = new Object[1][];
        return proxy(ResultSet.class, (proxy, method, args) -> switch (method.getName()) {
            case "next" -> {
                cursor[0] = iterator.hasNext() ? iterator.next() : null;
                yield cursor[0] != null;
            }
            case "getString" -> {
                Object[] row = cursor[0];
                int column = (int) args[0];
                yield row == null || column > row.length || !(row[column - 1] instanceof String value)
                        ? null
                        : value;
            }
            case "getTimestamp" -> {
                Object[] row = cursor[0];
                int column = (int) args[0];
                yield row == null || column > row.length || !(row[column - 1] instanceof Timestamp value)
                        ? null
                        : value;
            }
            case "close" -> {
                calls.add("resultSetClose");
                yield null;
            }
            default -> defaultAnswer(proxy, method, args);
        });
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler);
    }

    private static Object defaultAnswer(Object proxy, Method method, Object[] args) {
        return switch (method.getName()) {
            case "toString" -> "scripted-production-membership-jdbc-boundary";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            case "isWrapperFor" -> false;
            case "unwrap" -> null;
            default -> defaultValue(method.getReturnType());
        };
    }

    private static Object defaultValue(Class<?> returnType) {
        if (returnType == boolean.class) {
            return false;
        }
        if (returnType == int.class) {
            return 0;
        }
        if (returnType == long.class) {
            return 0L;
        }
        if (returnType == short.class) {
            return (short) 0;
        }
        if (returnType == byte.class) {
            return (byte) 0;
        }
        if (returnType == char.class) {
            return (char) 0;
        }
        if (returnType == float.class) {
            return 0f;
        }
        if (returnType == double.class) {
            return 0d;
        }
        return null;
    }
}
