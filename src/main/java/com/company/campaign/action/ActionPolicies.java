package com.company.campaign.action;

import com.company.campaign.shared.canonical.CanonicalJson;
import com.company.campaign.shared.canonical.ContentHash;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The bounded, persisted submission and reconciliation policies one external-action worker applies.
 *
 * <p>It fixes how many <em>actual</em> Provider submissions and how many <em>actual</em> reconciliation queries
 * one action may consume, and the exact delay before each call after the first, so a retry sequence is a
 * bounded deterministic fact rather than a loop. The deterministic default is three actual attempts of each
 * kind with delays of five seconds then thirty seconds, which is the accepted v1.3 example policy.
 *
 * <p>There is deliberately no policy that could retry forever and none that could retry a business outcome as
 * a technical failure: a definitive non-application only becomes another submission through an explicit
 * verification that proved it, and only while the bounded attempt budget remains.
 */
public record ActionPolicies(
        int maxSubmissionAttempts,
        List<Duration> submissionBackoff,
        int maxVerificationAttempts,
        List<Duration> verificationBackoff,
        ContentHash policyHash
) {

    private static final CanonicalJson CANONICAL_JSON = new CanonicalJson();

    public ActionPolicies {
        requireSchedule(maxSubmissionAttempts, submissionBackoff, "submissionBackoff");
        requireSchedule(maxVerificationAttempts, verificationBackoff, "verificationBackoff");
        Objects.requireNonNull(policyHash, "policyHash");
        if (!policyHash.equals(CANONICAL_JSON.hash(material(
                maxSubmissionAttempts, submissionBackoff, maxVerificationAttempts, verificationBackoff)))) {
            throw ActionSupport.invalid(ActionErrorCodes.ACTION_POLICY_INVALID, "policyHash");
        }
    }

    /** Builds one bounded policy pair and stamps it with its own canonical hash. */
    public static ActionPolicies of(
            int maxSubmissionAttempts,
            List<Duration> submissionBackoff,
            int maxVerificationAttempts,
            List<Duration> verificationBackoff) {
        List<Duration> submissions = ActionSupport.immutable(
                submissionBackoff, ActionVersions.MAX_POLICY_ATTEMPTS, "submissionBackoff");
        List<Duration> verifications = ActionSupport.immutable(
                verificationBackoff, ActionVersions.MAX_POLICY_ATTEMPTS, "verificationBackoff");
        return new ActionPolicies(
                maxSubmissionAttempts, submissions, maxVerificationAttempts, verifications,
                CANONICAL_JSON.hash(material(
                        maxSubmissionAttempts, submissions, maxVerificationAttempts, verifications)));
    }

    /** The accepted deterministic default: three actual attempts of each kind, five seconds then thirty. */
    public static ActionPolicies defaults() {
        return of(3, List.of(Duration.ofSeconds(5), Duration.ofSeconds(30)),
                3, List.of(Duration.ofSeconds(5), Duration.ofSeconds(30)));
    }

    /** The bounded delay before one submission attempt, where the first attempt has no delay. */
    public Duration delayBeforeSubmission(int attemptNumber) {
        return delayBefore(submissionBackoff, maxSubmissionAttempts, attemptNumber);
    }

    /** The bounded delay before one reconciliation query, where the first query has no delay. */
    public Duration delayBeforeVerification(int attemptNumber) {
        return delayBefore(verificationBackoff, maxVerificationAttempts, attemptNumber);
    }

    /** True when the given submission attempt number is the last one this policy permits. */
    public boolean isLastSubmission(int attemptNumber) {
        return attemptNumber >= maxSubmissionAttempts;
    }

    /** True when the given reconciliation attempt number is the last one this policy permits. */
    public boolean isLastVerification(int attemptNumber) {
        return attemptNumber >= maxVerificationAttempts;
    }

    /** True when one more submission may still be started at the given actual submission count. */
    public boolean submissionAttemptsRemain(int submissionCount) {
        return submissionCount < maxSubmissionAttempts;
    }

    /** True when one more reconciliation may still be started at the given actual query count. */
    public boolean verificationAttemptsRemain(int verificationCount) {
        return verificationCount < maxVerificationAttempts;
    }

    private static Duration delayBefore(List<Duration> backoff, int maxAttempts, int attemptNumber) {
        if (attemptNumber < 1 || attemptNumber > maxAttempts) {
            throw ActionSupport.invalid(ActionErrorCodes.ACTION_POLICY_INVALID, "attemptNumber");
        }
        return attemptNumber == 1 ? Duration.ZERO : backoff.get(attemptNumber - 2);
    }

    private static void requireSchedule(int maxAttempts, List<Duration> backoff, String field) {
        if (maxAttempts < 1 || maxAttempts > ActionVersions.MAX_POLICY_ATTEMPTS) {
            throw ActionSupport.invalid(ActionErrorCodes.ACTION_POLICY_INVALID, field);
        }
        List<Duration> bounded = ActionSupport.immutable(backoff, ActionVersions.MAX_POLICY_ATTEMPTS, field);
        if (bounded.size() != maxAttempts - 1) {
            // One delay per attempt after the first: a policy that omits one is not a schedule.
            throw ActionSupport.invalid(ActionErrorCodes.ACTION_POLICY_INVALID, field);
        }
        for (Duration delay : bounded) {
            if (delay.isZero() || delay.isNegative()
                    || delay.compareTo(ActionVersions.MAX_RETRY_BACKOFF) > 0) {
                throw ActionSupport.invalid(ActionErrorCodes.ACTION_POLICY_INVALID, field);
            }
        }
    }

    private static Material material(
            int maxSubmissionAttempts,
            List<Duration> submissionBackoff,
            int maxVerificationAttempts,
            List<Duration> verificationBackoff) {
        // The delays are covered by their canonical ISO-8601 text, so the policy bytes never depend on how a
        // duration happens to be rendered as a number.
        return new Material(maxSubmissionAttempts, text(submissionBackoff), maxVerificationAttempts,
                text(verificationBackoff));
    }

    private static List<String> text(List<Duration> durations) {
        List<String> values = new ArrayList<>(durations.size());
        for (Duration duration : durations) {
            values.add(duration.toString());
        }
        return List.copyOf(values);
    }

    /** Redacted diagnostics: no owner value is printed. */
    @Override
    public String toString() {
        return "ActionPolicies[maxSubmissions=" + maxSubmissionAttempts
                + ", maxVerifications=" + maxVerificationAttempts
                + ", policyHash=" + policyHash + ']';
    }

    /** Package-private hash material; the delays are bound by their canonical ISO-8601 text. */
    record Material(
            int maxSubmissionAttempts,
            List<String> submissionBackoffText,
            int maxVerificationAttempts,
            List<String> verificationBackoffText) {
    }
}