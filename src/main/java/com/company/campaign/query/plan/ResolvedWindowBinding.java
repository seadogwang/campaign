package com.company.campaign.query.plan;

import com.company.campaign.metadata.domain.EventTimeSemantics;
import com.company.campaign.metadata.domain.WindowBoundarySemantics;
import com.company.campaign.segment.canonical.CanonicalWindow;
import com.company.campaign.segment.canonical.CanonicalWindowKind;
import com.company.campaign.query.QueryErrorCodes;
import com.company.campaign.query.QueryException;

import java.util.Comparator;

/**
 * One concrete window bound to the AST node that declared it, under a deterministic structural path.
 *
 * <p>A {@code METRIC} binding carries the metric's pinned {@link WindowBoundarySemantics} and its pinned
 * event-time field and semantics, because a bounded metric window's inclusivity and event-time meaning are
 * Metadata decisions rather than something the planner may choose. A LIFETIME-only metric whose event-time
 * capability is absent is represented deliberately: its binding carries {@link EventTimeSemantics#NOT_APPLICABLE}
 * and no event-time field, rather than omitting the fact. A {@code RELATION} binding carries the accepted v1.3
 * {@code [start, end)} semantics and the relation's event-time field and semantics, so an EXISTS window can be
 * evaluated without re-reading the snapshot.
 *
 * <p>The canonical window identity is retained alongside the concrete boundaries, so a caller can see both
 * what the document declared and what the trusted time context resolved it to. The structural path is a
 * deterministic pre-order route through the canonical tree (child indices, {@code .not} and {@code .exists}
 * steps), never a runtime object identity; {@link #ORDER} is the one canonical binding order.
 */
