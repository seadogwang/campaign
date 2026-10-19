package com.company.campaign.dev.preview.postgres;

import com.company.campaign.security.context.TrustedExecutionContext;

import java.time.Instant;

/**
 * The host-owned authoritative data watermark of one Preview execution.
 *
 * <p>It has exactly one operation and it accepts no request value: the host resolves its own watermark from the
 * trusted execution context it already holds. The adapter re-proves the resolved instant against the trusted
 * plan pin before a single statement is prepared, so a missing, incoherent or stale watermark fails closed
 * instead of being silently replaced by the plan's own value.
 *
 * <p>It is deliberately separate from the trusted context: the context states which watermark was trusted when
 * the plan was frozen, while this port states which watermark the host can actually vouch for right now. The
 * two must agree, and the agreement is what a Preview result publishes.
 */
@FunctionalInterface
public interface PreviewDataWatermarkProvider {

    /** The host-owned watermark for one trusted execution context, or {@code null} when none is available. */
    Instant dataWatermark(TrustedExecutionContext context);
}