package com.company.campaign.readiness.postgres;

import com.company.campaign.persistence.CampaignDatasourceProperties;

import javax.sql.DataSource;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.SQLException;
import java.util.List;

/**
 * A minimal scripted JDBC boundary for the PostgreSQL readiness probe.
 *
 * <p>It is deliberately <strong>not</strong> an embedded database, an H2 substitute or evidence of any kind:
 * it answers exactly the two bounded statements the probe issues, so the probe's decision logic can be
 * asserted deterministically without a network. The only proof that a real PostgreSQL engine was exercised is
 * the environment-gated integration run; nothing here may be presented as PostgreSQL integration evidence.
 */
final class ScriptedProbeDatabase {

    private final List<String> appliedVersions;
    private final boolean scopeReadable;
    private final boolean failOnConnection;

    private ScriptedProbeDatabase(List<String> appliedVersions, boolean scopeReadable, boolean failOnConnection) {
        this.appliedVersions = appliedVersions;
        this.scopeReadable = scopeReadable;
        this.failOnConnection = failOnConnection;
    }

    /** A boundary whose Flyway history records the given versions and whose scope table is readable. */
    static ScriptedProbeDatabase withAppliedVersions(List<String> versions) {
        return new ScriptedProbeDatabase(versions, true, false);
    }

    /** A boundary whose Flyway history records nothing. */
    static ScriptedProbeDatabase withEmptyHistory() {
        return new ScriptedProbeDatabase(List.of(), true, false);
    }

    /** A boundary whose readiness scope table cannot be read. */
    static ScriptedProbeDatabase withUnreadableScope(List<String> versions) {
        return new ScriptedProbeDatabase(versions, false, false);
    }

    /** A boundary that cannot hand out a connection at all. */
    static ScriptedProbeDatabase unreachable() {
        return new ScriptedProbeDatabase(List.of(), false, true);
    }

    /** The scripted data source. */
    DataSource dataSource() {
        return (DataSource) proxy(DataSource.class, (proxy, method, args) -> {
            if ("getConnection".equals(method.getName())) {
                if (failOnConnection) {
                    throw new SQLException("scripted connection failure");
                }
                return connection();
            }
            return defaultAnswer(proxy, method, args);
        });
    }

    private Object connection() {
        return proxy(java.sql.Connection.class, (proxy, method, args) -> switch (method.getName()) {
            case "prepareStatement" -> statement((String) args[0]);
            case "setAutoCommit" -> null;
            case "isClosed" -> false;
            case "close" -> null;
            default -> defaultAnswer(proxy, method, args);
        });
    }

    private Object statement(String sql) {
        boolean historyQuery = sql.contains(CampaignDatasourceProperties.FLYWAY_HISTORY_TABLE);
        return proxy(java.sql.PreparedStatement.class, (proxy, method, args) -> switch (method.getName()) {
            case "executeQuery" -> historyQuery ? historyResultSet() : scopeQuery();
            case "setQueryTimeout", "setMaxRows", "close" -> null;
            default -> defaultAnswer(proxy, method, args);
        });
    }

    /** A readable scope answers the bounded existence query with no rows; an unreadable one fails the query. */
    private Object scopeQuery() throws SQLException {
        if (!scopeReadable) {
            throw new SQLException("scripted unreadable scope");
        }
        return scopeResultSet();
    }

    private Object historyResultSet() {
        int[] cursor = {-1};
        return proxy(java.sql.ResultSet.class, (proxy, method, args) -> switch (method.getName()) {
            case "next" -> ++cursor[0] < appliedVersions.size();
            case "getString" -> appliedVersions.get(cursor[0]);
            case "close", "setFetchSize" -> null;
            default -> defaultAnswer(proxy, method, args);
        });
    }

    private Object scopeResultSet() {
        return proxy(java.sql.ResultSet.class, (proxy, method, args) -> switch (method.getName()) {
            case "next" -> false;
            case "close" -> null;
            default -> defaultAnswer(proxy, method, args);
        });
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler);
    }

    private static Object defaultAnswer(Object proxy, Method method, Object[] args) {
        return switch (method.getName()) {
            case "toString" -> "scripted-probe-boundary";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            case "isWrapperFor" -> false;
            default -> null;
        };
    }
}