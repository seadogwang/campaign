package com.company.campaign.builder.refine;

import com.company.campaign.builder.agent.GroundedExplanation;
import com.company.campaign.builder.agent.SegmentPreviewCard;
import com.company.campaign.builder.segment.DevelopmentEvidence;
import com.company.campaign.builder.tool.SegmentToolCallRecord;
import com.company.campaign.contracts.ContractPatterns;
import com.company.campaign.contracts.Disposition;
import com.company.campaign.segment.dsl.contract.SegmentDsl;
import com.company.campaign.shared.canonical.CanonicalJson;
import com.company.campaign.shared.canonical.ContentHash;

import java.util.ArrayList;
import java.util.List;

/**
 * The immutable, redacted {@code DEVELOPMENT_ONLY} result of one refinement run.
 *
 * <p>On success it carries the immutable typed refined DSL as the host/UI continuation artifact, the safe
 * Preview card, the optionally rebuilt grounded explanation, and the exact pins a caller needs to detect a
 * stale presentation: the base document's editable hash, the refined document's editable hash, the canonical
 * validation hash, the plan hash, the Preview hash, the Metadata snapshot hash, the Skill hash and the ordered
 * Tool allowlist, the trusted time pins the Preview ran under, and the audit-safe fingerprint of the patch that
 * produced the edit. The carried document is exactly the one the refined hash and the Preview card bind, so a
 * caller can submit a second refinement without reapplying the patch outside the accepted adapter.
 *
 * <p>The result canonical hash covers the complete refined DSL through its accepted T05 canonical hash
 * material: the material binds {@code refinedDslHash}, and the constructor proves the carried document's
 * {@code editableDslHash} is exactly that hash. The DSL is never re-serialized by a second serializer here.
 *
 * <p>On a non-resolved outcome it carries exactly one bounded failure code and no refined DSL, plan, Preview
 * or explanation artifact, so a refused run publishes nothing derived from a partial edit.
 *
 * <p>It never carries the patch, a patch operand, the provider output, the trusted context identities, a
 * tenant/program/user/DataScope value, a raw member id, a member sample, hidden Metadata, SQL, a storage fact
 * or a credential. It can never be a Gate decision, a Save, a Publish or Runtime evidence, and it is never
 * named {@code SAVED}, {@code APPLIED}, {@code PUBLISHED}, {@code ACTIVE}, {@code AI_READY} or
 * {@code LIVE_READY}.
 */
