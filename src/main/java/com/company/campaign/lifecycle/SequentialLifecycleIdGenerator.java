package com.company.campaign.lifecycle;

import com.company.campaign.contracts.ContractPatterns;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Deterministic, thread-safe {@link LifecycleIdGenerator} for tests and local use.
 *
 * <p>Each kind has its own counter, so identities never collide across kinds and a fresh instance always
 * produces the same sequence. It is deliberately not a production identity source: a real host injects an
 * allocation adapter backed by its own durable identity store.
 */
public final class SequentialLifecycleIdGenerator implements LifecycleIdGenerator {

    private static final Pattern IDENTIFIER = Pattern.compile(ContractPatterns.REFERENCE_ID);

    private final Map<LifecycleIdKind, Long> counters = new EnumMap<>(LifecycleIdKind.class);
    private final long start;

    /** Creates a generator whose first identity of every kind is {@code 1}. */
    public SequentialLifecycleIdGenerator() {
        this(1L);
    }

    /** Creates a generator whose first identity of every kind is {@code start}. */
    public SequentialLifecycleIdGenerator(long start) {
        if (start < 1L) {
            throw new IllegalArgumentException("start must be positive");
        }
        this.start = start;
        for (LifecycleIdKind kind : LifecycleIdKind.values()) {
            counters.put(kind, start);
        }
    }

    @Override
    public synchronized String nextId(LifecycleIdKind kind) {
        Objects.requireNonNull(kind, "kind");
        long next = counters.get(kind);
        counters.put(kind, next + 1L);
        String id = kind.prefix() + '-' + String.format("%012d", next);
        if (!IDENTIFIER.matcher(id).matches()) {
            throw new IllegalStateException("generated identifier is outside the shared grammar");
        }
        return id;
    }

    /** The next value this generator would hand out for a kind, without consuming it. */
    public synchronized long peek(LifecycleIdKind kind) {
        return counters.get(Objects.requireNonNull(kind, "kind"));
    }

    /** The configured first identity value. */
    public long start() {
        return start;
    }
}