package com.company.campaign.production.audit;

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
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A strict scripted JDBC boundary for the durable audit writer.
 *
 * <p>It is deliberately <strong>not</strong> a database, an embedded engine, an H2 substitute or PostgreSQL
 * Scripted JDBC fixture used underneath a test MyBatis session factory and records every call that reaches it.
 * them in order, so the explicit transaction, the constant statements, the ordered typed bindings, the one JDBC
 * batch, the query timeout, the row bounds, the {@code on conflict do nothing} semantics and the exact
 * transaction rollback can be asserted deterministically without a network. Every assertion made through this
 * boundary is labelled non-database evidence.
 *
 * <p>It models the three properties of PostgreSQL the ledger's contract depends on:
 * <ul>
 *   <li>each connection owns its own uncommitted rows, so one transaction's rollback never discards another
 *       transaction's staged batch;</li>
 *   <li>the unique logical key is claimed at insert time, so a concurrent insert of the same key reports the same
 *       {@code 0} rows a real {@code on conflict do nothing} insert reports instead of creating a second row;</li>
 *   <li>a read inside an open transaction sees this connection's uncommitted row first, then the claim on the key,
 *       then the committed row.</li>
 * </ul>
 * A committed row is never overwritten and never removed: nothing here can model an update or a delete, because
 * the writer never issues one.
 */
final class ScriptedProductionAuditJdbcBoundary {

    static final String INSERT_MARKER = "insert into campaign_production_audit_ledger";

    static final String SELECT_MARKER = "select document_hash from campaign_production_audit_ledger";

    /** The column index of the immutable logical key in the one constant insert. */
    static final int LOGICAL_KEY_INDEX = 1;

    /** The column index of the canonical document hash in the one constant insert. */
    static final int DOCUMENT_HASH_INDEX = 15;

    /** The exact number of columns the one constant insert binds. */
    static final int COLUMN_COUNT = 16;

    private final List<String> calls = Collections.synchronizedList(new ArrayList<>());

    private final List<String> preparedSql = Collections.synchronizedList(new ArrayList<>());

    private final List<String> statements = Collections.synchronizedList(new ArrayList<>());

    /** The durable rows: logical key to canonical document hash. */
    private final Map<String, String> committed = new LinkedHashMap<>();

    /** The keys an open transaction has already inserted, so a concurrent insert of them reports zero rows. */
    private final Map<String, String> claimed = new LinkedHashMap<>();

    private final Set<Integer> boundColumnCounts = new LinkedHashSet<>();

    /** Every bound row, in batch order, as the exact ordered column values the writer bound. */
    private final List<List<Object>> boundRows = new ArrayList<>();

    private int insertRowCalls;
    private int executeBatchCalls;
    private int processedRows;
    private int failOnInsertRow = -1;
    private SQLException executeFailure;
    private boolean acquireFails;
    private boolean autoCommit = true;
    private boolean rollbackCalled;
    private boolean commitCalled;

    // ------------------------------------------------------------------ scripting

    /** Makes the one JDBC batch fail when it processes the given one-based row of the insert. */
    ScriptedProductionAuditJdbcBoundary failingOnRow(int row, SQLException failure) {
        this.failOnInsertRow = row;
        this.executeFailure = failure;
        return this;
    }

    ScriptedProductionAuditJdbcBoundary failingToConnect() {
        this.acquireFails = true;
        return this;
    }

    // ------------------------------------------------------------------ observations

    List<String> calls() {
        synchronized (calls) {
            return List.copyOf(calls);
        }
    }

    List<String> preparedSql() {
        synchronized (preparedSql) {
            return List.copyOf(preparedSql);
        }
    }

    List<String> statements() {
        synchronized (statements) {
            return List.copyOf(statements);
        }
    }

    Map<String, String> committedRows() {
        synchronized (this) {
            return Map.copyOf(committed);
        }
    }

    Set<Integer> boundColumnCounts() {
        synchronized (this) {
            return Set.copyOf(boundColumnCounts);
        }
    }

    /** Every bound row in batch order, as the exact ordered column values the writer bound. */
    List<List<Object>> boundRows() {
        synchronized (this) {
            List<List<Object>> copy = new ArrayList<>(boundRows.size());
            for (List<Object> row : boundRows) {
                copy.add(new ArrayList<>(row));
            }
            return List.copyOf(copy);
        }
    }

    /** The number of rows the writer has added to the one JDBC batch; each batched row counts once. */
    int insertRowCalls() {
        synchronized (this) {
            return insertRowCalls;
        }
    }

    /** The number of {@code executeBatch} calls the writer has made; the whole batch is one. */
    int executeBatchCalls() {
        synchronized (this) {
            return executeBatchCalls;
        }
    }

    boolean autoCommit() {
        synchronized (this) {
            return autoCommit;
        }
    }

    boolean rollbackCalled() {
        synchronized (this) {
            return rollbackCalled;
        }
    }

