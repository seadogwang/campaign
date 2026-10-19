package com.company.campaign.builder.assembly;

/**
 * The closed per-Skill/component state of an ephemeral Campaign assembly.
 *
 * <p>It is deliberately derived from host facts alone — a successfully admitted Skill artifact is
 * {@link #READY}, an unobserved Skill is {@link #MISSING}, and a bounded non-resolved disposition is recorded
 * as {@link #NEEDS_INPUT} or {@link #INVALID}. No state is ever read from a model output or from saved Campaign
 * state, and no state names a saved, applied, published, active or live-ready outcome.
 */
public enum CampaignComponentState {

    /** The Skill or component has produced no accepted artifact this run. */
    MISSING,

    /** The Skill reached a genuine NEEDS_INPUT terminal and awaits host-supplied input. */
    NEEDS_INPUT,

    /** The Skill produced an accepted, READY artifact. */
    READY,

    /** The Skill reached a bounded invalid terminal. */
    INVALID;
}