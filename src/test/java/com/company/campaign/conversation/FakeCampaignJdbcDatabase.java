package com.company.campaign.conversation;

import javax.sql.DataSource;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A minimal in-memory JDBC boundary for the durable Campaign conversation store.
 *
 * <p>It is deliberately <strong>not</strong> a database, an embedded engine, an H2 substitute or PostgreSQL
 * Legacy scripted JDBC fixture retained only for historical test support; production persistence uses MyBatis.
 * copy-on-write in-memory state, so the store's transaction, commit and cleanup logic can be asserted
 * deterministically without a network. Statements are recognised by their normalised text, and a statement the
 * fake does not know fails loudly rather than silently returning an empty result, so the fake cannot mask a
 * statement the store stopped issuing or started issuing differently.
 *
 * <p>An uncommitted change is visible to the connection that made it and to nobody else, and closing a
 * connection without committing discards it — which is what makes the store's "a transaction closed without
 * committing stores nothing" guarantee observable. Row locks are <em>not</em> emulated: serialization is proved
 * by the environment-gated real-PostgreSQL run, never here.
 */
final class FakeCampaignJdbcDatabase {

    private State state = new State();

    /** The scripted data source. */
    DataSource dataSource() {
        return (DataSource) proxy(DataSource.class, (proxy, method, args) -> {
            if ("getConnection".equals(method.getName())) {
                return connection();
            }
            return defaultAnswer(proxy, method, args);
        });
    }

    /** Every committed conversation row, for a test that wants to assert stored shape directly. */
    List<ConversationRow> committedConversations() {
        return List.copyOf(state.conversations.values());
    }

    /** Every committed outcome row. */
    List<OutcomeRow> committedOutcomes() {
        return List.copyOf(state.outcomes.values());
    }

    private Object connection() {
        ConnectionStage stage = new ConnectionStage(this);
        return proxy(java.sql.Connection.class, (proxy, method, args) -> switch (method.getName()) {
            case "prepareStatement" -> statement(stage, (String) args[0]);
            case "setAutoCommit", "setReadOnly", "setTransactionIsolation" -> null;
            case "isClosed" -> false;
            case "commit" -> {
                stage.commit();
                yield null;
            }
            case "rollback" -> {
                stage.rollback();
                yield null;
            }
            case "close" -> {
                stage.rollback();
                yield null;
            }
            default -> defaultAnswer(proxy, method, args);
        });
    }

    private Object statement(ConnectionStage stage, String sql) {
        String normalised = normalise(sql);
        List<Object> parameters = new ArrayList<>();
        return proxy(java.sql.PreparedStatement.class, (proxy, method, args) -> {
            switch (method.getName()) {
                case "setString":
                case "setLong":
                case "setInt":
                case "setBoolean":
                case "setBytes":
                case "setTimestamp":
                    parameters.add(args[1]);
                    return null;
                case "setNull":
                    parameters.add(null);
                    return null;
                case "setQueryTimeout":
                case "setMaxRows":
                case "setFetchSize":
                case "addBatch":
                case "clearBatch":
                case "close":
                    return null;
                case "executeQuery":
                    return resultSet(rows(stage, normalised, parameters));
                case "executeUpdate":
                    return update(stage, normalised, parameters);
                default:
                    return defaultAnswer(proxy, method, args);
            }
        });
    }

    // ------------------------------------------------------------------ statement dispatch

