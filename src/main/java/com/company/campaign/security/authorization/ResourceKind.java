package com.company.campaign.security.authorization;

import com.company.campaign.security.context.CampaignPermission;

/**
 * The closed set of resource kinds the security core can authorize.
 *
 * <p>Each kind owns the permission a principal must hold to reach it. The mapping is fixed here rather than
 * supplied by the caller, so a request cannot lower its own bar by claiming a weaker required permission.
 * Attribute, Metric and Relation are Metadata kinds and can be denied independently by the DataScope
 * policy; a Preview Sample additionally requires an approved DataScope.
 *
 * <p>{@link #CAMPAIGN} is the one kind T15 adds: authoring a Campaign Draft is a Campaign-level operation,
 * so it requires {@code campaign.edit} and is authorized exactly once per compile against the server-resolved
 * Campaign or Create resource. It is appended rather than inserted so existing ordinals never shift.
 *
 * <p>{@link #CAMPAIGN_PUBLISH} is the one kind T17 adds: publishing a saved Draft is an independently
 * authorized operation, so it requires {@code campaign.publish} and is authorized again on the explicit
 * publish confirmation. Preparing a publication is deliberately not authority to publish later, so this is
 * a separate decision rather than a second use of {@code campaign.edit}. It is appended after
 * {@link #CAMPAIGN} so every existing ordinal — and therefore every existing behaviour — is preserved.
 */
public enum ResourceKind {

    ATTRIBUTE,
    METRIC,
    RELATION,
    SEGMENT,
    PREVIEW_SAMPLE,
    CAMPAIGN,
    CAMPAIGN_PUBLISH;

    /** The permission required to access this kind of resource. */
    public CampaignPermission requiredPermission() {
        return switch (this) {
            case ATTRIBUTE, METRIC, RELATION -> CampaignPermission.CAMPAIGN_READ;
            case SEGMENT -> CampaignPermission.SEGMENT_EVALUATE;
            case PREVIEW_SAMPLE -> CampaignPermission.AUDIENCE_PREVIEW;
            case CAMPAIGN -> CampaignPermission.CAMPAIGN_EDIT;
            case CAMPAIGN_PUBLISH -> CampaignPermission.CAMPAIGN_PUBLISH;
        };
    }

    /** True for the Metadata kinds, which may be denied one kind at a time. */
    public boolean isMetadata() {
        return this == ATTRIBUTE || this == METRIC || this == RELATION;
    }
}