    boolean commitCalled() {
        synchronized (this) {
            return commitCalled;
        }
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
        Map<String, String> staged = new LinkedHashMap<>();
        return proxy(Connection.class, (proxy, method, args) -> switch (method.getName()) {
            case "setAutoCommit" -> {
                synchronized (this) {
                    autoCommit = (boolean) args[0];
                    calls.add("setAutoCommit(" + autoCommit + ")");
                }
                yield null;
            }
            case "getAutoCommit" -> autoCommit();
            case "prepareStatement" -> {
                String sql = (String) args[0];
                preparedSql.add(sql);
                calls.add("prepareStatement");
                yield statement(sql, staged);
            }
            case "commit" -> {
                synchronized (this) {
                    commitCalled = true;
                    calls.add("commit");
                    for (Map.Entry<String, String> row : staged.entrySet()) {
                        committed.put(row.getKey(), row.getValue());
                        claimed.remove(row.getKey());
                    }
                    staged.clear();
                }
                yield null;
            }
            case "rollback" -> {
                synchronized (this) {
                    rollbackCalled = true;
                    calls.add("rollback");
                    for (String key : staged.keySet()) {
                        claimed.remove(key);
                    }
                    staged.clear();
                }
                yield null;
            }
            case "close" -> {
                calls.add("connectionClose");
                yield null;
            }
            case "isClosed" -> false;
            default -> defaultAnswer(proxy, method, args);
        });
    }

    private PreparedStatement statement(String sql, Map<String, String> staged) {
        boolean insert = sql.startsWith(INSERT_MARKER);
        statements.add(insert ? "insert" : sql.startsWith(SELECT_MARKER) ? "select" : "unknown");
        Map<Integer, Object> current = new LinkedHashMap<>();
        List<Map<Integer, Object>> batched = new ArrayList<>();
        return proxy(PreparedStatement.class, (proxy, method, args) -> switch (method.getName()) {
            case "setQueryTimeout" -> {
                calls.add("setQueryTimeout(" + args[0] + ")");
                yield null;
            }
            case "setMaxRows" -> {
                calls.add("setMaxRows(" + args[0] + ")");
                yield null;
            }
            case "setString" -> {
                current.put((int) args[0], args[1]);
                yield null;
            }
            case "setNull" -> {
                current.put((int) args[0], null);
                yield null;
            }
            case "setTimestamp" -> {
                current.put((int) args[0], ((Timestamp) args[1]).toInstant().toString());
                yield null;
            }
            case "addBatch" -> {
                synchronized (this) {
                    insertRowCalls++;
                    calls.add("addBatch");
                    boundColumnCounts.add(current.size());
                    // The row is snapshotted so a later bind of the same statement cannot rewrite it.
                    batched.add(new LinkedHashMap<>(current));
                    boundRows.add(row(current));
                }
                yield null;
            }
            case "executeBatch" -> {
                synchronized (this) {
                    executeBatchCalls++;
                    calls.add("executeBatch");
                    yield executeBatch(batched, staged);
                }
            }
            case "executeQuery" -> {
                synchronized (this) {
                    calls.add("executeQuery");
                    yield resultSet(staged, (String) current.get(LOGICAL_KEY_INDEX));
                }
            }
            case "close" -> {
                calls.add("statementClose");
                yield null;
            }
            default -> defaultAnswer(proxy, method, args);
        });
    }

    /** The exact ordered column values one row bound, in column order, for ordered-bind assertions. */
    private static List<Object> row(Map<Integer, Object> current) {
        List<Object> values = new ArrayList<>(COLUMN_COUNT);
        for (int index = 1; index <= COLUMN_COUNT; index++) {
            values.add(current.get(index));
        }
        return Collections.unmodifiableList(values);
    }

    /**
     * The one {@code executeBatch}: it applies every batched row in order and returns the per-row affected count
     * exactly as the {@code on conflict (logical_key) do nothing} insert reports it.
     */
    private int[] executeBatch(List<Map<Integer, Object>> batched, Map<String, String> staged) throws SQLException {
        int[] counts = new int[batched.size()];
        for (int index = 0; index < batched.size(); index++) {
            processedRows++;
            if (executeFailure != null && processedRows == failOnInsertRow) {
                throw executeFailure;
            }
            counts[index] = append(batched.get(index), staged) ? 1 : 0;
        }
        return counts;
    }

    /** The {@code on conflict (logical_key) do nothing} semantics against this boundary's own state. */
    private boolean append(Map<Integer, Object> row, Map<String, String> staged) {
        String logicalKey = (String) row.get(LOGICAL_KEY_INDEX);
        if (logicalKey == null) {
            throw new IllegalStateException("the scripted boundary received no logical key");
        }
        if (committed.containsKey(logicalKey) || claimed.containsKey(logicalKey)) {
            return false;
        }
        String documentHash = (String) row.get(DOCUMENT_HASH_INDEX);
        staged.put(logicalKey, documentHash);
        claimed.put(logicalKey, documentHash);
        return true;
    }

    private ResultSet resultSet(Map<String, String> staged, String logicalKey) {
        String value = staged.containsKey(logicalKey)
                ? staged.get(logicalKey)
                : claimed.containsKey(logicalKey) ? claimed.get(logicalKey) : committed.get(logicalKey);
        Iterator<String> iterator = (value == null ? List.<String>of() : List.of(value)).iterator();
        String[] cursor = new String[1];
        return proxy(ResultSet.class, (proxy, method, args) -> switch (method.getName()) {
            case "next" -> {
                cursor[0] = iterator.hasNext() ? iterator.next() : null;
                yield cursor[0] != null;
            }
            case "getString" -> {
                if (cursor[0] == null || ((int) args[0]) != 1) {
                    yield null;
                }
                yield cursor[0];
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
            case "toString" -> "scripted-production-audit-jdbc-boundary";
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