    private List<Object[]> rows(ConnectionStage stage, String sql, List<Object> params) throws SQLException {
        State state = stage.current();
        if (sql.startsWith("SELECT reserved, revision, session_format, session_hash, session_bytes")) {
            ConversationRow row = state.conversations.get(conversationKey(params, 0));
            if (row == null) {
                return List.of();
            }
            return single(row.reserved, row.revision, row.sessionFormat, row.sessionHash, row.sessionBytes);
        }
        if (sql.startsWith("SELECT reserved, session_hash FROM campaign_conversation")) {
            ConversationRow row = state.conversations.get(conversationKey(params, 0));
            if (row == null) {
                return List.of();
            }
            return single(row.reserved, row.sessionHash);
        }
        if (sql.startsWith("SELECT conversation_id FROM campaign_conversation")) {
            String tenant = (String) params.get(0);
            String program = (String) params.get(1);
            String user = (String) params.get(2);
            Timestamp now = (Timestamp) params.get(3);
            String excludedConversation = (String) params.get(4);
            String excludedRun = (String) params.get(5);
            int limit = (Integer) params.get(6);
            List<Object[]> found = new ArrayList<>();
            for (ConversationRow row : orderedConversations(state)) {
                if (!row.tenantId.equals(tenant) || !row.programId.equals(program)
                        || !row.userId.equals(user) || !row.expiresAt.after(now)) {
                    continue;
                }
                if (row.conversationId.equals(excludedConversation) && row.runId.equals(excludedRun)) {
                    continue;
                }
                found.add(new Object[] {row.conversationId});
                if (found.size() >= limit) {
                    break;
                }
            }
            return found;
        }
        if (sql.startsWith("SELECT fingerprint, conversation_id, run_id FROM campaign_conversation_create")) {
            LedgerRow row = state.ledgers.get(ledgerKey(params, 0));
            if (row == null) {
                return List.of();
            }
            return single(row.fingerprint, row.conversationId, row.runId);
        }
        if (sql.startsWith("SELECT fingerprint, result_hash, result_bytes")) {
            OutcomeRow row = state.outcomes.get(outcomeKey(params, 0, 5));
            if (row == null) {
                return List.of();
            }
            return single(row.fingerprint, row.resultHash, row.resultBytes);
        }
        if (sql.startsWith("SELECT idempotency_key FROM campaign_conversation_outcome")) {
            String prefix = boundaryPrefix(params, 0);
            int limit = (Integer) params.get(5);
            List<Object[]> found = new ArrayList<>();
            for (OutcomeRow row : orderedOutcomes(state)) {
                if (!row.key.startsWith(prefix)) {
                    continue;
                }
                found.add(new Object[] {row.idempotencyKey});
                if (found.size() >= limit) {
                    break;
                }
            }
            return found;
        }
        throw new SQLException("the scripted boundary does not know this read");
    }

