package com.company.campaign.readiness;

import java.time.Duration;

/**
 * Every strict bound the readiness slice enforces.
 *
 * <p>They live in one place so a string accepted by one readiness contract is accepted by all of them, and so
 * no part of the slice can quietly grow an unbounded list, an unbounded read or an unbounded statement.
 */
public final class ReadinessLimits {

    /** The largest length of a bounded readiness identifier such as an evidence or assessment id. */
    public static final int MAX_ID_LENGTH = 128;

    /** The largest length of a bounded uppercase machine code. */
    public static final int MAX_CODE_LENGTH = 64;

    /** The largest length of an exact version, hash or revision reference. */
    public static final int MAX_VERSION_LENGTH = 128;

    /** The largest length of a non-sensitive evidence reference. */
    public static final int MAX_REFERENCE_LENGTH = 256;

    /** The largest number of accepted evidence references one assessment may carry. */
    public static final int MAX_ACCEPTED_EVIDENCE = 32;

    /** The largest number of ordered blockers one assessment may carry. */
    public static final int MAX_BLOCKERS = 32;

    /** The largest number of evidence records one bounded store read returns. */
    public static final int MAX_EVIDENCE_PER_READ = 256;

    /** The largest number of audit records one bounded read returns. */
    public static final int MAX_AUDIT_PER_READ = 256;

    /** The largest number of evidence records one scope may retain. */
    public static final int MAX_EVIDENCE_PER_SCOPE = 4_096;

    /** The largest number of assessments one scope may retain. */
    public static final int MAX_ASSESSMENTS_PER_SCOPE = 4_096;

    /** The largest number of distinct blocker codes one observability snapshot may report. */
    public static final int MAX_BLOCKER_CODES = 32;

    /**
     * The largest window between an evidence observation and its expiry. A readiness claim may not rest on
     * evidence that is valid for longer than this, so an "always fresh" evidence record is unrepresentable.
     */
    public static final Duration MAX_EVIDENCE_WINDOW = Duration.ofDays(366);

    /**
     * How far into the future an evidence observation may be stamped. A wider tolerance would let a host
     * freeze evidence ahead of the evaluation instant it claims to describe.
     */
    public static final Duration MAX_OBSERVATION_SKEW = Duration.ofMinutes(5);

    /** The statement timeout the JDBC readiness adapter sets on every statement. */
    public static final int JDBC_STATEMENT_TIMEOUT_SECONDS = 5;

    /** The lock timeout the JDBC readiness adapter sets on every transaction. */
    public static final int JDBC_LOCK_TIMEOUT_MILLIS = 2_000;

    private ReadinessLimits() {
    }
}