package com.company.campaign.builder.campaignagent;

/**
 * Whether the host permits at most one schema-only repair attempt of a rejected routing output.
 *
 * <p>The default is {@link #NONE}: a rejected output fails the turn closed unless the host explicitly opted
 * in. A repair can only re-ask the same bounded request; it can never widen a limit, relax a rule or reuse
 * the rejected bytes as authority.
 */
public enum CampaignSchemaRepairPolicy {

    /** A rejected routing output is final; no second attempt is made. */
    NONE,

    /** The host permits exactly one further attempt after a schema rejection. */
    ONE_REPAIR;

    /** True only when the host explicitly enabled the single repair attempt. */
    public boolean permitsOneRepair() {
        return this == ONE_REPAIR;
    }
}