    private int update(ConnectionStage stage, String sql, List<Object> params) throws SQLException {
        State state = stage.staged();
        if (sql.startsWith("INSERT INTO campaign_conversation (")) {
            String key = conversationKey(params, 0);
            if (state.conversations.containsKey(key)) {
                return 0;
            }
            ConversationRow row = new ConversationRow();
            row.tenantId = (String) params.get(0);
            row.programId = (String) params.get(1);
            row.userId = (String) params.get(2);
            row.conversationId = (String) params.get(3);
            row.runId = (String) params.get(4);
            row.reserved = false;
            row.revision = 0L;
            row.createdAt = (Timestamp) params.get(5);
            row.updatedAt = (Timestamp) params.get(6);
            row.expiresAt = (Timestamp) params.get(7);
            state.conversations.put(key, row);
            return 1;
        }
        if (sql.startsWith("UPDATE campaign_conversation SET reserved = TRUE")) {
            ConversationRow row = state.conversations.get(conversationKey(params, 3));
            if (row == null) {
                return 0;
            }
            row.reserved = true;
            row.revision = 0L;
            row.sessionFormat = null;
            row.sessionHash = null;
            row.sessionBytes = null;
            row.createdAt = (Timestamp) params.get(0);
            row.updatedAt = (Timestamp) params.get(1);
            row.expiresAt = (Timestamp) params.get(2);
            return 1;
        }
        if (sql.startsWith("UPDATE campaign_conversation SET revision = ?")) {
            ConversationRow row = state.conversations.get(conversationKey(params, 6));
            long expected = (Long) params.get(11);
            if (row == null || row.revision != expected) {
                return 0;
            }
            row.revision = (Long) params.get(0);
            row.sessionFormat = (String) params.get(1);
            row.sessionHash = (String) params.get(2);
            row.sessionBytes = (byte[]) params.get(3);
            row.updatedAt = (Timestamp) params.get(4);
            row.expiresAt = (Timestamp) params.get(5);
            return 1;
        }
        if (sql.startsWith("INSERT INTO campaign_conversation_create")) {
            LedgerRow row = new LedgerRow();
            row.key = ledgerKey(params, 0);
            row.tenantId = (String) params.get(0);
            row.programId = (String) params.get(1);
            row.userId = (String) params.get(2);
            row.createKey = (String) params.get(3);
            row.fingerprint = (String) params.get(4);
            row.conversationId = (String) params.get(5);
            row.runId = (String) params.get(6);
            row.createdAt = (Timestamp) params.get(7);
            row.expiresAt = (Timestamp) params.get(8);
            if (state.ledgers.containsKey(row.key)) {
                return 0;
            }
            state.ledgers.put(row.key, row);
            return 1;
        }
        if (sql.startsWith("INSERT INTO campaign_conversation_outcome")) {
            OutcomeRow row = new OutcomeRow();
            row.key = outcomeKey(params, 0, 5);
            row.tenantId = (String) params.get(0);
            row.programId = (String) params.get(1);
            row.userId = (String) params.get(2);
            row.conversationId = (String) params.get(3);
            row.runId = (String) params.get(4);
            row.idempotencyKey = (String) params.get(5);
            row.fingerprint = (String) params.get(6);
            row.resultHash = (String) params.get(7);
            row.resultBytes = (byte[]) params.get(8);
            row.createdAt = (Timestamp) params.get(9);
            row.expiresAt = (Timestamp) params.get(10);
            state.outcomes.put(row.key, row);
            return 1;
        }
        if (sql.startsWith("DELETE FROM campaign_conversation_outcome")) {
            return deleteOutcomes(state, sql, params);
        }
        if (sql.startsWith("DELETE FROM campaign_conversation WHERE")) {
            String tenant = (String) params.get(0);
            String program = (String) params.get(1);
            String user = (String) params.get(2);
            Timestamp now = (Timestamp) params.get(3);
            int limit = (Integer) params.get(4);
            int removed = 0;
            for (ConversationRow row : orderedConversations(state)) {
                if (removed >= limit) {
                    break;
                }
                if (row.tenantId.equals(tenant) && row.programId.equals(program)
                        && row.userId.equals(user) && !row.expiresAt.after(now)) {
                    state.conversations.remove(row.key());
                    removed++;
                }
            }
            return removed;
        }
        if (sql.startsWith("DELETE FROM campaign_conversation_create")) {
            String tenant = (String) params.get(0);
            String program = (String) params.get(1);
            String user = (String) params.get(2);
            Timestamp now = (Timestamp) params.get(3);
            int limit = (Integer) params.get(4);
            int removed = 0;
            for (LedgerRow row : orderedLedgers(state)) {
                if (removed >= limit) {
                    break;
                }
                if (!row.tenantId.equals(tenant) || !row.programId.equals(program)
                        || !row.userId.equals(user) || row.expiresAt.after(now)) {
                    continue;
                }
                boolean live = state.conversations.containsKey(
                        conversationKey(row.tenantId, row.programId, row.userId,
                                row.conversationId, row.runId));
                if (!live) {
                    state.ledgers.remove(row.key);
                    removed++;
                }
            }
            return removed;
        }
        throw new SQLException("the scripted boundary does not know this write");
    }

    private static int deleteOutcomes(State state, String sql, List<Object> params) {
        String prefix = boundaryPrefix(params, 0);
        if (sql.contains("expires_at <=")) {
            Timestamp now = (Timestamp) params.get(5);
            int limit = (Integer) params.get(6);
            int removed = 0;
            for (OutcomeRow row : orderedOutcomes(state)) {
                if (removed >= limit) {
                    break;
                }
                if (row.key.startsWith(prefix) && !row.expiresAt.after(now)) {
                    state.outcomes.remove(row.key);
                    removed++;
                }
            }
            return removed;
        }
        int limit = (Integer) params.get(5);
        int removed = 0;
        for (OutcomeRow row : orderedOutcomes(state)) {
            if (removed >= limit) {
                break;
            }
            if (row.key.startsWith(prefix)) {
                state.outcomes.remove(row.key);
                removed++;
            }
        }
        return removed;
    }

    private static List<ConversationRow> orderedConversations(State state) {
        List<ConversationRow> rows = new ArrayList<>(state.conversations.values());
        rows.sort((left, right) -> left.createdAt.compareTo(right.createdAt));
        return rows;
    }