public sealed interface ResolvedWindowBinding
        permits ResolvedWindowBinding.MetricWindow, ResolvedWindowBinding.RelationWindow {

    /** The one deterministic binding order: the structural path. */
    Comparator<ResolvedWindowBinding> ORDER = Comparator.comparing(ResolvedWindowBinding::path);

    /** The two window scopes a plan resolves. */
    enum WindowScope {
        METRIC,
        RELATION
    }

    /** The deterministic pre-order route from the predicate root to the owning node. */
    String path();

    /** The scope this window belongs to. */
    WindowScope scope();

    /** The canonical window identity the source document declared. */
    CanonicalWindow canonicalWindow();

    /** The concrete window resolved from the canonical identity and the trusted time context. */
    ResolvedWindow resolvedWindow();

    /** True when the window includes its start boundary. */
    boolean includeStart();

    /** True when the window includes its end boundary. */
    boolean includeEnd();

    /** A metric window's concrete boundaries with its pinned boundary and event-time semantics. */
    record MetricWindow(
            String path,
            CanonicalWindow canonicalWindow,
            ResolvedWindow resolvedWindow,
            boolean includeStart,
            boolean includeEnd,
            WindowBoundarySemantics boundarySemantics,
            EventTimeSemantics eventTimeSemantics,
            String eventTimeField) implements ResolvedWindowBinding {

        public MetricWindow {
            requirePath(path);
            requirePresent(canonicalWindow, "canonicalWindow");
            requirePresent(resolvedWindow, "resolvedWindow");
            requirePresent(boundarySemantics, "boundarySemantics");
            requireSameKind(canonicalWindow, resolvedWindow);
            if (canonicalWindow.kind() == CanonicalWindowKind.LIFETIME) {
                if (includeStart || includeEnd) {
                    throw invalid("metricLifetimeBoundary",
                            "A LIFETIME metric window has no boundary inclusivity.");
                }
                // The event-time capability is always represented explicitly. A LIFETIME-only metric that
                // declares none carries NOT_APPLICABLE with no field; a capable metric carries its pin.
                if (eventTimeSemantics == null) {
                    throw invalid("metricEventTime",
                            "A LIFETIME metric window must represent its event-time capability explicitly.");
                }
                if (eventTimeSemantics == EventTimeSemantics.NOT_APPLICABLE) {
                    if (eventTimeField != null) {
                        throw invalid("metricEventTime",
                                "A metric window with no applicable event time must not name an event-time field.");
                    }
                } else if (eventTimeField == null || eventTimeField.isBlank()) {
                    throw invalid("metricEventTime",
                            "A metric window with an applicable event time must name its field.");
                }
            } else {
                if (boundarySemantics == WindowBoundarySemantics.NOT_APPLICABLE) {
                    throw invalid("metricBoundedBoundary",
                            "A bounded metric window requires a pinned boundary semantics.");
                }
                if (eventTimeSemantics == null || eventTimeSemantics == EventTimeSemantics.NOT_APPLICABLE
                        || eventTimeField == null || eventTimeField.isBlank()) {
                    throw invalid("metricEventTime",
                            "A bounded metric window requires an event-time field and semantics.");
                }
                boolean expectedEnd = boundarySemantics == WindowBoundarySemantics.INCLUSIVE_BOTH;
                if (!includeStart || includeEnd != expectedEnd) {
                    throw invalid("metricBoundedBoundary",
                            "A bounded metric window's inclusivity must match its pinned boundary semantics.");
                }
            }
        }

        @Override
        public WindowScope scope() {
            return WindowScope.METRIC;
        }
    }

    /** A relation EXISTS window's concrete boundaries with the v1.3 {@code [start, end)} event-time scope. */
    record RelationWindow(
            String path,
            CanonicalWindow canonicalWindow,
            ResolvedWindow resolvedWindow,
            boolean includeStart,
            boolean includeEnd,
            EventTimeSemantics eventTimeSemantics,
            String eventTimeField) implements ResolvedWindowBinding {

        public RelationWindow {
            requirePath(path);
            requirePresent(canonicalWindow, "canonicalWindow");
            requirePresent(resolvedWindow, "resolvedWindow");
            requireSameKind(canonicalWindow, resolvedWindow);
            boolean bounded = canonicalWindow.kind() != CanonicalWindowKind.LIFETIME;
            if (bounded) {
                if (eventTimeSemantics == null || eventTimeSemantics == EventTimeSemantics.NOT_APPLICABLE
                        || eventTimeField == null || eventTimeField.isBlank()) {
                    throw invalid("relationEventTime",
                            "A bounded relation window requires an event-time field and semantics.");
                }
                if (!includeStart || includeEnd) {
                    throw invalid("relationBoundary",
                            "A bounded relation window keeps the accepted [start, end) semantics.");
                }
            } else {
                if (includeStart || includeEnd) {
                    throw invalid("relationBoundary", "A LIFETIME relation window has no boundary inclusivity.");
                }
                if ((eventTimeSemantics == null) != (eventTimeField == null)) {
                    throw invalid("relationEventTime",
                            "A relation event-time field and semantics must be declared together.");
                }
                if (eventTimeSemantics == EventTimeSemantics.NOT_APPLICABLE) {
                    throw invalid("relationEventTime", "A relation event-time semantics must be applicable.");
                }
            }
        }

        @Override
        public WindowScope scope() {
            return WindowScope.RELATION;
        }
    }

    private static void requireSameKind(CanonicalWindow canonicalWindow, ResolvedWindow resolvedWindow) {
        boolean same = switch (canonicalWindow.kind()) {
            case ROLLING -> resolvedWindow.kind() == ResolvedWindow.WindowKind.ROLLING;
            case FIXED -> resolvedWindow.kind() == ResolvedWindow.WindowKind.FIXED;
            case LIFETIME -> resolvedWindow.kind() == ResolvedWindow.WindowKind.LIFETIME;
            case RELATIVE_TO_ATTRIBUTE ->
                    resolvedWindow.kind() == ResolvedWindow.WindowKind.RELATIVE_TO_ATTRIBUTE;
            case SELECTED_EVENT -> resolvedWindow.kind() == ResolvedWindow.WindowKind.SELECTED_EVENT;
            case RELATIVE_TO_EVENT -> resolvedWindow.kind() == ResolvedWindow.WindowKind.RELATIVE_TO_EVENT;
        };
        if (!same) {
            throw invalid("windowKind", "A resolved window must keep the canonical window's kind.");
        }
    }

    private static void requirePath(String path) {
        if (path == null || path.isBlank()) {
            throw invalid("windowPath", "A resolved window binding requires a structural path.");
        }
    }

    private static void requirePresent(Object value, String invariant) {
        if (value == null) {
            throw invalid(invariant, "A resolved window binding requires a non-null input.");
        }
    }

    private static QueryException invalid(String invariant, String message) {
        return new QueryException(QueryErrorCodes.QUERY_PLAN_INCONSISTENT, invariant, message);
    }
}
