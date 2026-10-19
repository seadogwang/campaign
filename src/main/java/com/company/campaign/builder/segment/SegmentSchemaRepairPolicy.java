package com.company.campaign.builder.segment;

/**
 * The closed host-owned policy for whether one bounded schema-only repair call may follow a first-pass
 * strict-schema rejection.
 *
 * <p>It is generic and has no provider-specific behaviour, no retry counter and no network access of its own.
 * {@link #DISABLED} is the default, so the accepted Agent behavior is unchanged for every environment that
 * does not opt in. {@link #ONE_SCHEMA_REPAIR} permits exactly one repair call, and only when the first-pass
 * response failed the strict model-output JSON/schema/codec contract; a semantic, ambiguity, unsupported,
 * denial, resolution, validation, planning, preview or fixture outcome is never repairable by construction,
 * because those outcomes are only reachable after a response has already been accepted.
 */
public enum SegmentSchemaRepairPolicy {

    /** The accepted default: one call per attempt, no repair. */
    DISABLED,

    /** At most one additional call, and only to repair a captured first-pass strict-schema rejection. */
    ONE_SCHEMA_REPAIR;

    /** True only for the policy that permits the single bounded repair call. */
    public boolean permitsOneRepair() {
        return this == ONE_SCHEMA_REPAIR;
    }
}