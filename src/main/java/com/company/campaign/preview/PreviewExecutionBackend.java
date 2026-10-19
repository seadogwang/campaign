package com.company.campaign.preview;

import com.company.campaign.query.plan.SemanticQueryPlan;
import com.company.campaign.security.context.TrustedExecutionContext;

/**
 * The one narrow read-only execution port of the Preview boundary.
 *
 * <p>It has exactly two operations: one immutable server-owned {@link PreviewBackendIdentity} and one
 * execution that accepts only a genuine T08 {@link SemanticQueryPlan} plus the host-owned
 * {@link TrustedExecutionContext} needed to verify it. It has no mutation, transaction, publish, save,
 * provider or model method and no request-controlled backend selector, so a Preview request cannot choose,
 * replace or reconfigure its backend.
 *
 * <p>A T09B compiled SQL artifact is a future database adapter input; the Preview controller never executes
 * SQL. The T09A fixture adapter is a test/local evaluation implementation of this port.
 */
public interface PreviewExecutionBackend {

    /**
     * The one immutable server-owned identity of this backend, captured and re-proved against every result it
     * produces. It is never derived from a request and never changes for the lifetime of the backend.
     */
    PreviewBackendIdentity identity();

    /** Executes one genuine plan under the trusted context and returns a coherent pinned result. */
    PreviewBackendResult execute(SemanticQueryPlan plan, TrustedExecutionContext context);
}