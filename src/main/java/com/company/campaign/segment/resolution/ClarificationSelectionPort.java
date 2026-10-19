package com.company.campaign.segment.resolution;

/**
 * Host boundary for selecting one option from the exact current clarification.
 *
 * <p>The port receives only options produced from the permitted Metadata snapshot. It returns an opaque
 * option id, never a definition supplied outside that option set. Returning {@code null} leaves the
 * resolution at {@code NEEDS_INPUT} without producing a DSL or SQL artifact.
 */
@FunctionalInterface
public interface ClarificationSelectionPort {

    /** Selects one offered option, or returns {@code null} when the host has no answer yet. */
    String select(Clarification clarification);
}
