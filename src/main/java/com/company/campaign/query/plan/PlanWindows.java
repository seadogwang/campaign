package com.company.campaign.query.plan;

import com.company.campaign.metadata.domain.EventTimeSemantics;
import com.company.campaign.metadata.domain.WindowBoundarySemantics;
import com.company.campaign.query.QueryErrorCodes;
import com.company.campaign.query.QueryException;
import com.company.campaign.segment.canonical.CanonicalWindow;
import com.company.campaign.segment.canonical.CanonicalWindowKind;

import java.time.DateTimeException;
import java.time.Instant;

/**
 * The one deterministic window resolver shared by the planner and the plan construction boundary.
 *
 * <p>Both the planner that assembles a plan and the constructor that re-proves it call these factories, so
 * the two can never disagree about a concrete rolling/fixed/lifetime boundary, an inclusivity decision or the
 * event-time a window carries. Resolution uses only the trusted {@code asOf} and data watermark, never a local
 * clock, so the same canonical window always resolves to the same instants.
 */
final class PlanWindows {

    private static final long SECONDS_PER_DAY = 86_400L;

    /** Resolves one canonical window identity against the trusted time context. */
    static ResolvedWindow resolve(CanonicalWindow canonical, Instant asOf, Instant dataWatermark) {
        PlanStructure.requirePresent(canonical, "canonicalWindow");
        if (asOf == null || dataWatermark == null) {
            throw new QueryException(QueryErrorCodes.QUERY_TIME_CONTEXT_INVALID, "windowTimeContext",
                    "Resolving a window requires the trusted evaluation instant and data watermark.");
        }
        return switch (canonical) {
            case CanonicalWindow.RollingWindow rolling -> {
                long seconds;
                try {
                    seconds = Math.multiplyExact(rolling.days(), SECONDS_PER_DAY);
                } catch (ArithmeticException exception) {
                    throw overflow();
                }
                Instant start;
                try {
                    start = asOf.minusSeconds(seconds);
                } catch (DateTimeException | ArithmeticException exception) {
                    throw overflow();
                }
                yield new ResolvedWindow.Rolling(start, asOf);
            }
            case CanonicalWindow.FixedWindow fixed -> {
                try {
                    yield new ResolvedWindow.Fixed(
                            Instant.parse(fixed.startInstant()), Instant.parse(fixed.endInstant()));
                } catch (DateTimeException exception) {
                    throw new QueryException(QueryErrorCodes.QUERY_PLAN_INCONSISTENT, "fixedBoundaries",
                            "A canonical FIXED window carried boundaries that could not be resolved.");
                }
            }
            case CanonicalWindow.LifetimeWindow ignored -> new ResolvedWindow.Lifetime(asOf, dataWatermark);
            case CanonicalWindow.RelativeToAttributeWindow relative -> new ResolvedWindow.RelativeToAttribute(
                    relative.anchorDefinitionId(), relative.startOffsetDays(), relative.endOffsetDays());
            case CanonicalWindow.SelectedEventWindow selected -> new ResolvedWindow.SelectedEvent(
                    selected.selectorId(), selected.selectorScopeFilters());
            case CanonicalWindow.RelativeToEventWindow relative -> new ResolvedWindow.RelativeToEvent(
                    relative.selectorId(), relative.selectorScopeFilters(), relative.startOffsetDays(),
                    relative.endOffsetDays(), relative.excludeAnchorEvent());
        };
    }

    /**
     * Builds the one expected metric window binding.
     *
     * <p>The event-time semantics is normalized exactly once — an absent capability becomes
     * {@link EventTimeSemantics#NOT_APPLICABLE} — so both callers produce identical bytes for a LIFETIME-only
     * metric whose event-time capability is absent.
     */
    static ResolvedWindowBinding.MetricWindow metricWindow(
            String path,
            CanonicalWindow canonical,
            WindowBoundarySemantics boundarySemantics,
            EventTimeSemantics eventTimeSemantics,
            String eventTimeField,
            Instant asOf,
            Instant dataWatermark) {
        ResolvedWindow resolved = resolve(canonical, asOf, dataWatermark);
        boolean bounded = canonical.kind() != CanonicalWindowKind.LIFETIME;
        boolean includeStart = bounded;
        boolean includeEnd = bounded && boundarySemantics == WindowBoundarySemantics.INCLUSIVE_BOTH;
        EventTimeSemantics semantics = eventTimeSemantics == null
                ? EventTimeSemantics.NOT_APPLICABLE
                : eventTimeSemantics;
        return new ResolvedWindowBinding.MetricWindow(
                path, canonical, resolved, includeStart, includeEnd, boundarySemantics, semantics, eventTimeField);
    }

    /** Builds the one expected relation EXISTS window binding under the accepted {@code [start, end)} rule. */
    static ResolvedWindowBinding.RelationWindow relationWindow(
            String path,
            CanonicalWindow canonical,
            EventTimeSemantics eventTimeSemantics,
            String eventTimeField,
            Instant asOf,
            Instant dataWatermark) {
        ResolvedWindow resolved = resolve(canonical, asOf, dataWatermark);
        boolean bounded = canonical.kind() != CanonicalWindowKind.LIFETIME;
        return new ResolvedWindowBinding.RelationWindow(
                path, canonical, resolved, bounded, false, eventTimeSemantics, eventTimeField);
    }

    private static QueryException overflow() {
        return new QueryException(QueryErrorCodes.QUERY_WINDOW_OVERFLOW, "windowArithmetic",
                "Resolving a window's concrete boundaries overflowed the representable instant range.");
    }

    private PlanWindows() {
    }
}
