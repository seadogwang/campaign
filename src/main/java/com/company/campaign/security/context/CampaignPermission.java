package com.company.campaign.security.context;

/**
 * The closed set of campaign permissions understood by the security core.
 *
 * <p>The set is exactly the seven minimum permissions from the Implementation Specification. The enum name
 * is an internal constant; {@link #wireName()} is the stable wire spelling used in authorization decisions
 * and audit reason metadata. One permission has exactly one wire spelling, so a caller cannot express
 * {@code campaign.read} in a second casing and be treated as a different authority.
 */
public enum CampaignPermission {

    CAMPAIGN_READ("campaign.read"),
    CAMPAIGN_EDIT("campaign.edit"),
    AUDIENCE_PREVIEW("audience.preview"),
    CAMPAIGN_PUBLISH("campaign.publish"),
    CAMPAIGN_OPERATE("campaign.operate"),
    METADATA_MANAGE("metadata.manage"),
    SEGMENT_EVALUATE("segment.evaluate");

    private final String wireName;

    CampaignPermission(String wireName) {
        this.wireName = wireName;
    }

    /** Stable wire spelling of this permission. */
    public String wireName() {
        return wireName;
    }

    /** Resolves a wire name, rejecting anything outside the closed set with a stable code. */
    public static CampaignPermission fromWireName(String wireName) {
        if (wireName != null) {
            for (CampaignPermission permission : values()) {
                if (permission.wireName.equals(wireName)) {
                    return permission;
                }
            }
        }
        throw new UnknownPermissionException(wireName);
    }
}