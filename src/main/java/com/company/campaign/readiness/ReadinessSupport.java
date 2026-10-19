package com.company.campaign.readiness;

import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * The shared bounded-field grammar of the readiness slice.
 *
 * <p>Every readiness contract validates through these helpers, so an identifier accepted by an evidence
 * record is accepted by the assessment that references it, and no part of the slice can grow a field with
 * its own drifting grammar. A rejection names the field and never echoes the rejected text, so a value that
 * contained something sensitive cannot be recovered from an error.
 */
public final class ReadinessSupport {

    /** A bounded readiness identifier: tenant, program, evidence, assessment or actor reference. */
    private static final Pattern ID = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$");

    /** A bounded uppercase machine code. */
    private static final Pattern CODE = Pattern.compile("^[A-Z][A-Z0-9_]{0,63}$");

    /** An exact version, hash or revision reference. */
    private static final Pattern VERSION = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}$");

    /** A bounded non-sensitive evidence reference, such as a frozen asset path. */
    private static final Pattern REFERENCE = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._:/@-]{0,255}$");

    private ReadinessSupport() {
    }

    public static String requireId(String value, String field) {
        if (value == null || !ID.matcher(value).matches() || value.length() > ReadinessLimits.MAX_ID_LENGTH) {
            throw ReadinessException.invalidField(field);
        }
        return value;
    }

    public static String requireCode(String value, String field) {
        if (value == null || !CODE.matcher(value).matches()
                || value.length() > ReadinessLimits.MAX_CODE_LENGTH) {
            throw ReadinessException.invalidField(field);
        }
        return value;
    }

    public static String requireVersion(String value, String field) {
        if (value == null || !VERSION.matcher(value).matches()
                || value.length() > ReadinessLimits.MAX_VERSION_LENGTH) {
            throw ReadinessException.invalidField(field);
        }
        return value;
    }

    public static String requireReference(String value, String field) {
        if (value == null || !REFERENCE.matcher(value).matches()
                || value.length() > ReadinessLimits.MAX_REFERENCE_LENGTH) {
            throw ReadinessException.invalidField(field);
        }
        return value;
    }

    public static Instant requireInstant(Instant value, String field) {
        return Objects.requireNonNull(value, field);
    }

    public static <T> T requireNonNull(T value, String field) {
        return Objects.requireNonNull(value, field);
    }

    /** True when the value is a bounded readiness identifier. */
    public static boolean isId(String value) {
        return value != null && ID.matcher(value).matches();
    }

    /** True when the value is a bounded uppercase machine code. */
    public static boolean isCode(String value) {
        return value != null && CODE.matcher(value).matches();
    }
}