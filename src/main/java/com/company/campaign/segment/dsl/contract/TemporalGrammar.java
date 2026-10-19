package com.company.campaign.segment.dsl.contract;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.regex.Pattern;

/**
 * The one exact lexical policy for Segment DSL temporal text, shared by DATE and DATETIME values and by FIXED
 * window boundaries.
 *
 * <p>Java's {@code ISO_OFFSET_DATE_TIME} accepts lexical variants the wire contract does not: optional seconds,
 * offsets without a colon, and an unbounded fractional part. Accepting those here would let a document pass the
 * codec while the checked-in JSON Schema pattern rejects it. This class therefore fixes one grammar and makes
 * the Java rules and the schema agree:
 *
 * <ul>
 *   <li>DATE is exactly {@code YYYY-MM-DD} and a real calendar day;</li>
 *   <li>DATETIME and FIXED boundaries are exactly
 *       {@code YYYY-MM-DDTHH:mm:ss[.fraction](Z|+HH:mm|-HH:mm)} with uppercase {@code T}/{@code Z}, a real date,
 *       time and offset, and a bounded fractional length.</li>
 * </ul>
 *
 * <p>Missing seconds, compact offsets, offset-free local timestamps, zone IDs, lowercase variants and trailing
 * text are rejected. The declared lexeme is preserved: no normalization to UTC is applied, and nothing here
 * consults the default locale, timezone or calendar.
 */
public final class TemporalGrammar {

    private static final Pattern EXACT_DATE = Pattern.compile("^[0-9]{4}-[0-9]{2}-[0-9]{2}$");

    private static final Pattern EXACT_OFFSET_DATE_TIME = Pattern.compile(
            "^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}"
                    + "(\\.[0-9]{1," + SegmentDslLimits.MAX_DATETIME_FRACTIONAL_DIGITS + "})?"
                    + "(Z|[+-][0-9]{2}:[0-9]{2})$");

    private static final DateTimeFormatter STRICT_LOCAL_DATE = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final DateTimeFormatter STRICT_OFFSET_DATE_TIME = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    private TemporalGrammar() {
    }

    /** True when the text is exactly {@code YYYY-MM-DD} and names a real calendar day. */
    public static boolean isExactLocalDate(String value) {
        if (value == null || !EXACT_DATE.matcher(value).matches()) {
            return false;
        }
        try {
            LocalDate.parse(value, STRICT_LOCAL_DATE);
            return true;
        } catch (DateTimeParseException exception) {
            return false;
        }
    }

    /** True when the text matches the exact offset date-time grammar and resolves to a real date, time, offset. */
    public static boolean isExactOffsetDateTime(String value) {
        if (value == null || value.length() > SegmentDslLimits.MAX_DATETIME_TEXT_LENGTH) {
            return false;
        }
        if (!EXACT_OFFSET_DATE_TIME.matcher(value).matches()) {
            return false;
        }
        try {
            OffsetDateTime.parse(value, STRICT_OFFSET_DATE_TIME);
            return true;
        } catch (DateTimeParseException exception) {
            return false;
        }
    }

    /**
     * Parses text already accepted by {@link #isExactOffsetDateTime(String)}.
     *
     * <p>The failure it can raise never echoes the rejected text, so it is safe to use on the parse path.
     */
    public static OffsetDateTime parseExactOffsetDateTime(String value) {
        if (!isExactOffsetDateTime(value)) {
            throw new IllegalArgumentException("Not an exact offset date-time");
        }
        return OffsetDateTime.parse(value, STRICT_OFFSET_DATE_TIME);
    }
}