    private static List<LedgerRow> orderedLedgers(State state) {
        List<LedgerRow> rows = new ArrayList<>(state.ledgers.values());
        rows.sort((left, right) -> left.createdAt.compareTo(right.createdAt));
        return rows;
    }

    private static List<OutcomeRow> orderedOutcomes(State state) {
        List<OutcomeRow> rows = new ArrayList<>(state.outcomes.values());
        rows.sort((left, right) -> left.createdAt.compareTo(right.createdAt));
        return rows;
    }

    // ------------------------------------------------------------------ keys and binding helpers

    private static String conversationKey(List<Object> params, int from) {
        return conversationKey((String) params.get(from), (String) params.get(from + 1),
                (String) params.get(from + 2), (String) params.get(from + 3), (String) params.get(from + 4));
    }

    private static String conversationKey(
            String tenantId, String programId, String userId, String conversationId, String runId) {
        return tenantId + '|' + programId + '|' + userId + '|' + conversationId + '|' + runId;
    }

    private static String ledgerKey(List<Object> params, int from) {
        return params.get(from) + "|" + params.get(from + 1) + "|" + params.get(from + 2)
                + '|' + params.get(from + 3);
    }

    private static String outcomeKey(List<Object> params, int from, int idempotencyIndex) {
        return params.get(from) + "|" + params.get(from + 1) + "|" + params.get(from + 2)
                + '|' + params.get(from + 3) + "|" + params.get(from + 4) + '|' + params.get(idempotencyIndex);
    }

    private static String boundaryPrefix(List<Object> params, int from) {
        return params.get(from) + "|" + params.get(from + 1) + "|" + params.get(from + 2) + '|'
                + params.get(from + 3) + '|' + params.get(from + 4) + '|';
    }

    private static String normalise(String sql) {
        return sql.replaceAll("\\s+", " ").strip();
    }

    /** One single-row result, without the {@code List.of(Object[])} varargs trap. */
    private static List<Object[]> single(Object... values) {
        List<Object[]> rows = new ArrayList<>(1);
        rows.add(values);
        return rows;
    }

    // ------------------------------------------------------------------ result sets

    private static ResultSet resultSet(List<Object[]> rows) {
        int[] cursor = {-1};
        return (ResultSet) proxy(ResultSet.class, (proxy, method, args) -> switch (method.getName()) {
            case "next" -> ++cursor[0] < rows.size();
            case "getString" -> text(rows.get(cursor[0]), (Integer) args[0]);
            case "getLong" -> {
                Object value = rows.get(cursor[0])[(Integer) args[0] - 1];
                yield value == null ? 0L : ((Number) value).longValue();
            }
            case "getBoolean" -> {
                Object value = rows.get(cursor[0])[(Integer) args[0] - 1];
                yield value != null && (Boolean) value;
            }
            case "getBytes" -> (byte[]) rows.get(cursor[0])[(Integer) args[0] - 1];
            case "getTimestamp" -> (Timestamp) rows.get(cursor[0])[(Integer) args[0] - 1];
            case "wasNull" -> false;
            case "close", "setFetchSize" -> null;
            default -> defaultAnswer(proxy, method, args);
        });
    }

