package com.company.campaign.dev.preview.postgres;

import javax.sql.DataSource;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.util.ArrayList;
import java.util.List;

/**
 * A strict scripted JDBC boundary for the PostgreSQL Preview adapter.
 *
 * <p>It is deliberately <strong>not</strong> a database, an embedded engine, an H2 substitute or PostgreSQL
 * evidence: it answers exactly the bounded calls {@link PostgresPreviewExecutionBackend} makes and records every
 * one of them in order, so the read-only transaction, the timeout/fetch/max-row settings, the exact parameter
 * bindings, the rollback and the close can be asserted deterministically without a network.
 *
 * <p>An unconfigured call still succeeds with a neutral answer instead of failing, so the recorded order — not
 * the fake's tolerance — is what the tests assert. A configured failure is raised at exactly the point a real
 * driver would raise it, so the adapter's failure mapping is observable.
 */
final class ScriptedPreviewJdbcBoundary {

    private final List<String> calls = new ArrayList<>();
    private final List<String> bindings = new ArrayList<>();

    private List<String> rows = List.of();
    private String columnLabel = "member_id";
    private int columnCount = 1;
    private boolean readOnlyReported = true;
    private String productName = "PostgreSQL";
    private int majorVersion = 15;
    private boolean acquireFails;
    private SQLException executeFailure;
    private SQLException setReadOnlyFailure;

    private boolean rolledBack;
    private boolean connectionClosed;
    private boolean statementClosed;
    private boolean resultSetClosed;
    private String preparedSql;

    // ------------------------------------------------------------------ scripting

    ScriptedPreviewJdbcBoundary withRows(String... memberIds) {
        // Arrays.asList is used deliberately: a scripted null row must be representable, and List.of rejects it.
        this.rows = java.util.Arrays.asList(memberIds.clone());
        return this;
    }

    ScriptedPreviewJdbcBoundary withColumnLabel(String label) {
        this.columnLabel = label;
        return this;
    }

    ScriptedPreviewJdbcBoundary withColumnCount(int count) {
        this.columnCount = count;
        return this;
    }

    ScriptedPreviewJdbcBoundary withReadOnlyReported(boolean reported) {
        this.readOnlyReported = reported;
        return this;
    }

    ScriptedPreviewJdbcBoundary withDatabase(String product, int majorVersion) {
        this.productName = product;
        this.majorVersion = majorVersion;
        return this;
    }

    ScriptedPreviewJdbcBoundary failingToConnect() {
        this.acquireFails = true;
        return this;
    }

    ScriptedPreviewJdbcBoundary failingOnExecute(SQLException failure) {
        this.executeFailure = failure;
        return this;
    }

    /**
     * Makes the scripted connection refuse its read-only switch, exactly as a real PostgreSQL connection does
     * while a transaction is active (SQLSTATE {@code 25001}), so the adapter's handling of that refusal is
     * observable.
     */
    ScriptedPreviewJdbcBoundary failingToSetReadOnly(SQLException failure) {
        this.setReadOnlyFailure = failure;
        return this;
    }

    // ------------------------------------------------------------------ observations

    List<String> calls() {
        return List.copyOf(calls);
    }

    List<String> bindings() {
        return List.copyOf(bindings);
    }

    String preparedSql() {
        return preparedSql;
    }

    boolean rolledBack() {
        return rolledBack;
    }

    boolean connectionClosed() {
        return connectionClosed;
    }

    boolean statementClosed() {
        return statementClosed;
    }

    boolean resultSetClosed() {
        return resultSetClosed;
    }

    // ------------------------------------------------------------------ proxies

