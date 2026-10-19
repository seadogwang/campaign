package com.company.campaign.dev.preview.postgres;

import java.time.Duration;

/**
 * The immutable, bounded, server-owned execution policy of the PostgreSQL Preview adapter.
 *
 * <p>Every value is explicit and validated at construction; there is deliberately no static factory, no
 * default and no implicit instance, so a permissive or silently clamped policy cannot exist. The policy bounds
 * the statement timeout, the fetch size, the maximum number of member rows one Preview execution may read, how
 * old the host-owned data watermark may be, and the exact database product/version the pool must report.
 *
 * <p>Nothing here is request-selectable: the type is constructed by the opt-in host configuration only, and a
 * Preview request has no field, header, parameter or body member that could reach it.
 *
 * @param queryTimeoutSeconds         server-side JDBC statement timeout, in seconds
 * @param fetchSize                   JDBC fetch size applied before the query is executed
 * @param maxRows                     the largest number of member rows one execution may match
 * @param maxDataWatermarkAgeSeconds  the largest accepted {@code asOf - dataWatermark}, in seconds
 * @param requiredDatabaseProduct     the exact JDBC database product name the pool must report
 * @param minimumDatabaseMajorVersion the lowest accepted JDBC database major version
 */
public record PreviewPostgresExecutionPolicy(
        int queryTimeoutSeconds,
        int fetchSize,
        int maxRows,
        long maxDataWatermarkAgeSeconds,
        String requiredDatabaseProduct,
        int minimumDatabaseMajorVersion) {

    /** The largest statement timeout a host may configure, in seconds. */
    public static final int MAX_QUERY_TIMEOUT_SECONDS = 300;

    /** The largest fetch size a host may configure. */
    public static final int MAX_FETCH_SIZE = 100_000;

    /** The largest member-row bound a host may configure. */
    public static final int MAX_ROWS_BOUND = 1_000_000;

    /** The largest accepted data-watermark age a host may configure, in seconds (28 days). */
    public static final long MAX_DATA_WATERMARK_AGE_SECONDS = 2_419_200L;

    /** The lowest accepted database major version, so an ancient server is never silently trusted. */
    public static final int MINIMUM_ACCEPTED_MAJOR_VERSION = 9;

    public PreviewPostgresExecutionPolicy {
        requireRange(queryTimeoutSeconds, 1, MAX_QUERY_TIMEOUT_SECONDS, "queryTimeoutSeconds");
        requireRange(fetchSize, 1, MAX_FETCH_SIZE, "fetchSize");
        requireRange(maxRows, 1, MAX_ROWS_BOUND, "maxRows");
        if (maxDataWatermarkAgeSeconds < 1 || maxDataWatermarkAgeSeconds > MAX_DATA_WATERMARK_AGE_SECONDS) {
            throw invalid("maxDataWatermarkAgeSeconds");
        }
        if (requiredDatabaseProduct == null || requiredDatabaseProduct.isBlank()) {
            throw invalid("requiredDatabaseProduct");
        }
        if (minimumDatabaseMajorVersion < MINIMUM_ACCEPTED_MAJOR_VERSION
                || minimumDatabaseMajorVersion > 99) {
            throw invalid("minimumDatabaseMajorVersion");
        }
    }

    /**
     * The JDBC maximum-row setting one execution applies.
     *
     * <p>It is deliberately one row above {@link #maxRows()} so a result that exceeds the bound is
     * <em>detected</em> and refused rather than silently truncated into a plausible-looking answer.
     */
    public int maxReadRows() {
        return maxRows + 1;
    }

    /** The accepted data-watermark age as a duration. */
    public Duration maxDataWatermarkAge() {
        return Duration.ofSeconds(maxDataWatermarkAgeSeconds);
    }

    private static void requireRange(int value, int minimum, int maximum, String field) {
        if (value < minimum || value > maximum) {
            throw invalid(field);
        }
    }

    private static PreviewPostgresConfigurationException invalid(String field) {
        return new PreviewPostgresConfigurationException(
                PreviewPostgresErrorCodes.PREVIEW_POSTGRESQL_CONFIGURATION_INVALID,
                "The opt-in PostgreSQL Preview execution policy is missing or out of bounds: " + field);
    }
}