    private static String text(Object[] row, int index) {
        Object value = row[index - 1];
        return value == null ? null : value.toString();
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler);
    }

    private static Object defaultAnswer(Object proxy, Method method, Object[] args) {
        return switch (method.getName()) {
            case "toString" -> "scripted-campaign-conversation-boundary";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            case "isWrapperFor" -> false;
            case "unwrap" -> null;
            default -> null;
        };
    }

    // ------------------------------------------------------------------ state

    /** One connection's uncommitted view of the boundary. */
    private static final class ConnectionStage {

        private final FakeCampaignJdbcDatabase database;
        private State staged;

        private ConnectionStage(FakeCampaignJdbcDatabase database) {
            this.database = database;
        }

        private State current() {
            return staged == null ? database.state : staged;
        }

        private State staged() {
            if (staged == null) {
                staged = database.state.copy();
            }
            return staged;
        }

        private void commit() {
            if (staged != null) {
                database.state = staged;
                staged = null;
            }
        }

        private void rollback() {
            staged = null;
        }
    }

    /** The committed boundary state. */
    private static final class State {

        private final Map<String, ConversationRow> conversations = new LinkedHashMap<>();
        private final Map<String, LedgerRow> ledgers = new LinkedHashMap<>();
        private final Map<String, OutcomeRow> outcomes = new LinkedHashMap<>();

        private State copy() {
            State copy = new State();
            for (Map.Entry<String, ConversationRow> entry : conversations.entrySet()) {
                copy.conversations.put(entry.getKey(), entry.getValue().copy());
            }
            for (Map.Entry<String, LedgerRow> entry : ledgers.entrySet()) {
                copy.ledgers.put(entry.getKey(), entry.getValue().copy());
            }
            for (Map.Entry<String, OutcomeRow> entry : outcomes.entrySet()) {
                copy.outcomes.put(entry.getKey(), entry.getValue().copy());
            }
            return copy;
        }
    }

    static final class ConversationRow {

        private String tenantId;
        private String programId;
        private String userId;
        private String conversationId;
        private String runId;
        private boolean reserved;
        private long revision;
        private String sessionFormat;
        private String sessionHash;
        private byte[] sessionBytes;
        private Timestamp createdAt;
        private Timestamp updatedAt;
        private Timestamp expiresAt;

        String key() {
            return conversationKey(tenantId, programId, userId, conversationId, runId);
        }

        boolean reserved() {
            return reserved;
        }

        long revision() {
            return revision;
        }

        String sessionFormat() {
            return sessionFormat;
        }

        String sessionHash() {
            return sessionHash;
        }

        byte[] sessionBytes() {
            return sessionBytes;
        }

        ConversationRow copy() {
            ConversationRow copy = new ConversationRow();
            copy.tenantId = tenantId;
            copy.programId = programId;
            copy.userId = userId;
            copy.conversationId = conversationId;
            copy.runId = runId;
            copy.reserved = reserved;
            copy.revision = revision;
            copy.sessionFormat = sessionFormat;
            copy.sessionHash = sessionHash;
            copy.sessionBytes = sessionBytes == null ? null : Arrays.copyOf(sessionBytes, sessionBytes.length);
            copy.createdAt = createdAt;
            copy.updatedAt = updatedAt;
            copy.expiresAt = expiresAt;
            return copy;
        }
    }

    static final class LedgerRow {

        private String key;
        private String tenantId;
        private String programId;
        private String userId;
        private String createKey;
        private String fingerprint;
        private String conversationId;
        private String runId;
        private Timestamp createdAt;
        private Timestamp expiresAt;

        LedgerRow copy() {
            LedgerRow copy = new LedgerRow();
            copy.key = key;
            copy.tenantId = tenantId;
            copy.programId = programId;
            copy.userId = userId;
            copy.createKey = createKey;
            copy.fingerprint = fingerprint;
            copy.conversationId = conversationId;
            copy.runId = runId;
            copy.createdAt = createdAt;
            copy.expiresAt = expiresAt;
            return copy;
        }
    }

    static final class OutcomeRow {

        private String key;
        private String tenantId;
        private String programId;
        private String userId;
        private String conversationId;
        private String runId;
        private String idempotencyKey;
        private String fingerprint;
        private String resultHash;
        private byte[] resultBytes;
        private Timestamp createdAt;
        private Timestamp expiresAt;

        String fingerprint() {
            return fingerprint;
        }

        String resultHash() {
            return resultHash;
        }

        byte[] resultBytes() {
            return resultBytes;
        }

        OutcomeRow copy() {
            OutcomeRow copy = new OutcomeRow();
            copy.key = key;
            copy.tenantId = tenantId;
            copy.programId = programId;
            copy.userId = userId;
            copy.conversationId = conversationId;
            copy.runId = runId;
            copy.idempotencyKey = idempotencyKey;
            copy.fingerprint = fingerprint;
            copy.resultHash = resultHash;
            copy.resultBytes = resultBytes == null ? null : Arrays.copyOf(resultBytes, resultBytes.length);
            copy.createdAt = createdAt;
            copy.expiresAt = expiresAt;
            return copy;
        }
    }

    /** A test-owned guard: nothing in this fake may be mistaken for a real database. */
    @Override
    public String toString() {
        return "FakeCampaignJdbcDatabase[scripted=true, engine=NONE]";
    }
}
