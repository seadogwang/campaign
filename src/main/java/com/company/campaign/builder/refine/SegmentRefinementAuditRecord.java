package com.company.campaign.builder.refine;

import com.company.campaign.builder.agent.SegmentAuditToolCode;
import com.company.campaign.contracts.ContractPatterns;
import com.company.campaign.contracts.Disposition;
import com.company.campaign.shared.canonical.ContentHash;

import java.util.ArrayList;
import java.util.List;

/**
 * The immutable audit projection of one refinement run.
 *
 * <p>It contains only run/conversation correlation references, exact version and content hashes, dispositions,
 * state and Tool codes, a host-supplied bounded duration and redacted payload fingerprints. It never contains
 * the patch, a patch operand, the current or refined DSL, a member sample, PII, SQL, a trusted identifier or a
 * transaction row: the edit is represented only by its {@link #patchFingerprint()}, and every Tool attempt by
 * the canonical fingerprint of the payload it carried.
 */
public record SegmentRefinementAuditRecord(
        String refinementRunId,
        String conversationId,
        String skillId,
        String skillVersion,
        ContentHash skillHash,
        Disposition disposition,
        String terminalState,
        String failureCode,
        List<String> stateCodes,
        List<SegmentAuditToolCode> toolCodes,
        ContentHash metadataSnapshotHash,
        ContentHash baseDslHash,
        ContentHash refinedDslHash,
        ContentHash canonicalAstHash,
        ContentHash planHash,
        ContentHash previewHash,
        ContentHash patchFingerprint,
        ContentHash resultHash,
        Long durationNanos
) {

    public SegmentRefinementAuditRecord {
        requireReference(refinementRunId, "refinementRunId");
        requireReference(conversationId, "conversationId");
        requireReference(skillId, "skillId");
        requireReference(skillVersion, "skillVersion");
        if (skillHash == null || disposition == null || terminalState == null || resultHash == null
                || patchFingerprint == null) {
            throw SegmentRefinementException.of(
                    SegmentRefinementAdapterErrorCodes.REQUEST_INVALID,
                    "An audit record requires its identity, result and patch fingerprint");
        }
        if (!terminalState.matches(ContractPatterns.MACHINE_CODE)) {
            throw SegmentRefinementException.of(
                    SegmentRefinementAdapterErrorCodes.REQUEST_INVALID,
                    "An audit terminal state must be a stable machine code");
        }
        if (disposition.isResolved() != (failureCode == null)) {
            throw SegmentRefinementException.of(
                    SegmentRefinementAdapterErrorCodes.REQUEST_INVALID,
                    "Only a non-resolved audit record may carry a failure code");
        }
        stateCodes = stateCodes == null ? List.of() : List.copyOf(new ArrayList<>(stateCodes));
        toolCodes = toolCodes == null ? List.of() : List.copyOf(new ArrayList<>(toolCodes));
        for (String stateCode : stateCodes) {
            if (stateCode == null || !stateCode.matches(ContractPatterns.MACHINE_CODE)) {
                throw SegmentRefinementException.of(
                        SegmentRefinementAdapterErrorCodes.REQUEST_INVALID,
                        "An audit state code must be a stable machine code");
            }
        }
        if (durationNanos != null && durationNanos < 0) {
            throw SegmentRefinementException.of(
                    SegmentRefinementAdapterErrorCodes.REQUEST_INVALID,
                    "An audit duration must not be negative");
        }
    }

    private static void requireReference(String value, String field) {
        if (value == null || !value.matches(ContractPatterns.REFERENCE_ID)) {
            throw SegmentRefinementException.of(
                    SegmentRefinementAdapterErrorCodes.REQUEST_INVALID,
                    "An audit record requires a bounded reference: " + field);
        }
    }
}