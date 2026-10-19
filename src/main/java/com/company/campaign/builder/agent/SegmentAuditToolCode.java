package com.company.campaign.builder.agent;

import com.company.campaign.contracts.ContractPatterns;
import com.company.campaign.shared.canonical.ContentHash;

/**
 * One audit-safe Tool attempt in an audit projection.
 *
 * <p>It carries only the bounded Tool identifier, the stable bounded code of the attempt and the redacted
 * canonical fingerprint of the exact payload the attempt carried. No payload value, no outcome value, no raw
 * call identifier and no restricted fact is representable: the payload is represented by its canonical hash,
 * never by a hash of the call identifier alone.
 */
public record SegmentAuditToolCode(String toolId, String code, ContentHash inputFingerprint) {

    public SegmentAuditToolCode {
        if (toolId == null || !toolId.matches(ContractPatterns.REFERENCE_ID)) {
            throw SegmentAgentException.invalid(
                    SegmentAgentErrorCodes.REQUEST_INVALID, "An audit Tool code requires a bounded Tool identifier");
        }
        if (code != null && !code.matches(ContractPatterns.MACHINE_CODE)) {
            throw SegmentAgentException.invalid(
                    SegmentAgentErrorCodes.REQUEST_INVALID, "An audit Tool code must be a stable machine code");
        }
        if (inputFingerprint == null) {
            throw SegmentAgentException.invalid(
                    SegmentAgentErrorCodes.REQUEST_INVALID,
                    "An audit Tool attempt requires its redacted payload fingerprint");
        }
    }
}