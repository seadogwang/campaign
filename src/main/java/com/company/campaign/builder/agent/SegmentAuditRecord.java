package com.company.campaign.builder.agent;

import com.company.campaign.contracts.ContractPatterns;
import com.company.campaign.contracts.Disposition;
import com.company.campaign.shared.canonical.ContentHash;

import java.util.ArrayList;
import java.util.List;

/**
 * The immutable audit projection of one Agent run.
 *
 * <p>It contains only run/conversation correlation references, exact version and content hashes, dispositions,
 * state and Tool codes, a host-supplied bounded duration and redacted payload fingerprints. It never contains
 * raw provider output, the unrestricted utterance, the full DSL, member samples, PII, credentials, trusted
 * identifiers or transaction rows.
 */
public record SegmentAuditRecord(
        String agentRunId,
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
        ContentHash intentHash,
        ContentHash dslHash,
        ContentHash canonicalAstHash,
        ContentHash planHash,
        ContentHash previewHash,
        ContentHash utteranceFingerprint,
        ContentHash resultHash,
        Long durationNanos
) {

    public SegmentAuditRecord {
        requireReference(agentRunId, "agentRunId");
        requireReference(conversationId, "conversationId");
        requireReference(skillId, "skillId");
        requireReference(skillVersion, "skillVersion");
        if (skillHash == null || disposition == null || terminalState == null || resultHash == null) {
            throw SegmentAgentException.invalid(
                    SegmentAgentErrorCodes.REQUEST_INVALID, "An audit record requires its identity and result");
        }
        if (!terminalState.matches(ContractPatterns.MACHINE_CODE)) {
            throw SegmentAgentException.invalid(
                    SegmentAgentErrorCodes.REQUEST_INVALID, "An audit terminal state must be a stable machine code");
        }
        if (disposition.isResolved() != (failureCode == null)) {
            throw SegmentAgentException.invalid(
                    SegmentAgentErrorCodes.REQUEST_INVALID, "Only a non-resolved audit record may carry a failure code");
        }
        stateCodes = stateCodes == null ? List.of() : List.copyOf(new ArrayList<>(stateCodes));
        toolCodes = toolCodes == null ? List.of() : List.copyOf(new ArrayList<>(toolCodes));
        for (String stateCode : stateCodes) {
            if (stateCode == null || !stateCode.matches(ContractPatterns.MACHINE_CODE)) {
                throw SegmentAgentException.invalid(
                        SegmentAgentErrorCodes.REQUEST_INVALID, "An audit state code must be a stable machine code");
            }
        }
        if (durationNanos != null && durationNanos < 0) {
            throw SegmentAgentException.invalid(
                    SegmentAgentErrorCodes.REQUEST_INVALID, "An audit duration must not be negative");
        }
    }

    private static void requireReference(String value, String field) {
        if (value == null || !value.matches(ContractPatterns.REFERENCE_ID)) {
            throw SegmentAgentException.invalid(
                    SegmentAgentErrorCodes.REQUEST_INVALID, "An audit record requires a bounded reference: " + field);
        }
    }
}