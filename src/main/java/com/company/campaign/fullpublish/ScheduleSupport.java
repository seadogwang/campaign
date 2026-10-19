package com.company.campaign.fullpublish;

import com.company.campaign.builder.capability.schedule.ScheduleBasicVersions;
import com.company.campaign.contracts.ContractPatterns;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.regex.Pattern;

/**
 * The bounded, deterministic field semantics the T21 schedule compiler and the compiled schedule record share.
 *
 * <p>It exists so the compiler that normalizes a payload and the record that re-proves its own bytes cannot
 * drift apart: the canonical timezone rule, the offset-must-match-zone rule, the canonical local-time text and
 * the inclusive bounded calendar window are decided in exactly one place.
 *
 * <p>Nothing here reads a clock, a system timezone, a repository, a random source or a mutable registry: a
 * zone rules lookup is a pure function of one explicit instant, so the same payload always yields the same
 * canonical text and therefore the same schedule hash.
 */
final class ScheduleSupport {

    private static final Pattern IDENTIFIER = Pattern.compile(ContractPatterns.REFERENCE_ID);

    private ScheduleSupport() {
    }

    /** Requires a bounded canonical identifier, or fails closed. */
    static String requireIdentifier(String value, String invariant) {
        if (value == null || !IDENTIFIER.matcher(value).matches()) {
            throw invalid(ScheduleErrorCodes.SCHEDULE_INPUT_INVALID, invariant);
        }
        return value;
    }

    /**
     * The canonical, region-based IANA timezone identifier of one declared token.
     *
     * <p>Only a canonical region identifier is a timezone. A fixed offset ({@code +08:00}, {@code Z}), a
     * legacy alias and a non-canonical spelling are all refused rather than coerced into a neighbouring zone,
     * because two spellings of one zone would otherwise produce two schedule hashes for one semantic schedule.
     */
    static String canonicalZone(String token) {
        if (token == null || token.isEmpty() || token.length() > ScheduleBasicVersions.MAX_ZONE_ID_LENGTH) {
            throw invalid(ScheduleErrorCodes.SCHEDULE_FIELD_INVALID, "zoneId");
        }
        ZoneId zone;
        try {
            zone = ZoneId.of(token);
        } catch (DateTimeException rejected) {
            throw invalid(ScheduleErrorCodes.SCHEDULE_ZONE_INVALID, "zoneId");
        }
        if (zone instanceof ZoneOffset || !zone.getId().equals(token)) {
            throw invalid(ScheduleErrorCodes.SCHEDULE_ZONE_INVALID, "zoneId");
        }
        return zone.getId();
    }

    /**
     * The canonical offset-instant text of an {@code ONCE} schedule.
     *
     * <p>The declared offset must equal the canonical zone's offset at that exact instant: an instant that
     * contradicts the zone it claims to be in is refused rather than silently reinterpreted in another offset.
     */
    static String canonicalRunAt(String token, String zoneId) {
        if (token == null || token.isEmpty() || token.length() > ScheduleBasicVersions.MAX_INSTANT_LENGTH) {
            throw invalid(ScheduleErrorCodes.SCHEDULE_FIELD_INVALID, "runAt");
        }
        OffsetDateTime parsed;
        try {
            parsed = OffsetDateTime.parse(token);
        } catch (DateTimeParseException rejected) {
            throw invalid(ScheduleErrorCodes.SCHEDULE_FIELD_INVALID, "runAt");
        }
        if (!ZoneId.of(zoneId).getRules().getOffset(parsed.toInstant()).equals(parsed.getOffset())) {
            throw invalid(ScheduleErrorCodes.SCHEDULE_WINDOW_INVALID, "runAt");
        }
        return parsed.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }

    /** The canonical local-time text of a {@code DAILY} schedule. */
    static String canonicalLocalTime(String token) {
        if (token == null || token.isEmpty() || token.length() > ScheduleBasicVersions.MAX_LOCAL_TIME_LENGTH) {
            throw invalid(ScheduleErrorCodes.SCHEDULE_FIELD_INVALID, "localTime");
        }
        LocalTime parsed;
        try {
            parsed = LocalTime.parse(token);
        } catch (DateTimeParseException rejected) {
            throw invalid(ScheduleErrorCodes.SCHEDULE_FIELD_INVALID, "localTime");
        }
        return parsed.toString();
    }

    /**
     * The canonical inclusive calendar-date window of a {@code DAILY} schedule, counted in calendar days.
     *
     * <p>The count is an inclusive day difference against the frozen accepted T19 bound, not occurrence
     * generation: it never expands a date into an occurrence, resolves a transition or produces work.
     */
    static String[] canonicalWindow(String startToken, String endToken) {
        LocalDate start = parseDate(startToken);
        LocalDate end = parseDate(endToken);
        if (end.isBefore(start)) {
            throw invalid(ScheduleErrorCodes.SCHEDULE_WINDOW_INVALID, "startDate");
        }
        if (ChronoUnit.DAYS.between(start, end) + 1 > ScheduleVersions.MAX_CALENDAR_DAYS) {
            throw invalid(ScheduleErrorCodes.SCHEDULE_WINDOW_INVALID, "endDate");
        }
        return new String[] {start.format(DateTimeFormatter.ISO_LOCAL_DATE),
                end.format(DateTimeFormatter.ISO_LOCAL_DATE)};
    }

    /** The canonical ISO local-date text of one declared window bound. */
    static String canonicalDate(String token) {
        return parseDate(token).format(DateTimeFormatter.ISO_LOCAL_DATE);
    }

    /** Parses one declared local date, failing closed on any malformed or non-canonical token. */
    static LocalDate parseDate(String token) {
        if (token == null || token.isEmpty() || token.length() > ScheduleBasicVersions.MAX_LOCAL_TIME_LENGTH) {
            throw invalid(ScheduleErrorCodes.SCHEDULE_FIELD_INVALID, "startDate");
        }
        try {
            return LocalDate.parse(token);
        } catch (DateTimeParseException rejected) {
            throw invalid(ScheduleErrorCodes.SCHEDULE_FIELD_INVALID, "startDate");
        }
    }

    static ScheduleException invalid(String code, String invariant) {
        return new ScheduleException(code, invariant, "A basic schedule must be a bounded, canonical payload.");
    }
}