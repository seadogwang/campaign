package com.company.campaign.conversation;

import com.company.campaign.builder.campaignagent.CampaignAgentSession;
import com.company.campaign.builder.campaignagent.CampaignConversationTurn;
import com.company.campaign.persistence.PersistenceAdapters;
import com.company.campaign.persistence.entity.CampaignConversationEntity;
import com.company.campaign.shared.canonical.ContentHash;

import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * The durable PostgreSQL Campaign conversation store backed by MyBatis Plus.
 *
 * <p>It implements the same reservation, transaction, optimistic revision and idempotency contract as the
 * JDBC-backed store, but every database operation is issued through {@link SqlSession} and the MyBatis mapper
 * XML rather than through direct JDBC. The guarantees are the same:
 *
 * <ul>
 *   <li><strong>reservation is one atomic transaction</strong> &#8212; the create ledger and the reserved
 *       conversation row commit together, so a replay returns the reserved identity, a changed fingerprint
 *       fails closed as a bounded conflict and a colliding identity fails closed without overwriting
 *       anything;</li>
 *   <li><strong>{@code open} takes a real row lock</strong> &#8212; {@code SELECT ... FOR UPDATE} on
 *       the conversation row, held until the transaction commits or closes, so two stores on two sessions
 *       cannot both advance one conversation;</li>
 *   <li><strong>{@code commit} is an atomic compare-and-set</strong> &#8212; the session update carries
 *       {@code AND revision = <the revision read at open>}, so a write that would advance a revision
 *       another transaction already advanced updates zero rows and fails closed as stale;</li>
 *   <li><strong>every statement is bounded</strong> &#8212; bounded scans and a bounded, trusted-boundary-
 *       scoped batch on every cleanup delete;</li>
 *   <li><strong>nothing leaks</strong> &#8212; every persistence or codec failure becomes the one
 *       non-disclosing {@link ConversationStoreErrorCodes#STORE_UNAVAILABLE} code, and no URL, credential,
 *       trusted identifier, idempotency key or payload fragment is ever echoed.</li>
 * </ul>
 *
 * <p>Retention is explicit and clock-driven: {@code expires_at} is stamped from the injected {@link Clock},
 * and cleanup runs only inside {@link #reserve} and {@link #open}, bounded and scoped to one trusted
 * boundary. A conversation row and its idempotency outcomes are removed once {@code expires_at} has passed;
 * a create-ledger row is removed once it has expired <em>and</em> the conversation it reserved no longer
 * exists, so a replay can never return a reservation for a conversation that is already gone. No cleanup
 * thread is started.
 *
 * <p>It is constructed by the opt-in host configuration only. Nothing in this repository has executed it
 * against a real PostgreSQL instance in this session: the environment-gated integration test is the only
 * place that may claim a real database run.
 */
public final class MyBatisPlusCampaignConversationStore
        implements DurableCampaignConversationStore, CampaignConversationHistoryStore {

    /** The bounded server-side timeout of every statement this store issues. */
    public static final int JDBC_STATEMENT_TIMEOUT_SECONDS = 10;

    /** The largest number of expired rows one cleanup statement removes in one bounded batch. */
    public static final int CLEANUP_BATCH_LIMIT = 256;

    private static final String NS = "com.company.campaign.persistence.mapper.CampaignConversationMapper";

    private static final String STATEMENT_ENSURE_CONVERSATION = NS + ".ensureConversation";
    private static final String STATEMENT_LOCK_CONVERSATION = NS + ".lockConversation";
    private static final String STATEMENT_READ_CONVERSATION_FOR_RESERVE = NS + ".readConversationForReserve";
    private static final String STATEMENT_RESERVE_CONVERSATION = NS + ".reserveConversation";
    private static final String STATEMENT_TOUCH_CONVERSATION = NS + ".touchConversation";
    private static final String STATEMENT_COUNT_CONVERSATIONS = NS + ".countConversations";
    private static final String STATEMENT_LIST_CONVERSATIONS = NS + ".listConversations";
    private static final String STATEMENT_READ_CONVERSATION = NS + ".readConversation";
    private static final String STATEMENT_INSERT_CREATE_LEDGER = NS + ".insertCreateLedger";
    private static final String STATEMENT_READ_CREATE_LEDGER = NS + ".readCreateLedger";
    private static final String STATEMENT_READ_OUTCOME = NS + ".readOutcome";
    private static final String STATEMENT_COUNT_OUTCOMES = NS + ".countOutcomes";
    private static final String STATEMENT_WRITE_OUTCOME = NS + ".writeOutcome";
    private static final String STATEMENT_DELETE_OLDEST_OUTCOME = NS + ".deleteOldestOutcome";
    private static final String STATEMENT_SWEEP_CONVERSATIONS = NS + ".sweepConversations";
    private static final String STATEMENT_SWEEP_OUTCOMES = NS + ".sweepOutcomes";
    private static final String STATEMENT_SWEEP_CREATE_LEDGER = NS + ".sweepCreateLedger";

    private final SqlSessionFactory sqlSessionFactory;
    private final Clock clock;
    private static final CampaignConversationSnapshotCodec codec = new CampaignConversationSnapshotCodec();
    private final int maxConversations;
    private final int maxOutcomesPerConversation;
    private final Duration retention;

    public MyBatisPlusCampaignConversationStore(
            SqlSessionFactory sqlSessionFactory,
            Clock clock,
            int maxConversations,
            int maxOutcomesPerConversation,
            Duration retention) {
        this.sqlSessionFactory = Objects.requireNonNull(sqlSessionFactory, "sqlSessionFactory");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (maxConversations < 1 || maxOutcomesPerConversation < 1) {
            throw ConversationStoreException.invalid(
                    ConversationStoreErrorCodes.KEY_INVALID, "limits",
                    "The durable conversation store bounds must be positive.");
        }
        if (retention == null || retention.isZero() || retention.isNegative()) {
            throw ConversationStoreException.invalid(
                    ConversationStoreErrorCodes.RETENTION_INVALID, "retention",
                    "The durable conversation retention window must be positive.");
        }
        this.maxConversations = maxConversations;
        this.maxOutcomesPerConversation = maxOutcomesPerConversation;
        this.retention = retention;
    }

    // ------------------------------------------------------------------ open

    @Override
    public CampaignConversationTransaction open(CampaignConversationKey key) {
        Objects.requireNonNull(key, "key");
        SqlSession session = null;
        try {
            session = sqlSessionFactory.openSession(false);
            Instant now = clock.instant();
            sweepConversations(session, key, now);
            sweepOutcomes(session, key, now);
            ensureConversation(session, key, now, now.plus(retention));
            Loaded loaded = lockConversation(session, key);
            return new Transaction(key, session, loaded);
        } catch (ConversationStoreException failure) {
            rollbackQuietly(session);
            closeQuietly(session);
            throw failure;
        } catch (RuntimeException failure) {
            rollbackQuietly(session);
            closeQuietly(session);
            throw unavailable();
        }
    }

    // ------------------------------------------------------------------ reserve

    @Override
    public CampaignConversationReservation reserve(
            CampaignConversationCreateKey createKey,
            CampaignConversationKey requestedKey,
            ContentHash requestFingerprint) {
        Objects.requireNonNull(createKey, "createKey");
        Objects.requireNonNull(requestedKey, "requestedKey");
        Objects.requireNonNull(requestFingerprint, "requestFingerprint");
        requireSameBoundary(createKey, requestedKey);
        Instant now = clock.instant();
        Instant expiresAt = now.plus(retention);
        return inSession(session -> {
            sweepConversations(session, requestedKey, now);
            sweepOutcomes(session, requestedKey, now);
            sweepCreateLedger(session, createKey, now);
            int inserted = insertCreateLedger(
                    session, createKey, requestFingerprint, requestedKey, now, expiresAt);
            if (inserted == 0) {
                Ledger ledger = readCreateLedger(session, createKey);
                if (ledger == null) {
                    throw unavailable();
                }
                if (!ledger.fingerprint().equals(requestFingerprint)) {
                    throw ConversationStoreException.invalid(
                            ConversationStoreErrorCodes.CREATE_CONFLICT, "fingerprint",
                            "The create Idempotency-Key was already used for a different request.");
                }
                return new CampaignConversationReservation(
                        ledger.conversationId(), ledger.runId(), 0L, ledger.fingerprint());
            }
            ensureConversation(session, requestedKey, now, expiresAt);
            Reserved existing = readReserved(session, requestedKey);
            if (existing.reserved() || existing.hasSession()) {
                throw ConversationStoreException.invalid(
                        ConversationStoreErrorCodes.IDENTITY_COLLISION, "identity",
                        "The generated conversation identity is already reserved.");
            }
            if (countConversations(session, requestedKey, now, maxConversations + 1)
                    >= maxConversations) {
                throw ConversationStoreException.invalid(
                        ConversationStoreErrorCodes.STORE_CAPACITY_EXCEEDED, "capacity",
                        "The durable conversation store is at its bounded capacity.");
            }
            reserveConversation(session, requestedKey, now, expiresAt);
            return new CampaignConversationReservation(
                    requestedKey.conversationId(), requestedKey.runId(), 0L, requestFingerprint);
        });
    }

    private static void requireSameBoundary(
            CampaignConversationCreateKey createKey, CampaignConversationKey requestedKey) {
        if (!createKey.tenantId().equals(requestedKey.tenantId())
                || !createKey.programId().equals(requestedKey.programId())
                || !createKey.userId().equals(requestedKey.userId())) {
            throw ConversationStoreException.invalid(
                    ConversationStoreErrorCodes.RESERVATION_INVALID, "boundary",
                    "A reservation must share the trusted boundary of its create key.");
        }
    }

    // ------------------------------------------------------------------ history

    @Override
    public List<CampaignConversationSummary> list(CampaignConversationBoundary boundary, int limit) {
        Objects.requireNonNull(boundary, "boundary");
        if (limit < 1) {
            throw ConversationStoreException.invalid(
                    ConversationStoreErrorCodes.KEY_INVALID, "limit",
                    "A conversation history list requires a positive bound.");
        }
        Instant now = clock.instant();
        return inSession(session -> {
            Map<String, Object> params = new HashMap<>();
            params.put("tenantId", boundary.tenantId());
            params.put("programId", boundary.programId());
            params.put("userId", boundary.userId());
            params.put("now", now);
            params.put("limit", limit);
            List<CampaignConversationEntity> rows =
                    selectList(session, STATEMENT_LIST_CONVERSATIONS, params);
            if (rows.size() > limit) {
                throw ConversationStoreException.invalid(
                        ConversationStoreErrorCodes.STORE_UNAVAILABLE, "read",
                        "A durable conversation read exceeded its configured bound.");
            }
            List<CampaignConversationSummary> summaries = new ArrayList<>(rows.size());
            for (CampaignConversationEntity row : rows) {
                CampaignConversationSnapshot snapshot = readSnapshot(row);
                summaries.add(new CampaignConversationSummary(
                        row.getConversationId(),
                        row.getRunId(),
                        row.getRevision() == null ? 0L : row.getRevision(),
                        snapshot != null,
                        titleOf(snapshot),
                        turnCountOf(snapshot),
                        row.getCreatedAt(),
                        row.getUpdatedAt()));
            }
            return summaries;
        });
    }

    @Override
    public CampaignConversationSnapshot read(CampaignConversationKey key) {
        Objects.requireNonNull(key, "key");
        return inSession(session -> {
            CampaignConversationEntity entity =
                    selectOne(session, STATEMENT_READ_CONVERSATION, keyParams(key));
            return entity == null ? null : readSnapshot(entity);
        });
    }

    /** The first user-turn text of one stored snapshot, or null when no user turn is stored yet. */
    private static String titleOf(CampaignConversationSnapshot snapshot) {
        if (snapshot == null || snapshot.session() == null) {
            return null;
        }
        return snapshot.session().turns().stream()
                .filter(turn -> CampaignConversationTurn.USER.equals(turn.role()))
                .map(CampaignConversationTurn::text)
                .filter(text -> text != null && !text.isBlank())
                .findFirst()
                .orElse(null);
    }

    /** The accepted transcript turn count of one stored snapshot, or zero when none is stored. */
    private static int turnCountOf(CampaignConversationSnapshot snapshot) {
        return snapshot == null || snapshot.session() == null ? 0 : snapshot.session().turns().size();
    }

    // ------------------------------------------------------------------ bounded MyBatis work

    private <T> T inSession(Function<SqlSession, T> work) {
        SqlSession session = null;
        try {
            session = sqlSessionFactory.openSession(false);
            T result = work.apply(session);
            session.commit();
            return result;
        } catch (ConversationStoreException exception) {
            rollbackQuietly(session);
            throw exception;
        } catch (RuntimeException exception) {
            rollbackQuietly(session);
            throw unavailable();
        } finally {
            closeQuietly(session);
        }
    }

    private static void ensureConversation(
            SqlSession session, CampaignConversationKey key, Instant now, Instant expiresAt) {
        Map<String, Object> params = keyParams(key);
        params.put("createdAt", now);
        params.put("updatedAt", now);
        params.put("expiresAt", expiresAt);
        executeInsert(session, STATEMENT_ENSURE_CONVERSATION, params);
    }

    private static Loaded lockConversation(SqlSession session, CampaignConversationKey key) {
        CampaignConversationEntity entity =
                selectOne(session, STATEMENT_LOCK_CONVERSATION, keyParams(key));
        if (entity == null) {
            return Loaded.absent();
        }
        return new Loaded(
                true,
                entity.getReserved() != null && entity.getReserved(),
                entity.getRevision() == null ? 0L : entity.getRevision(),
                readSnapshot(entity));
    }

    private static Reserved readReserved(SqlSession session, CampaignConversationKey key) {
        Map<String, Object> row =
                selectOne(session, STATEMENT_READ_CONVERSATION_FOR_RESERVE, keyParams(key));
        if (row == null) {
            return new Reserved(false, false);
        }
        return new Reserved(
                Boolean.TRUE.equals(row.get("reserved")),
                row.get("session_hash") != null);
    }

    private static int countConversations(
            SqlSession session, CampaignConversationKey key, Instant now, int bound) {
        Map<String, Object> params = new HashMap<>();
        params.put("tenantId", key.tenantId());
        params.put("programId", key.programId());
        params.put("userId", key.userId());
        params.put("now", now);
        params.put("excludeConversationId", key.conversationId());
        params.put("excludeRunId", key.runId());
        params.put("limit", bound);
        return selectList(session, STATEMENT_COUNT_CONVERSATIONS, params).size();
    }

    private static Ledger readCreateLedger(
            SqlSession session, CampaignConversationCreateKey createKey) {
        Map<String, Object> params = new HashMap<>();
        params.put("tenantId", createKey.tenantId());
        params.put("programId", createKey.programId());
        params.put("userId", createKey.userId());
        params.put("createKey", createKey.idempotencyKey());
        Map<String, Object> row = selectOne(session, STATEMENT_READ_CREATE_LEDGER, params);
        if (row == null) {
            return null;
        }
        return new Ledger(
                storedFingerprint((String) row.get("fingerprint")),
                (String) row.get("conversation_id"),
                (String) row.get("run_id"));
    }

    private static int insertCreateLedger(
            SqlSession session,
            CampaignConversationCreateKey createKey,
            ContentHash requestFingerprint,
            CampaignConversationKey requestedKey,
            Instant now,
            Instant expiresAt) {
        Map<String, Object> params = new HashMap<>();
        params.put("tenantId", createKey.tenantId());
        params.put("programId", createKey.programId());
        params.put("userId", createKey.userId());
        params.put("createKey", createKey.idempotencyKey());
        params.put("fingerprint",
                PersistenceAdapters.contentHashValue(requestFingerprint));
        params.put("conversationId", requestedKey.conversationId());
        params.put("runId", requestedKey.runId());
        params.put("createdAt", now);
        params.put("expiresAt", expiresAt);
        return executeInsert(session, STATEMENT_INSERT_CREATE_LEDGER, params);
    }

    private static void reserveConversation(
            SqlSession session, CampaignConversationKey key, Instant now, Instant expiresAt) {
        Map<String, Object> params = keyParams(key);
        params.put("createdAt", now);
        params.put("updatedAt", now);
        params.put("expiresAt", expiresAt);
        executeUpdate(session, STATEMENT_RESERVE_CONVERSATION, params);
    }

    /** A stored fingerprint that is not a canonical content hash is one bounded, non-disclosing failure. */
    private static ContentHash storedFingerprint(String value) {
        try {
            return PersistenceAdapters.contentHashFromString(value);
        } catch (RuntimeException failure) {
            throw unavailable();
        }
    }

    private static CampaignConversationSnapshot readSnapshot(CampaignConversationEntity entity) {
        String format = entity.getSessionFormat();
        String hash = entity.getSessionHash();
        byte[] bytes = entity.getSessionBytes();
        if (format == null && hash == null && bytes == null) {
            return null;
        }
        try {
            if (format == null || hash == null || bytes == null
                    || !CampaignConversationSnapshotCodec.FORMAT_VERSION.equals(format)
                    || !new ContentHash(hash).matches(bytes)) {
                throw ConversationStoreException.invalid(
                        ConversationStoreErrorCodes.SNAPSHOT_INTEGRITY, "session",
                        "A stored conversation session contradicts its own hash or version.");
            }
            return codec.decode(bytes);
        } catch (ConversationStoreException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw ConversationStoreException.invalid(
                    ConversationStoreErrorCodes.SNAPSHOT_INVALID, "session",
                    "A stored conversation session is unreadable.");
        }
    }

    private static CampaignStoredOutcome readStoredOutcome(Map<String, Object> row) {
        String fingerprint = (String) row.get("fingerprint");
        String hash = (String) row.get("result_hash");
        byte[] bytes = (byte[]) row.get("result_bytes");
        try {
            if (fingerprint == null || hash == null || bytes == null
                    || !new ContentHash(hash).matches(bytes)) {
                throw ConversationStoreException.invalid(
                        ConversationStoreErrorCodes.OUTCOME_INVALID, "outcome",
                        "A stored conversation outcome contradicts its own hash.");
            }
            CampaignStoredOutcome outcome =
                    new CampaignConversationSnapshotCodec().decodeOutcome(bytes);
            if (!outcome.requestFingerprint().value().equals(fingerprint)) {
                throw ConversationStoreException.invalid(
                        ConversationStoreErrorCodes.OUTCOME_INVALID, "outcome",
                        "A stored conversation outcome contradicts its stored fingerprint.");
            }
            return outcome;
        } catch (ConversationStoreException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw ConversationStoreException.invalid(
                    ConversationStoreErrorCodes.OUTCOME_INVALID, "outcome",
                    "A stored conversation outcome is unreadable.");
        }
    }

    // ------------------------------------------------------------------ cleanup

    /** Removes the expired conversations of one trusted boundary, one bounded batch at a time. */
    private static void sweepConversations(
            SqlSession session, CampaignConversationKey key, Instant now) {
        Map<String, Object> params = boundaryParams(key.tenantId(), key.programId(), key.userId());
        params.put("sweepBefore", now);
        params.put("limit", CLEANUP_BATCH_LIMIT);
        executeDelete(session, STATEMENT_SWEEP_CONVERSATIONS, params);
    }

    private static void sweepOutcomes(
            SqlSession session, CampaignConversationKey key, Instant now) {
        Map<String, Object> params = keyParams(key);
        params.put("sweepBefore", now);
        params.put("limit", CLEANUP_BATCH_LIMIT);
        executeDelete(session, STATEMENT_SWEEP_OUTCOMES, params);
    }

    private static void sweepCreateLedger(
            SqlSession session, CampaignConversationCreateKey createKey, Instant now) {
        Map<String, Object> params = boundaryParams(
                createKey.tenantId(), createKey.programId(), createKey.userId());
        params.put("sweepBefore", now);
        params.put("limit", CLEANUP_BATCH_LIMIT);
        executeDelete(session, STATEMENT_SWEEP_CREATE_LEDGER, params);
    }

    // ------------------------------------------------------------------ statement helpers

    private static int executeInsert(
            SqlSession session, String statementId, Map<String, Object> params) {
        try {
            return session.insert(statementId, params);
        } catch (RuntimeException failure) {
            throw unavailable();
        }
    }

    private static int executeUpdate(
            SqlSession session, String statementId, Map<String, Object> params) {
        try {
            return session.update(statementId, params);
        } catch (RuntimeException failure) {
            throw unavailable();
        }
    }

    private static int executeDelete(
            SqlSession session, String statementId, Map<String, Object> params) {
        try {
            return session.delete(statementId, params);
        } catch (RuntimeException failure) {
            throw unavailable();
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T selectOne(
            SqlSession session, String statementId, Map<String, Object> params) {
        try {
            return (T) session.selectOne(statementId, params);
        } catch (RuntimeException failure) {
            throw unavailable();
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> List<T> selectList(
            SqlSession session, String statementId, Map<String, Object> params) {
        try {
            return (List<T>) session.selectList(statementId, params);
        } catch (RuntimeException failure) {
            throw unavailable();
        }
    }

    private static Map<String, Object> keyParams(CampaignConversationKey key) {
        Map<String, Object> params = new HashMap<>();
        params.put("tenantId", key.tenantId());
        params.put("programId", key.programId());
        params.put("userId", key.userId());
        params.put("conversationId", key.conversationId());
        params.put("runId", key.runId());
        return params;
    }

    private static Map<String, Object> boundaryParams(
            String tenantId, String programId, String userId) {
        Map<String, Object> params = new HashMap<>();
        params.put("tenantId", tenantId);
        params.put("programId", programId);
        params.put("userId", userId);
        return params;
    }

    private static void rollbackQuietly(SqlSession session) {
        if (session == null) {
            return;
        }
        try {
            session.rollback();
        } catch (RuntimeException ignored) {
            // The transaction is already unusable; the caller reports the original failure.
        }
    }

    private static void closeQuietly(SqlSession session) {
        if (session == null) {
            return;
        }
        try {
            session.close();
        } catch (RuntimeException ignored) {
            // A close failure cannot change the outcome the caller already observed.
        }
    }

    /** The one non-disclosing failure for every way the persistence boundary can fail. */
    private static ConversationStoreException unavailable() {
        return ConversationStoreException.invalid(
                ConversationStoreErrorCodes.STORE_UNAVAILABLE, "store",
                "The durable conversation boundary could not complete the transaction.");
    }

    private static ConversationStoreException stale() {
        return ConversationStoreException.invalid(
                ConversationStoreErrorCodes.STALE_REVISION, "revision",
                "The conversation revision was advanced by another transaction.");
    }

    /**
     * The accepted PostgreSQL durability claim of this adapter.
     *
     * <p>It describes the storage mechanism only &#8212; the committed Campaign conversation schema
     * accessed through bounded MyBatis statements &#8212; and is deliberately not evidence that a live
     * database was reached in any given session. A host that requires live readiness evidence must still
     * run the environment-gated integration proof; this claim can never substitute for it.
     */
    @Override
    public CampaignConversationStoreDurability durability() {
        return CampaignConversationStoreDurability.POSTGRESQL;
    }

    /** A bounded diagnostic: the bounds only, never a connection value. */
    @Override
    public String toString() {
        return "MyBatisPlusCampaignConversationStore[durable=true, maxConversations=" + maxConversations
                + ", maxOutcomesPerConversation=" + maxOutcomesPerConversation
                + ", retention=" + retention + ']';
    }

    /** The facts one transaction read from the locked conversation row. */
    private record Loaded(
            boolean present, boolean reserved, long revision, CampaignConversationSnapshot snapshot) {

        private static Loaded absent() {
            return new Loaded(false, false, -1L, null);
        }
    }

    /** The reservation-relevant facts of one conversation row. */
    private record Reserved(boolean reserved, boolean hasSession) {
    }

    /** One stored create-idempotency ledger row. */
    private record Ledger(ContentHash fingerprint, String conversationId, String runId) {
    }

    /** The exclusive transaction of one key; it holds the row lock until it is closed. */
    private final class Transaction implements CampaignConversationTransaction {

        private final CampaignConversationKey key;
        private final SqlSession session;
        private final Loaded loaded;
        private CampaignAgentSession working;
        private boolean materialized;
        private boolean closed;

        private Transaction(CampaignConversationKey key, SqlSession session, Loaded loaded) {
            this.key = key;
            this.session = session;
            this.loaded = loaded;
        }

        @Override
        public CampaignAgentSession session() {
            if (!materialized) {
                CampaignConversationSnapshot snapshot = loaded.snapshot();
                working = snapshot == null || snapshot.session() == null
                        ? null
                        : CampaignAgentSession.restore(snapshot.assemblyContext(), snapshot.session());
                materialized = true;
            }
            return working;
        }

        @Override
        public boolean reserved() {
            return loaded.reserved();
        }

        @Override
        public CampaignStoredOutcome stored(String idempotencyKey) {
            if (idempotencyKey == null || closed) {
                return null;
            }
            Map<String, Object> params = keyParams(key);
            params.put("idempotencyKey", idempotencyKey);
            Map<String, Object> row = selectOne(session, STATEMENT_READ_OUTCOME, params);
            return row == null ? null : readStoredOutcome(row);
        }

        @Override
        public void commit(
                CampaignAgentSession workingSession, String idempotencyKey,
                CampaignStoredOutcome outcome) {
            if (closed) {
                throw ConversationStoreException.invalid(
                        ConversationStoreErrorCodes.KEY_INVALID, "transaction",
                        "A closed conversation transaction cannot commit.");
            }
            if (workingSession == null && (idempotencyKey == null || outcome == null)) {
                release(false);
                return;
            }
            try {
                Instant now = clock.instant();
                Instant expiresAt = now.plus(retention);
                if (workingSession != null) {
                    if (workingSession.revision() < loaded.revision()) {
                        throw stale();
                    }
                    byte[] bytes = codec.encode(
                            CampaignConversationSnapshot.of(key, workingSession));
                    ContentHash hash = ContentHash.sha256(bytes);
                    Map<String, Object> params = new HashMap<>();
                    params.put("revision", workingSession.revision());
                    params.put("sessionFormat",
                            CampaignConversationSnapshotCodec.FORMAT_VERSION);
                    params.put("sessionHash",
                            PersistenceAdapters.contentHashValue(hash));
                    params.put("sessionBytes", bytes);
                    params.put("updatedAt", now);
                    params.put("expiresAt", expiresAt);
                    params.put("tenantId", key.tenantId());
                    params.put("programId", key.programId());
                    params.put("userId", key.userId());
                    params.put("conversationId", key.conversationId());
                    params.put("runId", key.runId());
                    params.put("expectedRevision", loaded.revision());
                    int updated = executeUpdate(session, STATEMENT_TOUCH_CONVERSATION, params);
                    if (updated == 0) {
                        throw stale();
                    }
                }
                if (idempotencyKey != null && outcome != null) {
                    storeOutcome(idempotencyKey, outcome, now, expiresAt);
                }
                session.commit();
                release(true);
            } catch (ConversationStoreException failure) {
                release(false);
                throw failure;
            } catch (RuntimeException failure) {
                release(false);
                throw unavailable();
            }
        }

        /** Writes one bounded idempotency outcome, evicting the oldest when the bound is reached. */
        private void storeOutcome(
                String idempotencyKey, CampaignStoredOutcome outcome, Instant now,
                Instant expiresAt) {
            Map<String, Object> readParams = keyParams(key);
            readParams.put("idempotencyKey", idempotencyKey);
            boolean present = selectOne(session, STATEMENT_READ_OUTCOME, readParams) != null;
            byte[] bytes = codec.encodeOutcome(outcome);
            if (!present) {
                evictOutcomeIfFull();
            }
            Map<String, Object> params = keyParams(key);
            params.put("idempotencyKey", idempotencyKey);
            params.put("fingerprint",
                        PersistenceAdapters.contentHashValue(outcome.requestFingerprint()));
            params.put("resultHash",
                    PersistenceAdapters.contentHashValue(ContentHash.sha256(bytes)));
            params.put("resultBytes", bytes);
            params.put("createdAt", now);
            params.put("expiresAt", expiresAt);
            executeInsert(session, STATEMENT_WRITE_OUTCOME, params);
        }

        private void evictOutcomeIfFull() {
            Map<String, Object> countParams = keyParams(key);
            countParams.put("limit", maxOutcomesPerConversation);
            int count =
                    selectList(session, STATEMENT_COUNT_OUTCOMES, countParams).size();
            if (count < maxOutcomesPerConversation) {
                return;
            }
            Map<String, Object> deleteParams = keyParams(key);
            deleteParams.put("limit", 1);
            int removed = executeDelete(session, STATEMENT_DELETE_OLDEST_OUTCOME, deleteParams);
            if (removed == 0) {
                throw ConversationStoreException.invalid(
                        ConversationStoreErrorCodes.OUTCOME_LIMIT_EXCEEDED, "outcome",
                        "The conversation reached its bounded idempotency outcome limit.");
            }
        }

        @Override
        public void abort() {
            release(false);
        }

        @Override
        public void close() {
            release(false);
        }

        private void release(boolean committed) {
            if (closed) {
                return;
            }
            closed = true;
            if (!committed) {
                rollbackQuietly(session);
            }
            closeQuietly(session);
        }
    }
}