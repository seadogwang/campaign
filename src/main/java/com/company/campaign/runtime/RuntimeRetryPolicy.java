package com.company.campaign.runtime;

import com.company.campaign.shared.canonical.CanonicalJson;
import com.company.campaign.shared.canonical.ContentHash;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * The bounded, persisted technical retry policy one Runtime worker applies.
 *
 * <p>It fixes how many <em>actual</em> attempts a task may consume and the exact delay before each attempt after
 * the first, so a retry sequence is a bounded deterministic fact rather than a loop. The default test policy is
 * three actual attempts with delays of five seconds then thirty seconds; nothing here derives a delay from a
 * wall clock, a random source or a caller.
 *
 * <p>The policy covers bounded technical dependency failures only. A business outcome and a frozen
 * configuration defect are never retried, so they never consume it.
 */
public record RuntimeRetryPolicy(
        int maxAttempts,
        List<Duration> backoff,
        ContentHash policyHash
) {

    /** The largest number of actual attempts any policy may declare. */
    public static final int MAX_ATTEMPTS = 8;

    private static final CanonicalJson CANONICAL_JSON = new CanonicalJson();

    public RuntimeRetryPolicy {
        if (maxAttempts < 1 || maxAttempts > MAX_ATTEMPTS) {
            throw invalid("maxAttempts");
        }
        backoff = RuntimeSupport.immutable(backoff, MAX_ATTEMPTS, "backoff");
        if (backoff.size() != maxAttempts - 1) {
            // One delay per attempt after the first: a policy that omits one is not a schedule.
            throw invalid("backoff");
        }
        for (Duration delay : backoff) {
            if (delay.isZero() || delay.isNegative()
                    || delay.compareTo(RuntimeVersions.MAX_RETRY_BACKOFF) > 0) {
                throw invalid("backoffDelay");
            }
        }
        Objects.requireNonNull(policyHash, "policyHash");
        if (!policyHash.equals(CANONICAL_JSON.hash(material(maxAttempts, backoff)))) {
            throw invalid("policyHash");
        }
    }

    /** Builds one bounded retry policy and stamps it with its own canonical hash. */
    public static RuntimeRetryPolicy of(int maxAttempts, List<Duration> backoff) {
        List<Duration> copy = RuntimeSupport.immutable(backoff, MAX_ATTEMPTS, "backoff");
        return new RuntimeRetryPolicy(
                maxAttempts, copy, CANONICAL_JSON.hash(material(maxAttempts, copy)));
    }

    /** The accepted default test policy: three actual attempts, five seconds then thirty seconds. */
    public static RuntimeRetryPolicy defaults() {
        return of(3, List.of(Duration.ofSeconds(5), Duration.ofSeconds(30)));
    }

    /** The bounded delay before one attempt, where the first attempt has no delay. */
    public Duration delayBeforeAttempt(int attemptNumber) {
        if (attemptNumber < 1 || attemptNumber > maxAttempts) {
            throw invalid("attemptNumber");
        }
        return attemptNumber == 1 ? Duration.ZERO : backoff.get(attemptNumber - 2);
    }

    /** True when the given attempt number is the last one this policy permits. */
    public boolean isLastAttempt(int attemptNumber) {
        return attemptNumber >= maxAttempts;
    }

    private static Material material(int maxAttempts, List<Duration> backoff) {
        // The delays are covered by their canonical ISO-8601 text, so the policy bytes never depend on how a
        // duration happens to be rendered as a number.
        List<String> text = new java.util.ArrayList<>(backoff.size());
        for (Duration delay : backoff) {
            text.add(delay.toString());
        }
        return new Material(maxAttempts, List.copyOf(text));
    }

    private static RuntimeBoundaryException invalid(String invariant) {
        return new RuntimeBoundaryException(
                RuntimeErrorCodes.RUNTIME_RETRY_POLICY_INVALID, invariant,
                "A retry policy must be a bounded, complete and self-binding attempt schedule.");
    }

    /** Redacted diagnostics: no owner value is printed. */
    @Override
    public String toString() {
        return "RuntimeRetryPolicy[maxAttempts=" + maxAttempts
                + ", backoff=" + backoff
                + ", policyHash=" + policyHash + ']';
    }

    /** Package-private hash material; the delays are bound by their canonical ISO-8601 text. */
    record Material(int maxAttempts, List<String> backoffText) {
    }
}