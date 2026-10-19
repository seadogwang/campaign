package com.company.campaign.builder.agent;

import com.company.campaign.builder.segment.DevelopmentEvidence;
import com.company.campaign.builder.tool.SegmentToolCallRecord;
import com.company.campaign.contracts.Disposition;
import com.company.campaign.segment.resolution.Clarification;
import com.company.campaign.shared.canonical.CanonicalJson;
import com.company.campaign.shared.canonical.ContentHash;

import java.util.ArrayList;
import java.util.List;

/**
 * The immutable, redacted {@code DEVELOPMENT_ONLY} result of one formal Agent run.
 *
 * <p>On success it carries the exact state trace, the bounded Tool trace, the safe Preview card, the grounded
 * explanation and the immutable hashes and pins (Skill, Metadata, Intent, DSL, canonical AST, plan and
 * Preview). On a non-resolved outcome it carries exactly one bounded failure code and, for {@code NEEDS_INPUT},
 * the genuine T10 clarification — and no DSL, plan, Preview or explanation artifact.
 *
 * <p>It never carries the provider output, the trusted context, a tenant/user/DataScope value, a raw member id,
 * a member sample, hidden Metadata, SQL, a storage fact or a credential. It can never be a Gate decision, a
 * Save, a Publish or Runtime evidence, and it is never named {@code SAVED}, {@code APPLIED}, {@code PUBLISHED},
 * {@code ACTIVE}, {@code AI_READY} or {@code LIVE_READY}.
 */
