package com.company.campaign.builder.skill;

/**
 * The closed capability lifecycle states a Segment Skill may declare.
 *
 * <p>The list mirrors the v1.3 capability lifecycle. This task may only publish {@link #EXPERIMENTAL}: a
 * capability is promoted to {@link #ACTIVE} exclusively by an approved T14 Gate, which does not exist yet, so a
 * Skill resource that claims {@code ACTIVE} is refused rather than trusted.
 */
public enum SegmentCapabilityState {

    /** Proven development capability with no production activation. */
    EXPERIMENTAL,

    /** Gate-promoted capability; not reachable in this task. */
    ACTIVE,

    /** Withdrawn capability; not reachable in this task. */
    DEPRECATED
}