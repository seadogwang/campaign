package com.company.campaign.query.plan;

import com.company.campaign.query.QueryErrorCodes;
import com.company.campaign.query.QueryException;

import java.time.Instant;
import java.util.List;
import com.company.campaign.segment.canonical.CanonicalMetricFilter;

/**
 * Closed, immutable concrete time window resolved from a canonical window identity plus trusted time context.
 *
 * <p>A {@code ROLLING} window is {@code [start, end)} with a start of exactly {@code asOf - N*24h} and an end
 * of {@code asOf}. A {@code FIXED} window uses the canonical UTC instants exactly and is never reinterpreted
 * in the context timezone. A {@code LIFETIME} window invents no start: it explicitly carries the evaluation
 * {@code asOf} and the data watermark that bound the retained history actually available.
 */
public sealed interface ResolvedWindow
        permits ResolvedWindow.Rolling, ResolvedWindow.Fixed, ResolvedWindow.Lifetime,
        ResolvedWindow.RelativeToAttribute, ResolvedWindow.SelectedEvent, ResolvedWindow.RelativeToEvent {

    /** The closed discriminator set for a concrete resolved window. */
    enum WindowKind {
        ROLLING,
        FIXED,
        LIFETIME,
        RELATIVE_TO_ATTRIBUTE,
        SELECTED_EVENT,
        RELATIVE_TO_EVENT
    }

    /** The exact wire discriminator for this resolved window. */
    WindowKind kind();

    /** A bounded interval derived from a rolling duration: {@code [asOf - N*24h, asOf)}. */
    record Rolling(Instant start, Instant end) implements ResolvedWindow {
        public Rolling {
            requireBounded(start, end);
        }

        @Override
        public WindowKind kind() {
            return WindowKind.ROLLING;
        }
    }

    /** A bounded interval whose boundaries are the canonical fixed instants, used exactly as declared. */
    record Fixed(Instant start, Instant end) implements ResolvedWindow {
        public Fixed {
            requireBounded(start, end);
        }

        @Override
        public WindowKind kind() {
            return WindowKind.FIXED;
        }
    }

    /** Retained history with no invented start, bounded only by the evaluation instant and data watermark. */
    record Lifetime(Instant evaluationAsOf, Instant dataWatermark) implements ResolvedWindow {
        public Lifetime {
            if (evaluationAsOf == null || dataWatermark == null) {
                throw invalid("lifetimeBounds", "A LIFETIME window requires an evaluation instant and a watermark.");
            }
            if (dataWatermark.isAfter(evaluationAsOf)) {
                throw invalid("lifetimeBounds", "A LIFETIME window watermark must not be after the evaluation instant.");
            }
        }

        @Override
        public WindowKind kind() {
            return WindowKind.LIFETIME;
        }
    }

    /** A plan-bound dynamic interval evaluated from one subject Attribute for each root row. */
    record RelativeToAttribute(
            String anchorDefinitionId,
            long startOffsetDays,
            long endOffsetDays) implements ResolvedWindow {

        public RelativeToAttribute {
            if (anchorDefinitionId == null || anchorDefinitionId.isBlank()
                    || startOffsetDays >= endOffsetDays) {
                throw invalid("relativeWindow", "A relative window requires an anchor and ordered offsets.");
            }
        }

        @Override
        public WindowKind kind() {
            return WindowKind.RELATIVE_TO_ATTRIBUTE;
        }
    }

    record SelectedEvent(
            String selectorId,
            List<CanonicalMetricFilter> selectorScopeFilters) implements ResolvedWindow {
        public SelectedEvent {
            if (selectorId == null || selectorId.isBlank()) {
                throw invalid("eventSelector", "A selected-event window requires its selector.");
            }
            selectorScopeFilters = selectorScopeFilters == null ? List.of() : List.copyOf(selectorScopeFilters);
        }

        @Override
        public WindowKind kind() {
            return WindowKind.SELECTED_EVENT;
        }
    }

    record RelativeToEvent(
            String selectorId,
            List<CanonicalMetricFilter> selectorScopeFilters,
            long startOffsetDays,
            long endOffsetDays,
            boolean excludeAnchorEvent) implements ResolvedWindow {
        public RelativeToEvent {
            if (selectorId == null || selectorId.isBlank() || startOffsetDays >= endOffsetDays) {
                throw invalid("eventSelector", "A relative event window requires a selector and ordered offsets.");
            }
            selectorScopeFilters = selectorScopeFilters == null ? List.of() : List.copyOf(selectorScopeFilters);
        }

        @Override
        public WindowKind kind() {
            return WindowKind.RELATIVE_TO_EVENT;
        }
    }

    private static void requireBounded(Instant start, Instant end) {
        if (start == null || end == null) {
            throw invalid("windowBounds", "A bounded window requires both boundaries.");
        }
        if (!start.isBefore(end)) {
            throw invalid("windowBounds", "A bounded window start must be strictly before its end.");
        }
    }

    private static QueryException invalid(String invariant, String message) {
        return new QueryException(QueryErrorCodes.QUERY_PLAN_INCONSISTENT, invariant, message);
    }
}
