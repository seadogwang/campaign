package com.company.campaign.dev.campaignagent;

import com.company.campaign.builder.campaignagent.CampaignAgentSession;
import com.company.campaign.builder.campaignagent.CampaignConversationTurn;
import com.company.campaign.conversation.CampaignConversationBoundary;
import com.company.campaign.conversation.CampaignConversationCreateKey;
import com.company.campaign.conversation.CampaignConversationHistoryStore;
import com.company.campaign.conversation.CampaignConversationSnapshot;
import com.company.campaign.conversation.CampaignConversationSummary;
import com.company.campaign.conversation.CampaignConversationKey;
import com.company.campaign.conversation.CampaignConversationReservation;
import com.company.campaign.conversation.CampaignConversationStore;
import com.company.campaign.conversation.CampaignConversationTransaction;
import com.company.campaign.conversation.CampaignStoredOutcome;
import com.company.campaign.conversation.ConversationStoreErrorCodes;
import com.company.campaign.conversation.ConversationStoreException;
import com.company.campaign.shared.canonical.ContentHash;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The development-only in-memory Campaign conversation registry.
 *
 * <p><strong>This is not durable storage and not production conversation persistence.</strong> It holds every
 * session and every idempotency entry in process memory and loses all of it when the process stops. It exists so
 * the opt-in local development slice can run an end-to-end conversation without a database.
 *
 * <p>It is deliberately small and deterministic. It reads the injected {@link Clock} for every timestamp, uses
 * the explicit retention and eviction limits it is constructed with, and has no static map, no sleep, no random
 * identifier and no background thread. One turn is atomic per conversation key: the exclusive per-key lock is
 * held for the whole transaction, so two concurrent calls with the same expected revision cannot both advance
 * the session — the second sees the first's committed revision and fails closed as stale.
 *
 * <p>A conversation comes into existence only through {@link #reserve}. Opening a key that was never reserved
 * yields a transaction whose {@link CampaignConversationTransaction#reserved()} is false and whose session is
 * null, so a turn cannot implicitly create an unreserved conversation; a transaction that is closed without
 * committing stores neither a session nor an idempotency outcome.
 */
public final class InMemoryDevelopmentConversationStore
        implements CampaignConversationStore, CampaignConversationHistoryStore {

    private final Clock clock;
    private final int maxConversations;
    private final int maxOutcomesPerConversation;
    private final Duration retention;

    private final Map<CampaignConversationKey, Entry> entries = new HashMap<>();
    private final Map<CampaignConversationCreateKey, Creation> creations = new HashMap<>();
    private final ReentrantLock mapLock = new ReentrantLock();

    public InMemoryDevelopmentConversationStore(
            Clock clock, int maxConversations, int maxOutcomesPerConversation, Duration retention) {
        this.clock = Objects.requireNonNull(clock, "clock");
        if (maxConversations < 1 || maxOutcomesPerConversation < 1) {
            throw ConversationStoreException.invalid(
                    ConversationStoreErrorCodes.KEY_INVALID, "limits", "The registry bounds must be positive.");
        }
        if (retention == null || retention.isZero() || retention.isNegative()) {
            throw ConversationStoreException.invalid(
                    ConversationStoreErrorCodes.KEY_INVALID, "retention", "The retention window must be positive.");
        }
        this.maxConversations = maxConversations;
        this.maxOutcomesPerConversation = maxOutcomesPerConversation;
        this.retention = retention;
    }

    @Override
    public CampaignConversationTransaction open(CampaignConversationKey key) {
        Objects.requireNonNull(key, "key");
        Instant now = clock.instant();
        Entry entry;
        mapLock.lock();
        try {
            sweep(now);
            entry = entries.get(key);
            if (entry != null) {
                entry.references++;
                entry.lastTouched = now;
            }
        } finally {
            mapLock.unlock();
        }
        if (entry != null) {
            entry.lock.lock();
        }
        return new Transaction(key, entry);
    }

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
        mapLock.lock();
        try {
            sweep(now);
            Creation existing = creations.get(createKey);
            if (existing != null) {
                if (!existing.fingerprint().equals(requestFingerprint)) {
                    throw ConversationStoreException.invalid(
                            ConversationStoreErrorCodes.CREATE_CONFLICT, "fingerprint",
                            "The create Idempotency-Key was already used for a different request.");
                }
                return existing.reservation();
            }
            if (entries.containsKey(requestedKey)) {
                // An entry exists only when the identity already holds a reservation or stored state, so a
                // generated identity is never silently reused.
                throw ConversationStoreException.invalid(
                        ConversationStoreErrorCodes.IDENTITY_COLLISION, "identity",
                        "The generated conversation identity is already reserved.");
            }
            if (entries.size() >= maxConversations && !evictOldest(now)) {
                throw ConversationStoreException.invalid(
                        ConversationStoreErrorCodes.STORE_CAPACITY_EXCEEDED, "capacity",
                        "The conversation registry is at its bounded capacity.");
            }
            Entry entry = new Entry();
            entry.reserved = true;
            entry.createdAt = now;
            entry.lastTouched = now;
            entries.put(requestedKey, entry);
            CampaignConversationReservation reservation = new CampaignConversationReservation(
                    requestedKey.conversationId(), requestedKey.runId(), 0L, requestFingerprint);
            creations.put(createKey, new Creation(requestedKey, requestFingerprint, reservation, now));
            return reservation;
        } finally {
            mapLock.unlock();
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
        List<CampaignConversationSummary> summaries = new ArrayList<>();
        mapLock.lock();
        try {
            sweep(now);
            for (Map.Entry<CampaignConversationKey, Entry> candidate : entries.entrySet()) {
                CampaignConversationKey key = candidate.getKey();
                if (!key.tenantId().equals(boundary.tenantId())
                        || !key.programId().equals(boundary.programId())
                        || !key.userId().equals(boundary.userId())) {
                    continue;
                }
                Entry entry = candidate.getValue();
                CampaignAgentSession session = entry.session;
                summaries.add(new CampaignConversationSummary(
                        key.conversationId(), key.runId(),
                        session == null ? 0L : session.revision(),
                        session != null,
                        titleOf(session),
                        session == null ? 0 : session.turns().size(),
                        entry.createdAt, entry.lastTouched));
            }
        } finally {
            mapLock.unlock();
        }
        summaries.sort(Comparator
                .comparing(CampaignConversationSummary::updatedAt).reversed()
                .thenComparing(CampaignConversationSummary::conversationId)
                .thenComparing(CampaignConversationSummary::runId));
        return summaries.size() <= limit
                ? List.copyOf(summaries)
                : List.copyOf(summaries.subList(0, limit));
    }

    @Override
    public CampaignConversationSnapshot read(CampaignConversationKey key) {
        Objects.requireNonNull(key, "key");
        mapLock.lock();
        try {
            Entry entry = entries.get(key);
            return entry == null || entry.session == null
                    ? null
                    : CampaignConversationSnapshot.of(key, entry.session);
        } finally {
            mapLock.unlock();
        }
    }

    /** The first user-turn text of one stored session, or null when no user turn is stored yet. */
    private static String titleOf(CampaignAgentSession session) {
        if (session == null) {
            return null;
        }
        return session.turns().stream()
                .filter(turn -> CampaignConversationTurn.USER.equals(turn.role()))
                .map(CampaignConversationTurn::text)
                .filter(text -> text != null && !text.isBlank())
                .findFirst()
                .orElse(null);
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

    /** Removes every expired, unreferenced conversation, prunes outcomes and drops orphaned create ledgers. */
    private void sweep(Instant now) {
        Iterator<Map.Entry<CampaignConversationKey, Entry>> iterator = entries.entrySet().iterator();
        while (iterator.hasNext()) {
            Entry entry = iterator.next().getValue();
            if (entry.references > 0 || entry.lock.isLocked()) {
                continue;
            }
            if (now.isAfter(entry.lastTouched.plus(retention))) {
                iterator.remove();
                continue;
            }
            entry.pruneOutcomes(now, retention);
        }
        // A create ledger outlives nothing: once its conversation is gone, a replay must mint a fresh identity.
        creations.entrySet().removeIf(creation -> !entries.containsKey(creation.getValue().conversationKey()));
    }

    /** Evicts the least recently touched unreferenced conversation; false when none can be evicted. */
    private boolean evictOldest(Instant now) {
        CampaignConversationKey oldest = null;
        Instant oldestTouched = null;
        for (Map.Entry<CampaignConversationKey, Entry> candidate : entries.entrySet()) {
            Entry entry = candidate.getValue();
            if (entry.references > 0 || entry.lock.isLocked()) {
                continue;
            }
            if (oldestTouched == null || entry.lastTouched.isBefore(oldestTouched)) {
                oldest = candidate.getKey();
                oldestTouched = entry.lastTouched;
            }
        }
        if (oldest == null) {
            return false;
        }
        entries.remove(oldest);
        return true;
    }

    /** One stored create-idempotency ledger entry of one trusted boundary. */
    private record Creation(
            CampaignConversationKey conversationKey,
            ContentHash fingerprint,
            CampaignConversationReservation reservation,
            Instant createdAt
    ) {
    }

    /** The mutable state of one conversation, guarded by its own lock. */
    private static final class Entry {

        private final ReentrantLock lock = new ReentrantLock();
        private final Map<String, CampaignStoredOutcome> outcomes = new LinkedHashMap<>();
        private final Map<String, Instant> outcomeInstants = new LinkedHashMap<>();
        private volatile CampaignAgentSession session;
        private volatile Instant createdAt;
        private volatile Instant lastTouched;
        private boolean reserved;
        private int references;

        private void pruneOutcomes(Instant now, Duration retention) {
            List<String> expired = new ArrayList<>();
            for (Map.Entry<String, Instant> outcome : outcomeInstants.entrySet()) {
                if (now.isAfter(outcome.getValue().plus(retention))) {
                    expired.add(outcome.getKey());
                }
            }
            for (String key : expired) {
                outcomes.remove(key);
                outcomeInstants.remove(key);
            }
        }
    }

    /** The exclusive transaction of one key; it holds the key's lock until it is closed. */
    private final class Transaction implements CampaignConversationTransaction {

        private final CampaignConversationKey key;
        private Entry entry;
        private CampaignAgentSession working;
        private boolean loaded;
        private boolean closed;

        private Transaction(CampaignConversationKey key, Entry entry) {
            this.key = key;
            this.entry = entry;
        }

        @Override
        public CampaignAgentSession session() {
            if (!loaded) {
                working = entry == null || entry.session == null ? null : entry.session.copy();
                loaded = true;
            }
            return working;
        }

        @Override
        public boolean reserved() {
            return entry != null && entry.reserved;
        }

        @Override
        public CampaignStoredOutcome stored(String idempotencyKey) {
            return entry == null || idempotencyKey == null ? null : entry.outcomes.get(idempotencyKey);
        }

        @Override
        public void commit(
                CampaignAgentSession workingSession, String idempotencyKey, CampaignStoredOutcome outcome) {
            if (closed) {
                throw ConversationStoreException.invalid(
                        ConversationStoreErrorCodes.KEY_INVALID, "transaction",
                        "A closed conversation transaction cannot commit.");
            }
            if (entry == null && workingSession == null && (idempotencyKey == null || outcome == null)) {
                // Nothing to store at all: no session and no bounded outcome, so nothing is created.
                release();
                return;
            }
            if (entry == null) {
                entry = acquireEntry();
            }
            if (workingSession != null) {
                // A defensive copy is stored, so the caller's instance can never mutate stored state afterwards.
                entry.session = workingSession.copy();
            }
            if (idempotencyKey != null && outcome != null) {
                store(entry, idempotencyKey, outcome, clock.instant());
            }
            entry.lastTouched = clock.instant();
            release();
        }

        /** Materializes the key's entry under the map lock and takes its exclusive lock. */
        private Entry acquireEntry() {
            Entry created;
            mapLock.lock();
            try {
                created = entries.get(key);
                if (created == null) {
                    if (entries.size() >= maxConversations && !evictOldest(clock.instant())) {
                        throw ConversationStoreException.invalid(
                                ConversationStoreErrorCodes.STORE_CAPACITY_EXCEEDED, "capacity",
                                "The conversation registry is at its bounded capacity.");
                    }
                    created = new Entry();
                    created.createdAt = clock.instant();
                    entries.put(key, created);
                }
                created.references++;
            } finally {
                mapLock.unlock();
            }
            created.lock.lock();
            return created;
        }

        private void store(Entry target, String idempotencyKey, CampaignStoredOutcome outcome, Instant now) {
            if (!target.outcomes.containsKey(idempotencyKey)
                    && target.outcomes.size() >= maxOutcomesPerConversation) {
                String oldest = null;
                Instant oldestInstant = null;
                for (Map.Entry<String, Instant> candidate : target.outcomeInstants.entrySet()) {
                    if (oldestInstant == null || candidate.getValue().isBefore(oldestInstant)) {
                        oldest = candidate.getKey();
                        oldestInstant = candidate.getValue();
                    }
                }
                if (oldest != null) {
                    target.outcomes.remove(oldest);
                    target.outcomeInstants.remove(oldest);
                }
            }
            target.outcomes.put(idempotencyKey, outcome);
            target.outcomeInstants.put(idempotencyKey, now);
        }

        @Override
        public void abort() {
            release();
        }

        @Override
        public void close() {
            release();
        }

        private void release() {
            if (closed) {
                return;
            }
            closed = true;
            Entry target = entry;
            if (target == null) {
                return;
            }
            target.lastTouched = clock.instant();
            target.lock.unlock();
            mapLock.lock();
            try {
                target.references--;
            } finally {
                mapLock.unlock();
            }
        }
    }
}