public record SegmentAgentResult(
        String evidenceClass,
        String skillId,
        String skillVersion,
        ContentHash skillHash,
        Disposition disposition,
        SegmentAgentState terminalState,
        String failureCode,
        Clarification clarification,
        ContentHash metadataSnapshotHash,
        ContentHash intentHash,
        ContentHash dslEditableHash,
        ContentHash canonicalAstHash,
        ContentHash planHash,
        ContentHash previewHash,
        List<AgentTransition> stateTrace,
        List<SegmentToolCallRecord> toolTrace,
        SegmentPreviewCard previewCard,
        GroundedExplanation explanation,
        ContentHash resultHash
) {

    private static final CanonicalJson CANONICAL_JSON = new CanonicalJson();

    public SegmentAgentResult {
        if (!DevelopmentEvidence.DEVELOPMENT_ONLY.equals(evidenceClass)) {
            throw SegmentAgentException.invalid(
                    SegmentAgentErrorCodes.REQUEST_INVALID, "Only a DEVELOPMENT_ONLY result may be produced");
        }
        if (skillId == null || skillVersion == null || skillHash == null || disposition == null
                || terminalState == null) {
            throw SegmentAgentException.invalid(
                    SegmentAgentErrorCodes.REQUEST_INVALID, "A result requires its Skill identity and disposition");
        }
        if (!terminalState.isTerminal()) {
            throw SegmentAgentException.invalid(
                    SegmentAgentErrorCodes.REQUEST_INVALID, "A result requires a terminal Agent state");
        }
        if (!terminalStateFor(disposition).equals(terminalState)) {
            throw SegmentAgentException.invalid(
                    SegmentAgentErrorCodes.REQUEST_INVALID, "A result terminal state must match its disposition");
        }
        if (disposition.isResolved() != (failureCode == null)) {
            throw SegmentAgentException.invalid(
                    SegmentAgentErrorCodes.REQUEST_INVALID, "Only a non-resolved result may carry a failure code");
        }
        if (disposition.isResolved()) {
            if (previewCard == null || explanation == null || clarification != null) {
                throw SegmentAgentException.invalid(
                        SegmentAgentErrorCodes.REQUEST_INVALID,
                        "A ready result requires a Preview card and explanation and no clarification");
            }
        } else if (previewCard != null || explanation != null || dslEditableHash != null
                || canonicalAstHash != null || planHash != null || previewHash != null) {
            throw SegmentAgentException.invalid(
                    SegmentAgentErrorCodes.REQUEST_INVALID,
                    "A non-resolved result must not carry a later-stage artifact");
        }
        if (Disposition.NEEDS_INPUT == disposition && clarification == null) {
            throw SegmentAgentException.invalid(
                    SegmentAgentErrorCodes.REQUEST_INVALID, "A NEEDS_INPUT result requires the genuine clarification");
        }
        if (Disposition.NEEDS_INPUT != disposition && clarification != null) {
            throw SegmentAgentException.invalid(
                    SegmentAgentErrorCodes.REQUEST_INVALID, "Only a NEEDS_INPUT result may carry a clarification");
        }
        stateTrace = stateTrace == null ? List.of() : List.copyOf(new ArrayList<>(stateTrace));
        toolTrace = toolTrace == null ? List.of() : List.copyOf(new ArrayList<>(toolTrace));
        ContentHash expected = CANONICAL_JSON.hash(new Material(
                evidenceClass, skillId, skillVersion, skillHash, disposition, terminalState, failureCode,
                clarification, metadataSnapshotHash, intentHash, dslEditableHash, canonicalAstHash, planHash,
                previewHash, stateTrace, toolTrace, previewCard, explanation));
        if (resultHash == null || !resultHash.equals(expected)) {
            throw SegmentAgentException.invalid(
                    SegmentAgentErrorCodes.REQUEST_INVALID, "A result hash must be the canonical hash of its evidence");
        }
    }

    /** Publishes a result, computing its composite canonical hash from the exact evidence. */
    public static SegmentAgentResult publish(
            String skillId,
            String skillVersion,
            ContentHash skillHash,
            Disposition disposition,
            SegmentAgentState terminalState,
            String failureCode,
            Clarification clarification,
            ContentHash metadataSnapshotHash,
            ContentHash intentHash,
            ContentHash dslEditableHash,
            ContentHash canonicalAstHash,
            ContentHash planHash,
            ContentHash previewHash,
            List<AgentTransition> stateTrace,
            List<SegmentToolCallRecord> toolTrace,
            SegmentPreviewCard previewCard,
            GroundedExplanation explanation) {
        ContentHash hash = CANONICAL_JSON.hash(new Material(
                DevelopmentEvidence.DEVELOPMENT_ONLY, skillId, skillVersion, skillHash, disposition, terminalState,
                failureCode, clarification, metadataSnapshotHash, intentHash, dslEditableHash, canonicalAstHash,
                planHash, previewHash, stateTrace, toolTrace, previewCard, explanation));
        return new SegmentAgentResult(
                DevelopmentEvidence.DEVELOPMENT_ONLY, skillId, skillVersion, skillHash, disposition, terminalState,
                failureCode, clarification, metadataSnapshotHash, intentHash, dslEditableHash, canonicalAstHash,
                planHash, previewHash, stateTrace, toolTrace, previewCard, explanation, hash);
    }

    /** True always: this adapter is development-only and never production evidence. */
    public boolean developmentOnly() {
        return true;
    }

    /** True only when the run reached {@code READY_FOR_PREVIEW}. */
    public boolean isReadyForPreview() {
        return Disposition.RESOLVED == disposition;
    }

    /** The exact canonical bytes of every projected fact; deterministic for identical evidence. */
    public byte[] canonicalBytes() {
        return CANONICAL_JSON.write(new Material(
                evidenceClass, skillId, skillVersion, skillHash, disposition, terminalState, failureCode,
                clarification, metadataSnapshotHash, intentHash, dslEditableHash, canonicalAstHash, planHash,
                previewHash, stateTrace, toolTrace, previewCard, explanation));
    }

    /** The composite content hash covering every projected fact. */
    public ContentHash contentHash() {
        return resultHash;
    }

    private static SegmentAgentState terminalStateFor(Disposition disposition) {
        return switch (disposition) {
            case RESOLVED -> SegmentAgentState.READY_FOR_PREVIEW;
            case NEEDS_INPUT -> SegmentAgentState.NEEDS_INPUT;
            case UNSUPPORTED -> SegmentAgentState.UNSUPPORTED;
            case DENIED -> SegmentAgentState.DENIED;
            case INVALID -> SegmentAgentState.INVALID;
        };
    }

    /** The exact material the composite result hash covers, excluding the hash field itself. */
    private record Material(
            String evidenceClass,
            String skillId,
            String skillVersion,
            ContentHash skillHash,
            Disposition disposition,
            SegmentAgentState terminalState,
            String failureCode,
            Clarification clarification,
            ContentHash metadataSnapshotHash,
            ContentHash intentHash,
            ContentHash dslEditableHash,
            ContentHash canonicalAstHash,
            ContentHash planHash,
            ContentHash previewHash,
            List<AgentTransition> stateTrace,
            List<SegmentToolCallRecord> toolTrace,
            SegmentPreviewCard previewCard,
            GroundedExplanation explanation) {

        private Material {
            stateTrace = stateTrace == null ? null : List.copyOf(stateTrace);
            toolTrace = toolTrace == null ? null : List.copyOf(toolTrace);
        }
    }
}