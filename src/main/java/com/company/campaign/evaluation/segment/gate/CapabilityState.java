package com.company.campaign.evaluation.segment.gate;

/**
 * The readiness state of one Gate-gated capability.
 *
 * <p>Only a latest approved {@code PASSED} Gate decision for exactly the current gated pins may be
 * {@link #ACTIVE}. Every other condition keeps the capability {@link #EXPERIMENTAL}, and any change to a
 * gated input moves it to {@link #EVALUATION_REQUIRED} until a new reviewed run passes.
 */
public enum CapabilityState {

    /** The latest approved Gate decision is {@code PASSED} and every gated input is unchanged. */
    ACTIVE,

    /** The capability is usable for development only: no approved {@code PASSED} decision covers it. */
    EXPERIMENTAL,

    /** A gated input changed since the latest approved {@code PASSED} decision, so a new evaluation is due. */
    EVALUATION_REQUIRED
}