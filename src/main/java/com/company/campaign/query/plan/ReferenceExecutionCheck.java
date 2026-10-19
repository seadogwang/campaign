package com.company.campaign.query.plan;

import com.company.campaign.query.QueryErrorCodes;
import com.company.campaign.query.QueryException;
import com.company.campaign.security.context.TrustedExecutionContext;
import com.company.campaign.segment.reference.ReferenceExecutionContext;

/**
 * Everything one execution-time reference re-check needs, bundled so a caller cannot supply half of it.
 *
 * <p>An execution guard needs three things and nothing else: the guard itself, the trusted execution context
 * the authorization decision is derived from, and the host-owned reference ports the frozen evidence is
 * re-proved against. Passing them as one value means an execution path either holds a complete, host-built
 * check or holds nothing — there is no representable state where a caller supplied a guard but no ports and
 * silently skipped the re-check.
 *
 * <p>It is created by the host and handed to an execution boundary. It is never reachable from a request, a
 * DSL document, a canonical AST or a model payload.
 */
public record ReferenceExecutionCheck(
        ReferenceExecutionGuard guard,
        TrustedExecutionContext trustedContext,
        ReferenceExecutionContext referenceContext
) {

    public ReferenceExecutionCheck {
        if (guard == null) {
            throw invalid("guard", "A reference execution check requires its guard.");
        }
        if (trustedContext == null) {
            throw invalid("trustedContext", "A reference execution check requires the trusted execution context.");
        }
        if (referenceContext == null) {
            throw invalid("referenceContext", "A reference execution check requires the host reference ports.");
        }
    }

    /**
     * Re-proves every frozen reference dependency of one plan, or does nothing when it has none.
     *
     * @throws QueryException with {@link QueryErrorCodes#QUERY_REFERENCE_EXECUTION_DENIED} on any drift
     */
    public void requireExecutable(SemanticQueryPlan plan) {
        guard.requireExecutable(plan, trustedContext, referenceContext);
    }

    private static QueryException invalid(String invariant, String message) {
        return new QueryException(QueryErrorCodes.QUERY_INPUT_MISSING, invariant, message);
    }
}