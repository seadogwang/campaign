package com.company.campaign.segment.intent;

import com.company.campaign.metadata.domain.TimeWindowKind;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Closed immutable union of the time-window slots a clause may carry.
 *
 * <p>The window is its own slot, never encoded inside the value or the concept. The kind reuses the
 * Metadata-owned {@link TimeWindowKind} so an Intent window and a definition capability are compared on one
 * vocabulary; the unit is Intent-owned because it is a raw request, not yet a bound Metadata capability.
 */
public sealed interface IntentWindow
        permits IntentWindow.Rolling, IntentWindow.Lifetime, IntentWindow.Fixed, IntentWindow.CalendarMonths,
        IntentWindow.RelativeToAttribute, IntentWindow.SelectedEvent, IntentWindow.RelativeToEvent {

    /** Maximum rolling amount, mirroring the DSL duration bound. */
    int MAX_ROLLING_AMOUNT = 100_000;
    /** Maximum calendar-month buckets expanded by the deterministic host. */
    int MAX_CALENDAR_MONTHS = 24;

    /** The declared window kind. */
    TimeWindowKind kind();

    /** A relative rolling window of a fixed duration ending at the host {@code asOf}. */
    record Rolling(int amount, IntentWindowUnit unit) implements IntentWindow {

        public Rolling {
            if (unit == null) {
                throw IntentException.invalid(
                        IntentErrorCodes.WINDOW_INVALID, "A rolling window requires a duration unit");
            }
            if (amount <= 0 || amount > MAX_ROLLING_AMOUNT) {
                throw new IntentException(
                        IntentErrorCodes.LIMIT_EXCEEDED, "A rolling window amount is out of bounds");
            }
        }

        @Override
        public TimeWindowKind kind() {
            return TimeWindowKind.ROLLING;
        }
    }

    /** The retained lifetime of the subject. */
    record Lifetime() implements IntentWindow {

        @Override
        public TimeWindowKind kind() {
            return TimeWindowKind.LIFETIME;
        }
    }

    /** An explicit half-open instant interval. */
    record Fixed(String start, String end) implements IntentWindow {

        public Fixed {
            start = canonicalInstant(start);
            end = canonicalInstant(end);
            if (Instant.parse(start).isAfter(Instant.parse(end))) {
                throw IntentException.invalid(
                        IntentErrorCodes.WINDOW_INVALID, "A fixed window start must not follow its end");
            }
        }

        @Override
        public TimeWindowKind kind() {
            return TimeWindowKind.FIXED;
        }

        private static String canonicalInstant(String raw) {
            String text = IntentTexts.requireText(raw, "window.bound");
            try {
                return Instant.parse(text).toString();
            } catch (DateTimeParseException exception) {
                throw IntentException.invalid(
                        IntentErrorCodes.WINDOW_INVALID, "A fixed window bound is not a well-formed instant");
            }
        }
    }

    /** Calendar-aligned months whose exact instant boundaries are computed by the trusted host. */
    record CalendarMonths(
            int count,
            boolean includeCurrent,
            CalendarBucketRequirement bucketRequirement) implements IntentWindow {

        public CalendarMonths {
            if (count <= 0 || count > MAX_CALENDAR_MONTHS) {
                throw new IntentException(
                        IntentErrorCodes.LIMIT_EXCEEDED, "A calendar-month count is out of bounds");
            }
            if (bucketRequirement == null) {
                throw IntentException.invalid(
                        IntentErrorCodes.WINDOW_INVALID, "A calendar-month window requires a bucket policy");
            }
        }

        @Override
        public TimeWindowKind kind() {
            return TimeWindowKind.FIXED;
        }
    }

    /** A bounded interval derived independently for each subject from one permitted temporal Attribute. */
    record RelativeToAttribute(
            String anchorDefinitionId,
            int startOffset,
            int endOffset,
            IntentWindowUnit unit) implements IntentWindow {

        public RelativeToAttribute {
            if (anchorDefinitionId == null || anchorDefinitionId.isBlank() || anchorDefinitionId.length() > 128) {
                throw IntentException.invalid(
                        IntentErrorCodes.WINDOW_INVALID, "A relative window requires a bounded anchor definition ID");
            }
            if (unit == null) {
                throw IntentException.invalid(
                        IntentErrorCodes.WINDOW_INVALID, "A relative window requires an offset unit");
            }
            if (startOffset < -MAX_ROLLING_AMOUNT || startOffset > MAX_ROLLING_AMOUNT
                    || endOffset < -MAX_ROLLING_AMOUNT || endOffset > MAX_ROLLING_AMOUNT
                    || startOffset >= endOffset) {
                throw IntentException.invalid(
                        IntentErrorCodes.WINDOW_INVALID, "A relative window requires ordered bounded offsets");
            }
        }

        @Override
        public TimeWindowKind kind() {
            return TimeWindowKind.RELATIVE_TO_ATTRIBUTE;
        }
    }

    /** A metric restricted to one event selected by governed Metadata. */
    record SelectedEvent(
            String selectorId,
            List<IntentScopeFilter> selectorScopeFilters) implements IntentWindow {
        public SelectedEvent {
            selectorId = requireSelectorId(selectorId);
            selectorScopeFilters = selectorFilters(selectorScopeFilters);
        }

        @Override
        public TimeWindowKind kind() {
            return TimeWindowKind.SELECTED_EVENT;
        }
    }

    /** A per-subject interval anchored to one governed selected event. */
    record RelativeToEvent(
            String selectorId,
            List<IntentScopeFilter> selectorScopeFilters,
            int startOffset,
            int endOffset,
            IntentWindowUnit unit,
            boolean excludeAnchorEvent) implements IntentWindow {
        public RelativeToEvent {
            selectorId = requireSelectorId(selectorId);
            selectorScopeFilters = selectorFilters(selectorScopeFilters);
            if (unit == null || startOffset < -MAX_ROLLING_AMOUNT || startOffset > MAX_ROLLING_AMOUNT
                    || endOffset < -MAX_ROLLING_AMOUNT || endOffset > MAX_ROLLING_AMOUNT
                    || startOffset >= endOffset) {
                throw IntentException.invalid(
                        IntentErrorCodes.WINDOW_INVALID,
                        "A relative event window requires ordered bounded offsets and a unit");
            }
        }

        @Override
        public TimeWindowKind kind() {
            return TimeWindowKind.RELATIVE_TO_EVENT;
        }
    }

    private static String requireSelectorId(String value) {
        if (value == null || value.isBlank() || value.length() > 128) {
            throw IntentException.invalid(IntentErrorCodes.WINDOW_INVALID,
                    "An event window requires a bounded selector ID");
        }
        return value;
    }

    private static List<IntentScopeFilter> selectorFilters(List<IntentScopeFilter> filters) {
        if (filters == null || filters.isEmpty()) {
            return List.of();
        }
        Set<String> ids = new HashSet<>();
        for (IntentScopeFilter filter : filters) {
            if (filter == null || !ids.add(filter.filterId())) {
                throw IntentException.invalid(IntentErrorCodes.SCOPE_FILTER_INVALID,
                        "Selector scope filters must be non-null and unique by filter ID");
            }
        }
        return List.copyOf(filters);
    }
}
