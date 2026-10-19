package com.company.campaign.builder.tool;

import java.util.Map;

/**
 * The server-owned Tool schema version of every permitted Tool identifier.
 *
 * <p>A Skill must pin each allowed Tool at exactly this version. A different version is refused rather than
 * partially understood, because a Tool schema this build does not implement cannot be executed safely. The
 * mapping is immutable and closed: a Skill cannot introduce a Tool or a version.
 */
public final class SegmentToolVersions {

    /** The one accepted schema version for the current T11 Tool surface. */
    public static final String CURRENT_TOOL_SCHEMA = "segment-tool-schema/1.0";

    private static final Map<String, String> VERSIONS = Map.of(
            SegmentToolIds.METADATA_DISCOVER, "1.0",
            SegmentToolIds.METADATA_DETAIL, "1.0",
            SegmentToolIds.INTENT_RESOLVE, "1.0",
            SegmentToolIds.DSL_VALIDATE, "1.0",
            SegmentToolIds.PLAN_BUILD, "1.0",
            SegmentToolIds.PREVIEW_SAFE, "1.0",
            SegmentToolIds.EXPLANATION_GROUNDED, "1.0",
            SegmentToolIds.AUDIENCE_REFERENCE_DISCOVER, "1.0",
            SegmentToolIds.AUDIENCE_REFERENCE_RESOLVE, "1.0");

    private SegmentToolVersions() {
    }

    /** The accepted version of a Tool identifier, or null when the identifier is not server-owned. */
    public static String versionOf(String toolId) {
        return VERSIONS.get(toolId);
    }

    /** True when the identifier is server-owned at exactly the supplied version. */
    public static boolean matches(String toolId, String toolVersion) {
        return toolVersion != null && toolVersion.equals(VERSIONS.get(toolId));
    }
}