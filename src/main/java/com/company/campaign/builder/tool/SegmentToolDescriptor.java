package com.company.campaign.builder.tool;

import com.company.campaign.contracts.ContractPatterns;

/**
 * One server-owned Tool schema pin: its stable identifier, its version, its deterministic rank and its bounds.
 *
 * <p>The rank is the Skill's declared order. The first admitted call must be the lowest-ranked permitted Tool
 * and every later admitted call must carry a strictly greater rank, with each Tool used at most once, so tool
 * execution is deterministic from explicit input and cannot depend on call timing. A rank may be skipped: the
 * invoker requires the rank to advance, not to advance by one, because only the host orchestrator decides which
 * declared phases of the fixed flow actually run.
 */
public record SegmentToolDescriptor(
        String toolId,
        String toolVersion,
        int rank,
        int maxPayloadBytes,
        int maxResultItems
) {

    /** The absolute bound on stable semantic references a Tool outcome may carry. */
    public static final int MAX_RESULT_ITEMS = 512;

    public SegmentToolDescriptor {
        if (toolId == null || !toolId.matches(ContractPatterns.REFERENCE_ID)) {
            throw SegmentToolException.invalid(
                    SegmentToolErrorCodes.TOOL_UNKNOWN, "A Tool descriptor requires a bounded identifier");
        }
        if (toolVersion == null || toolVersion.isBlank()) {
            throw SegmentToolException.invalid(
                    SegmentToolErrorCodes.TOOL_UNKNOWN, "A Tool descriptor requires a version");
        }
        if (rank < 0) {
            throw SegmentToolException.invalid(
                    SegmentToolErrorCodes.TOOL_UNKNOWN, "A Tool descriptor requires a non-negative rank");
        }
        if (maxPayloadBytes < 1 || maxPayloadBytes > 262_144) {
            throw SegmentToolException.invalid(
                    SegmentToolErrorCodes.TOOL_PAYLOAD_LIMIT_EXCEEDED, "A Tool descriptor payload bound is invalid");
        }
        if (maxResultItems < 1 || maxResultItems > MAX_RESULT_ITEMS) {
            throw SegmentToolException.invalid(
                    SegmentToolErrorCodes.TOOL_OUTPUT_LIMIT_EXCEEDED, "A Tool descriptor item bound is invalid");
        }
    }
}