    /** A prepared statement over this boundary, for binding-only assertions. */
    PreparedStatement preparedStatement() {
        return statement();
    }

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
            case "setAutoCommit" -> {
                calls.add("setAutoCommit(" + args[0] + ")");
                yield null;
            }
            case "setReadOnly" -> {
                calls.add("setReadOnly(" + args[0] + ")");
                if (setReadOnlyFailure != null) {
                    throw setReadOnlyFailure;
                }
                yield null;
            }
            case "setTransactionIsolation" -> {
                calls.add("setTransactionIsolation(" + args[0] + ")");
                yield null;
            }
            case "isReadOnly" -> {
                calls.add("isReadOnly");
                yield readOnlyReported;
            }
            case "getMetaData" -> {
                calls.add("getMetaData");
                yield databaseMetaData();
            }
            case "prepareStatement" -> {
                preparedSql = (String) args[0];
                calls.add("prepareStatement");
                yield statement();
            }
            case "rollback" -> {
                calls.add("rollback");
                rolledBack = true;
                yield null;
            }
            case "close" -> {
                calls.add("close");
                connectionClosed = true;
                yield null;
            }
            case "isClosed" -> false;
            case "getAutoCommit" -> false;
            default -> defaultAnswer(proxy, method, args);
        });
    }

    private java.sql.DatabaseMetaData databaseMetaData() {
        return proxy(java.sql.DatabaseMetaData.class, (proxy, method, args) -> switch (method.getName()) {
            case "getDatabaseProductName" -> productName;
            case "getDatabaseMajorVersion" -> majorVersion;
            default -> defaultAnswer(proxy, method, args);
        });
    }

    private PreparedStatement statement() {
        return proxy(PreparedStatement.class, (proxy, method, args) -> {
            switch (method.getName()) {
                case "setQueryTimeout" -> calls.add("setQueryTimeout(" + args[0] + ")");
                case "setFetchSize" -> calls.add("setFetchSize(" + args[0] + ")");
                case "setMaxRows" -> calls.add("setMaxRows(" + args[0] + ")");
                case "executeQuery" -> {
                    calls.add("executeQuery");
                    if (executeFailure != null) {
                        throw executeFailure;
                    }
                    return resultSet();
                }
                case "close" -> {
                    calls.add("statementClose");
                    statementClosed = true;
                }
                case "setBoolean" -> bindings.add("setBoolean[" + args[0] + "]=" + args[1]);
                case "setLong" -> bindings.add("setLong[" + args[0] + "]=" + args[1]);
                case "setBigDecimal" -> bindings.add("setBigDecimal[" + args[0] + "]=" + args[1]);
                case "setString" -> bindings.add("setString[" + args[0] + "]=" + args[1]);
                case "setObject" -> bindings.add(args.length == 3
                        ? "setObject[" + args[0] + "]=" + args[1] + "@" + sqlTypeName((Integer) args[2])
                        : "setObject[" + args[0] + "]=" + args[1]);
                default -> {
                    return defaultAnswer(proxy, method, args);
                }
            }
            return null;
        });
    }

    private ResultSet resultSet() {
        java.util.Iterator<String> iterator = rows.iterator();
        return proxy(ResultSet.class, (proxy, method, args) -> switch (method.getName()) {
            case "getMetaData" -> resultSetMetaData();
            case "next" -> iterator.hasNext();
            case "getString" -> iterator.hasNext() ? iterator.next() : null;
            case "close" -> {
                calls.add("resultSetClose");
                resultSetClosed = true;
                yield null;
            }
            case "wasNull" -> false;
            default -> defaultAnswer(proxy, method, args);
        });
    }

    private java.sql.ResultSetMetaData resultSetMetaData() {
        return proxy(java.sql.ResultSetMetaData.class, (proxy, method, args) -> switch (method.getName()) {
            case "getColumnCount" -> columnCount;
            case "getColumnLabel", "getColumnName" -> columnLabel;
            default -> defaultAnswer(proxy, method, args);
        });
    }

    static String sqlTypeName(int sqlType) {
        return switch (sqlType) {
            case java.sql.Types.OTHER -> "OTHER";
            case java.sql.Types.DATE -> "DATE";
            case java.sql.Types.TIMESTAMP_WITH_TIMEZONE -> "TIMESTAMP_WITH_TIMEZONE";
            case java.sql.Types.CHAR -> "CHAR";
            case java.sql.Types.VARCHAR -> "VARCHAR";
            default -> "TYPE_" + sqlType;
        };
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler);
    }

    private static Object defaultAnswer(Object proxy, Method method, Object[] args) {
        return switch (method.getName()) {
            case "toString" -> "scripted-preview-jdbc-boundary";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            case "isWrapperFor" -> false;
            case "unwrap" -> null;
            default -> defaultValue(method.getReturnType());
        };
    }

    /** A neutral value for an unscripted primitive-returning method, so a proxy never NPEs on widening. */
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