public record SegmentRefinementResult(
        String evidenceClass,
        String skillId,
        String skillVersion,
        ContentHash skillHash,
        List<String> allowedToolIds,
        Disposition disposition,
        SegmentRefinementState terminalState,
        String failureCode,
        ContentHash baseDslHash,
        ContentHash refinedDslHash,
        SegmentDsl refinedDsl,
        ContentHash canonicalAstHash,
        ContentHash planHash,
        ContentHash previewHash,
        ContentHash metadataSnapshotHash,
        String asOf,
        String timezone,
        String dataWatermark,
        ContentHash patchFingerprint,
        List<SegmentRefinementTransition> stateTrace,
        List<SegmentToolCallRecord> toolTrace,
        SegmentPreviewCard previewCard,
        GroundedExplanation explanation,
        ContentHash resultHash
) {

    private static final CanonicalJson CANONICAL_JSON = new CanonicalJson();

    public SegmentRefinementResult {
        if (!DevelopmentEvidence.DEVELOPMENT_ONLY.equals(evidenceClass)) {
            throw SegmentRefinementException.of(
                    SegmentRefinementAdapterErrorCodes.REQUEST_INVALID,
                    "Only a DEVELOPMENT_ONLY result may be produced");
        }
        if (skillId == null || skillVersion == null || skillHash == null || patchFingerprint == null
                || disposition == null || terminalState == null) {
            throw SegmentRefinementException.of(
                    SegmentRefinementAdapterErrorCodes.REQUEST_INVALID,
                    "A refinement result requires its Skill identity, patch fingerprint and disposition");
        }
        if (!terminalState.isTerminal()) {
            throw SegmentRefinementException.of(
                    SegmentRefinementAdapterErrorCodes.REQUEST_INVALID,
                    "A refinement result requires a terminal state");
        }
        if (!terminalStateFor(disposition).equals(terminalState)) {
            throw SegmentRefinementException.of(
                    SegmentRefinementAdapterErrorCodes.REQUEST_INVALID,
                    "A refinement result terminal state must match its disposition");
        }
        if (disposition.isResolved() != (failureCode == null)) {
            throw SegmentRefinementException.of(
                    SegmentRefinementAdapterErrorCodes.REQUEST_INVALID,
                    "Only a non-resolved result may carry a failure code");
        }
        if (failureCode != null && !failureCode.matches(ContractPatterns.MACHINE_CODE)) {
            throw SegmentRefinementException.of(
                    SegmentRefinementAdapterErrorCodes.REQUEST_INVALID,
                    "A refinement failure code must be a stable machine code");
        }
        allowedToolIds = allowedToolIds == null ? List.of() : List.copyOf(new ArrayList<>(allowedToolIds));
        if (disposition.isResolved()) {
            if (previewCard == null || refinedDsl == null || refinedDslHash == null || canonicalAstHash == null
                    || planHash == null || previewHash == null) {
                throw SegmentRefinementException.of(
                        SegmentRefinementAdapterErrorCodes.REQUEST_INVALID,
                        "A ready refinement requires its refined DSL, Preview card and every refined-stage pin");
            }
            // The carried continuation document is exactly the one the result hash binds: its accepted T05
            // editable hash is the refined hash, and the Preview card was produced from that same document.
            if (!refinedDsl.editableDslHash().equals(refinedDslHash)) {
                throw SegmentRefinementException.of(
                        SegmentRefinementAdapterErrorCodes.REQUEST_INVALID,
                        "A refined DSL must be the document its refined hash binds");
            }
            if (!previewCard.dslHash().equals(refinedDslHash)) {
                throw SegmentRefinementException.of(
                        SegmentRefinementAdapterErrorCodes.REQUEST_INVALID,
                        "A refined DSL must be the document the Preview card was produced from");
            }
        } else if (previewCard != null || explanation != null || refinedDsl != null || refinedDslHash != null
                || canonicalAstHash != null || planHash != null || previewHash != null) {
            throw SegmentRefinementException.of(
                    SegmentRefinementAdapterErrorCodes.REQUEST_INVALID,
                    "A non-resolved refinement must not carry a later-stage artifact");
        }
        if ((asOf == null) != (timezone == null) || (asOf == null) != (dataWatermark == null)) {
            throw SegmentRefinementException.of(
                    SegmentRefinementAdapterErrorCodes.REQUEST_INVALID,
                    "A refinement result carries either every trusted time pin or none of them");
        }
        stateTrace = stateTrace == null ? List.of() : List.copyOf(new ArrayList<>(stateTrace));
        toolTrace = toolTrace == null ? List.of() : List.copyOf(new ArrayList<>(toolTrace));
        ContentHash expected = CANONICAL_JSON.hash(new Material(
                evidenceClass, skillId, skillVersion, skillHash, allowedToolIds, disposition, terminalState,
                failureCode, baseDslHash, refinedDslHash, canonicalAstHash, planHash, previewHash,
                metadataSnapshotHash, asOf, timezone, dataWatermark, patchFingerprint, stateTrace, toolTrace,
                previewCard, explanation));
        if (resultHash == null || !resultHash.equals(expected)) {
            throw SegmentRefinementException.of(
                    SegmentRefinementAdapterErrorCodes.REQUEST_INVALID,
                    "A result hash must be the canonical hash of its evidence");
        }
    }

    /** Publishes a result, computing its composite canonical hash from the exact evidence. */
    public static SegmentRefinementResult publish(
            String skillId,
            String skillVersion,
            ContentHash skillHash,
            List<String> allowedToolIds,
            Disposition disposition,
            SegmentRefinementState terminalState,
            String failureCode,
            ContentHash baseDslHash,
            ContentHash refinedDslHash,
            SegmentDsl refinedDsl,
            ContentHash canonicalAstHash,
            ContentHash planHash,
            ContentHash previewHash,
            ContentHash metadataSnapshotHash,
            String asOf,
            String timezone,
            String dataWatermark,
            ContentHash patchFingerprint,
            List<SegmentRefinementTransition> stateTrace,
            List<SegmentToolCallRecord> toolTrace,
            SegmentPreviewCard previewCard,
            GroundedExplanation explanation) {
        ContentHash hash = CANONICAL_JSON.hash(new Material(
                DevelopmentEvidence.DEVELOPMENT_ONLY, skillId, skillVersion, skillHash, allowedToolIds, disposition,
                terminalState, failureCode, baseDslHash, refinedDslHash, canonicalAstHash, planHash, previewHash,
                metadataSnapshotHash, asOf, timezone, dataWatermark, patchFingerprint, stateTrace, toolTrace,
                previewCard, explanation));
        return new SegmentRefinementResult(
                DevelopmentEvidence.DEVELOPMENT_ONLY, skillId, skillVersion, skillHash, allowedToolIds, disposition,
                terminalState, failureCode, baseDslHash, refinedDslHash, refinedDsl, canonicalAstHash, planHash,
                previewHash, metadataSnapshotHash, asOf, timezone, dataWatermark, patchFingerprint, stateTrace,
                toolTrace, previewCard, explanation, hash);
    }

    /** True always: this orchestrator is development-only and never production evidence. */
    public boolean developmentOnly() {
        return true;
    }

    /** True only when the run reached {@code READY_FOR_PREVIEW}. */
    public boolean isReadyForPreview() {
        return Disposition.RESOLVED == disposition;
    }

    /** True when a grounded explanation was honestly rebuilt from the conversation's prior resolution. */
    public boolean hasGroundedExplanation() {
        return explanation != null;
    }

    /** The exact canonical bytes of every projected fact; deterministic for identical evidence. */
    public byte[] canonicalBytes() {
        return CANONICAL_JSON.write(material());
    }

    /** The composite content hash covering every projected fact. */
    public ContentHash contentHash() {
        return resultHash;
    }

    private Material material() {
        return new Material(
                evidenceClass, skillId, skillVersion, skillHash, allowedToolIds, disposition, terminalState,
                failureCode, baseDslHash, refinedDslHash, canonicalAstHash, planHash, previewHash,
                metadataSnapshotHash, asOf, timezone, dataWatermark, patchFingerprint, stateTrace, toolTrace,
                previewCard, explanation);
    }

    private static SegmentRefinementState terminalStateFor(Disposition disposition) {
        return switch (disposition) {
            case RESOLVED -> SegmentRefinementState.READY_FOR_PREVIEW;
            case DENIED -> SegmentRefinementState.DENIED;
            case INVALID -> SegmentRefinementState.INVALID;
            case NEEDS_INPUT, UNSUPPORTED -> throw SegmentRefinementException.of(
                    SegmentRefinementAdapterErrorCodes.REQUEST_INVALID,
                    "A refinement can only terminate as READY_FOR_PREVIEW, DENIED or INVALID");
        };
    }

    /** The exact material the composite result hash covers, excluding the hash field itself. */
    private record Material(
            String evidenceClass,
            String skillId,
            String skillVersion,
            ContentHash skillHash,
            List<String> allowedToolIds,
            Disposition disposition,
            SegmentRefinementState terminalState,
            String failureCode,
            ContentHash baseDslHash,
            ContentHash refinedDslHash,
            ContentHash canonicalAstHash,
            ContentHash planHash,
            ContentHash previewHash,
            ContentHash metadataSnapshotHash,
            String asOf,
            String timezone,
            String dataWatermark,
            ContentHash patchFingerprint,
            List<SegmentRefinementTransition> stateTrace,
            List<SegmentToolCallRecord> toolTrace,
            SegmentPreviewCard previewCard,
            GroundedExplanation explanation) {

        private Material {
            allowedToolIds = allowedToolIds == null ? null : List.copyOf(allowedToolIds);
            stateTrace = stateTrace == null ? null : List.copyOf(stateTrace);
            toolTrace = toolTrace == null ? null : List.copyOf(toolTrace);
        }
    }
}