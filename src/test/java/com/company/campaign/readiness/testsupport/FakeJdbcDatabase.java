package com.company.campaign.readiness.testsupport;

import javax.sql.DataSource;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A legacy scripted JDBC boundary retained for mapper-boundary tests.
 *
 * <p>It is deliberately <strong>not</strong> an embedded database, an H2 substitute or evidence of any kind:
 * it answers a handful of statements with scripted rows or an empty result so the adapter's transaction,
 * timeout, row-bound and rollback behaviour can be asserted deterministically and without a network. No test
 * in this repository claims that a real PostgreSQL instance ran, and nothing here may be presented as
 * PostgreSQL integration evidence.
 */
public final class FakeJdbcDatabase {

    private final List<String> preparedStatements = new ArrayList<>();
    private final List<Integer> queryTimeouts = new ArrayList<>();
    private final List<Integer> maxRows = new ArrayList<>();
    private final Map<String, Object> singleRowByFragment = new LinkedHashMap<>();
    private final AtomicReference<String> failFragment = new AtomicReference<>();
    private final AtomicReference<Boolean> committed = new AtomicReference<>(false);
    private final AtomicReference<Boolean> rolledBack = new AtomicReference<>(false);
    private final AtomicReference<Boolean> autoCommitDisabled = new AtomicReference<>(false);

    /** Scripts a single-row answer for every query whose SQL contains the fragment. */
    public FakeJdbcDatabase withSingleRow(String sqlFragment, Object firstColumnValue) {
        singleRowByFragment.put(sqlFragment, firstColumnValue);
        return this;
    }

    /** Makes every statement whose SQL contains the fragment fail, to exercise rollback. */
    public FakeJdbcDatabase failingOn(String sqlFragment) {
        failFragment.set(sqlFragment);
        return this;
    }

    public List<String> preparedStatements() {
        return List.copyOf(preparedStatements);
    }

    public List<Integer> queryTimeouts() {
        return List.copyOf(queryTimeouts);
    }

    public List<Integer> maxRows() {
        return List.copyOf(maxRows);
    }

    public boolean committed() {
        return committed.get();
    }

    public boolean rolledBack() {
        return rolledBack.get();
    }

    public boolean autoCommitDisabled() {
        return autoCommitDisabled.get();
    }

    /** The scripted data source. */
    public DataSource dataSource() {
        return (DataSource) proxy(DataSource.class, "DataSource", (proxy, method, args) -> {
            if ("getConnection".equals(method.getName())) {
                return connection();
            }
            return defaultFor(method.getReturnType(), proxy, method, args);
        });
    }

    private Connection connection() {
        return (Connection) proxy(Connection.class, "Connection", (proxy, method, args) -> {
            switch (method.getName()) {
                case "setAutoCommit" -> {
                    autoCommitDisabled.set(!((Boolean) args[0]));
                    return null;
                }
                case "getAutoCommit" -> {
                    return !autoCommitDisabled.get();
                }
                case "prepareStatement" -> {
                    return statement((String) args[0]);
                }
                case "commit" -> {
                    committed.set(true);
                    return null;
                }
                case "rollback" -> {
                    rolledBack.set(true);
                    return null;
                }
                case "isClosed" -> {
                    return false;
                }
                default -> {
                    return defaultFor(method.getReturnType(), proxy, method, args);
                }
            }
        });
    }

    private PreparedStatement statement(String sql) {
        preparedStatements.add(sql);
        AtomicInteger rowCursor = new AtomicInteger();
        return (PreparedStatement) proxy(PreparedStatement.class, "PreparedStatement", (proxy, method, args) -> {
            switch (method.getName()) {
                case "setQueryTimeout" -> {
                    queryTimeouts.add((Integer) args[0]);
                    return null;
                }
                case "setMaxRows" -> {
                    maxRows.add((Integer) args[0]);
                    return null;
                }
                case "executeUpdate" -> {
                    failIfScripted(sql);
                    return 1;
                }
                case "executeQuery" -> {
                    failIfScripted(sql);
                    return resultSet(sql, rowCursor);
                }
                default -> {
                    return defaultFor(method.getReturnType(), proxy, method, args);
                }
            }
        });
    }

    private void failIfScripted(String sql) throws SQLException {
        String fragment = failFragment.get();
        if (fragment != null && sql.contains(fragment)) {
            throw new SQLException("scripted failure");
        }
    }

    private ResultSet resultSet(String sql, AtomicInteger rowCursor) {
        Object value = null;
        for (Map.Entry<String, Object> entry : singleRowByFragment.entrySet()) {
            if (sql.contains(entry.getKey())) {
                value = entry.getValue();
                break;
            }
        }
        Object rowValue = value;
        return (ResultSet) proxy(ResultSet.class, "ResultSet", (proxy, method, args) -> {
            switch (method.getName()) {
                case "next" -> {
                    boolean present = rowValue != null && rowCursor.getAndIncrement() == 0;
                    return present;
                }
                case "getLong" -> {
                    return rowValue instanceof Number number ? number.longValue() : 0L;
                }
                case "getString" -> {
                    return rowValue instanceof String text ? text : null;
                }
                case "getBoolean" -> {
                    return rowValue instanceof Boolean flag && flag;
                }
                case "getTimestamp" -> {
                    return rowValue instanceof Timestamp timestamp ? timestamp : null;
                }
                case "wasNull" -> {
                    return false;
                }
                case "isClosed" -> {
                    return false;
                }
                default -> {
                    return defaultFor(method.getReturnType(), proxy, method, args);
                }
            }
        });
    }

    private static Object proxy(Class<?> type, String name, InvocationHandler handler) {
        return Proxy.newProxyInstance(
                FakeJdbcDatabase.class.getClassLoader(),
                new Class<?>[] {type},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "toString" -> {
                            return "FakeJdbc[" + name + "]";
                        }
                        case "hashCode" -> {
                            return System.identityHashCode(proxy);
                        }
                        case "equals" -> {
                            return proxy == args[0];
                        }
                        default -> {
                            return handler.invoke(proxy, method, args);
                        }
                    }
                });
    }

    private static Object defaultFor(Class<?> returnType, Object proxy, Method method, Object[] args) {
        if (returnType == void.class) {
            return null;
        }
        if (!returnType.isPrimitive()) {
            return null;
        }
        if (returnType == boolean.class) {
            return false;
        }
        if (returnType == char.class) {
            return (char) 0;
        }
        if (returnType == int.class) {
            return 0;
        }
        if (returnType == long.class) {
            return 0L;
        }
        if (returnType == double.class) {
            return 0d;
        }
        if (returnType == float.class) {
            return 0f;
        }
        if (returnType == short.class) {
            return (short) 0;
        }
        return (byte) 0;
    }
}
