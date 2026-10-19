package com.company.campaign.builder.tool;

import com.company.campaign.contracts.ContractPatterns;
import com.company.campaign.contracts.Disposition;
import com.company.campaign.shared.canonical.ContentHash;

/**
 * One immutable audit-safe record of a Tool attempt, whether it was admitted or refused.
 *
 * <p>It carries the bounded Tool identifier the caller named, the server-owned rank of that Tool when the
 * catalog knows it, the bounded disposition/code and the redacted canonical fingerprint of the exact payload
 * the attempt carried. It never carries the raw model-supplied call identifier, the payload itself, the
 * outcome value or any restricted fact: the payload is represented only by its canonical hash.
 */
public record SegmentToolCallRecord(
        String toolId,
        int rank,
        Disposition disposition,
        String code,
        ContentHash inputFingerprint
) {

    /** The rank recorded for an attempt that named a Tool the exact Skill does not order. */
    public static final int UNKNOWN_RANK = -1;

    public SegmentToolCallRecord {
        if (toolId == null || !toolId.matches(ContractPatterns.REFERENCE_ID)) {
            throw SegmentToolException.invalid(
                    SegmentToolErrorCodes.TOOL_RESULT_INCOHERENT,
                    "A Tool call record requires a bounded Tool identifier");
        }
        if (disposition == null || inputFingerprint == null) {
            throw SegmentToolException.invalid(
                    SegmentToolErrorCodes.TOOL_RESULT_INCOHERENT, "A Tool call record requires its identity");
        }
        if (disposition.isResolved() == (code != null)) {
            throw SegmentToolException.invalid(
                    SegmentToolErrorCodes.TOOL_RESULT_INCOHERENT,
                    "Only a non-resolved Tool call record may carry a code");
        }
    }
}