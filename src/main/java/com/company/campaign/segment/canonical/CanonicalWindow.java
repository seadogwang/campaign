package com.company.campaign.segment.canonical;

import com.company.campaign.segment.dsl.contract.SegmentDslLimits;

import java.time.Instant;
import java.util.List;

/**
 * Closed, immutable canonical time window.
 *
 * <p>It keeps the declared window kind and the exact boundary semantics the v1.3 spec fixes:
 * a ROLLING window is {@code [asOf - duration, asOf)}; a FIXED window is {@code [start, end)} with both
 * boundaries resolved to the one UTC instant representation; a LIFETIME window is the retained history
 * strictly before the exclusive {@code dataWatermark} boundary and is reported as a limitation rather than
 * silently bounded.
 *
 * <p>A ROLLING window is normalized to one duration representation — a whole number of days — because
 * v1.3 defines {@code DAY} as 24 hours and {@code WEEK} as exactly 7 days. {@code ROLLING 1 WEEK} and
 * {@code ROLLING 7 DAY} are therefore one canonical duration and serialize and hash identically; the
 * editable T05 document keeps its declared unit. The day count is bounded by the largest duration any T05
 * document can declare, so the normalization can never overflow.
 */
public sealed interface CanonicalWindow
        permits CanonicalWindow.RollingWindow, CanonicalWindow.LifetimeWindow, CanonicalWindow.FixedWindow,
        CanonicalWindow.RelativeToAttributeWindow, CanonicalWindow.SelectedEventWindow,
        CanonicalWindow.RelativeToEventWindow {

    /** The exact wire discriminator for this canonical window. */
    CanonicalWindowKind kind();

    /** A duration counted back from {@code asOf}, normalized to a whole number of days. */
    record RollingWindow(long days) implements CanonicalWindow {

        /** The largest canonical duration: the T05 maximum amount in its longest declared unit ({@code WEEK}). */
        public static final long MAX_DAYS = 7L * SegmentDslLimits.MAX_WINDOW_AMOUNT;

        public RollingWindow {
            if (days <= 0) {
                throw CanonicalInvariants.failure(
                        CanonicalErrorCodes.CANONICAL_WINDOW_INVALID, "rollingDays",
                        "A canonical ROLLING duration must be a positive number of days.");
            }
            if (days > MAX_DAYS) {
                throw CanonicalInvariants.failure(
                        CanonicalErrorCodes.CANONICAL_WINDOW_INVALID, "rollingDays",
                        "A canonical ROLLING duration exceeds the largest declarable duration.");
            }
        }

        @Override
        public CanonicalWindowKind kind() {
            return CanonicalWindowKind.ROLLING;
        }
    }

    /** All retained history before exclusive {@code dataWatermark}; the retention limitation is explicit. */
    record LifetimeWindow() implements CanonicalWindow {
        @Override
        public CanonicalWindowKind kind() {
            return CanonicalWindowKind.LIFETIME;
        }
    }

    /** An explicit {@code [start, end)} interval whose boundaries are the same instant in UTC form. */
    record FixedWindow(String startInstant, String endInstant) implements CanonicalWindow {
        public FixedWindow {
            CanonicalInvariants.requirePresent(startInstant, "fixedWindowStart");
            CanonicalInvariants.requirePresent(endInstant, "fixedWindowEnd");
            CanonicalInvariants.requireCanonicalInstant(startInstant);
            CanonicalInvariants.requireCanonicalInstant(endInstant);
            if (!Instant.parse(startInstant).isBefore(Instant.parse(endInstant))) {
                throw CanonicalInvariants.failure(
                        CanonicalErrorCodes.CANONICAL_WINDOW_INVALID, "fixedBoundaries",
                        "A canonical FIXED window start must be strictly before its end.");
            }
        }

        @Override
        public CanonicalWindowKind kind() {
            return CanonicalWindowKind.FIXED;
        }
    }

    /** A bounded interval whose endpoints are whole-day offsets from a temporal Attribute. */
    record RelativeToAttributeWindow(
            String anchorDefinitionId,
            long startOffsetDays,
            long endOffsetDays) implements CanonicalWindow {

        public RelativeToAttributeWindow {
            CanonicalInvariants.requirePresent(anchorDefinitionId, "relativeAnchorDefinitionId");
            if (anchorDefinitionId.isBlank() || anchorDefinitionId.length() > 128) {
                throw CanonicalInvariants.failure(
                        CanonicalErrorCodes.CANONICAL_WINDOW_INVALID, "relativeAnchorDefinitionId",
                        "A relative window requires a bounded anchor definition ID.");
            }
            if (startOffsetDays < -RollingWindow.MAX_DAYS || startOffsetDays > RollingWindow.MAX_DAYS
                    || endOffsetDays < -RollingWindow.MAX_DAYS || endOffsetDays > RollingWindow.MAX_DAYS
                    || startOffsetDays >= endOffsetDays) {
                throw CanonicalInvariants.failure(
                        CanonicalErrorCodes.CANONICAL_WINDOW_INVALID, "relativeOffsets",
                        "A relative window requires ordered bounded offsets.");
            }
        }

        @Override
        public CanonicalWindowKind kind() {
            return CanonicalWindowKind.RELATIVE_TO_ATTRIBUTE;
        }
    }

    /** One event selected by a governed Metadata selector. */
    record SelectedEventWindow(
            String selectorId,
            List<CanonicalMetricFilter> selectorScopeFilters) implements CanonicalWindow {
        public SelectedEventWindow {
            CanonicalInvariants.requirePresent(selectorId, "eventSelectorId");
            selectorScopeFilters = selectorScopeFilters == null ? List.of() : List.copyOf(selectorScopeFilters);
        }

        @Override
        public CanonicalWindowKind kind() {
            return CanonicalWindowKind.SELECTED_EVENT;
        }
    }

    /** A bounded interval anchored to a governed selected event. */
    record RelativeToEventWindow(
            String selectorId,
            List<CanonicalMetricFilter> selectorScopeFilters,
            long startOffsetDays,
            long endOffsetDays,
            boolean excludeAnchorEvent) implements CanonicalWindow {
        public RelativeToEventWindow {
            CanonicalInvariants.requirePresent(selectorId, "eventSelectorId");
            selectorScopeFilters = selectorScopeFilters == null ? List.of() : List.copyOf(selectorScopeFilters);
            if (startOffsetDays < -RollingWindow.MAX_DAYS || startOffsetDays > RollingWindow.MAX_DAYS
                    || endOffsetDays < -RollingWindow.MAX_DAYS || endOffsetDays > RollingWindow.MAX_DAYS
                    || startOffsetDays >= endOffsetDays) {
                throw CanonicalInvariants.failure(
                        CanonicalErrorCodes.CANONICAL_WINDOW_INVALID, "relativeEventOffsets",
                        "A relative event window requires ordered bounded offsets.");
            }
        }

        @Override
        public CanonicalWindowKind kind() {
            return CanonicalWindowKind.RELATIVE_TO_EVENT;
        }
    }
}
