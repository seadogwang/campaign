package com.company.campaign.fullpublish;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.zone.ZoneOffsetTransition;
import java.time.zone.ZoneRules;
import java.util.List;
import java.util.Objects;

/**
 * The deterministic daylight-saving resolution of one {@code DAILY} local date and time.
 *
 * <p>It is a pure function of the canonical zone and the local date-time: it reads no clock, no system
 * timezone and no ambient value, so the same local date and time always resolve to the same instant.
 *
 * <p>Two transitions are decided explicitly rather than left to a default:
 *
 * <ul>
 *   <li>a <em>nonexistent</em> local time — the clock moved forward across it — resolves to the transition's
 *       next valid local instant, so a daily occurrence is never silently dropped by a spring-forward;</li>
 *   <li>a <em>repeated</em> local time — the clock moved back across it — resolves to the earlier valid
 *       offset, so a daily occurrence is enqueued exactly once, in the first of its two possible instants.</li>
 * </ul>
 */
public final class ScheduleInstants {

    private ScheduleInstants() {
    }

    /** Resolves one local date and time in a canonical zone to its exact instant. */
    public static java.time.Instant resolve(ZoneId zone, LocalDate date, LocalTime time) {
        Objects.requireNonNull(zone, "zone");
        Objects.requireNonNull(date, "date");
        Objects.requireNonNull(time, "time");
        LocalDateTime local = LocalDateTime.of(date, time);
        ZoneRules rules = zone.getRules();
        List<ZoneOffset> offsets = rules.getValidOffsets(local);
        if (offsets.size() == 1) {
            return local.toInstant(offsets.get(0));
        }
        if (offsets.size() == 2) {
            // An overlap: the earlier valid offset is the one whose instant comes first, so the occurrence is
            // bound to the first of the two possible instants rather than to whichever the platform defaults to.
            java.time.Instant first = local.toInstant(offsets.get(0));
            java.time.Instant second = local.toInstant(offsets.get(1));
            return first.isBefore(second) ? first : second;
        }
        // A gap: the local time does not exist. The transition's first valid local instant after it is the
        // deterministic resolution, so the occurrence is moved forward instead of being dropped.
        ZoneOffsetTransition transition = rules.getTransition(local);
        if (transition == null) {
            throw ScheduleSupport.invalid(
                    ScheduleErrorCodes.SCHEDULE_WINDOW_INVALID, "localDateTime");
        }
        return transition.getDateTimeAfter().atZone(zone).toInstant();
    }

    @Override
    public String toString() {
        return "ScheduleInstants[deterministic=true]";
    }
}