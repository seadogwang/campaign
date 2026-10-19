package com.company.campaign.builder.tool;

import com.company.campaign.builder.agent.GroundedExplanation;
import com.company.campaign.preview.PreviewResponse;
import com.company.campaign.query.plan.SemanticQueryPlan;
import com.company.campaign.builder.tool.reference.ReferenceDiscoveryToolOutput;
import com.company.campaign.builder.tool.reference.ReferenceResolutionToolOutput;
import com.company.campaign.segment.resolution.ResolutionResult;
import com.company.campaign.segment.validation.SegmentValidationResult;
import com.company.campaign.shared.canonical.ContentHash;

import java.util.ArrayList;
import java.util.List;

/**
 * The closed, host-private typed result union an admitted Tool hands back to the Agent.
 *
 * <p>The Agent composes the accepted T07/T08/T09C/T10 components <em>inside</em> the Tool boundary, so the
 * genuine artifact of a protected operation is produced by the admitted handler rather than before the call.
 * The artifact travels beside the model-visible {@link SegmentToolOutcome} and is never part of it: the public
 * envelope stays bounded, redacted and hash-based, while this union carries the typed value the Agent needs.
 *
 * <p>The union is sealed, so a consumer switches over the closed set of variants instead of casting. There is
 * no {@code Object} payload, no map, no reflection and no callback, so an artifact cannot be forged from a
 * model payload. Every admission additionally binds the artifact to the exact call identifier, Tool
 * identifier, Skill/catalog hash and public outcome hash — see {@link SegmentToolAdmission}.
 */
public sealed interface SegmentToolArtifact {

    /** The one server-owned Tool identifier that produced this artifact. */
    String toolId();

    /** The content hash the public Tool outcome must publish for this artifact. */
    ContentHash publicEvidenceHash();

    /**
     * The authorized Metadata discovery projection: exactly the stable definition identifiers the run is
     * permitted to use, as decided by the T02 authorization boundary before any projection reaches the model.
     */
    record MetadataDiscovery(List<String> definitionIds, ContentHash snapshotHash) implements SegmentToolArtifact {

        public MetadataDiscovery {
            definitionIds = definitionIds == null ? List.of() : List.copyOf(new ArrayList<>(definitionIds));
            if (snapshotHash == null) {
                throw SegmentToolException.invalid(
                        SegmentToolErrorCodes.TOOL_RESULT_INCOHERENT,
                        "A Metadata discovery artifact requires the pinned snapshot hash");
            }
        }

        @Override
        public String toolId() {
            return SegmentToolIds.METADATA_DISCOVER;
        }

        @Override
        public ContentHash publicEvidenceHash() {
            return snapshotHash;
        }
    }

    /** The genuine T10 deterministic resolution of this run. */
    record Resolution(ResolutionResult resolution) implements SegmentToolArtifact {

        public Resolution {
            if (resolution == null) {
                throw SegmentToolException.invalid(
                        SegmentToolErrorCodes.TOOL_RESULT_INCOHERENT,
                        "A resolution artifact requires the accepted resolution");
            }
        }

        @Override
        public String toolId() {
            return SegmentToolIds.INTENT_RESOLVE;
        }

        @Override
        public ContentHash publicEvidenceHash() {
            return resolution.baseIntentHash();
        }
    }

    /** The genuine T07 validation and canonicalization of the host-owned document. */
    record Validation(SegmentValidationResult validation) implements SegmentToolArtifact {

        public Validation {
            if (validation == null) {
                throw SegmentToolException.invalid(
                        SegmentToolErrorCodes.TOOL_RESULT_INCOHERENT,
                        "A validation artifact requires the accepted validation result");
            }
        }

        @Override
        public String toolId() {
            return SegmentToolIds.DSL_VALIDATE;
        }

        @Override
        public ContentHash publicEvidenceHash() {
            return validation.canonicalAstHash();
        }
    }

    /** The genuine T08 semantic query plan of the validated document. */
    record Plan(SemanticQueryPlan plan) implements SegmentToolArtifact {

        public Plan {
            if (plan == null) {
                throw SegmentToolException.invalid(
                        SegmentToolErrorCodes.TOOL_RESULT_INCOHERENT,
                        "A plan artifact requires the accepted query plan");
            }
        }

        @Override
        public String toolId() {
            return SegmentToolIds.PLAN_BUILD;
        }

        @Override
        public ContentHash publicEvidenceHash() {
            return plan.planHash();
        }
    }

    /** The genuine T09C Safe Preview response, already checked to carry no member sample. */
    record Preview(PreviewResponse preview) implements SegmentToolArtifact {

        public Preview {
            if (preview == null) {
                throw SegmentToolException.invalid(
                        SegmentToolErrorCodes.TOOL_RESULT_INCOHERENT,
                        "A Preview artifact requires the accepted Preview response");
            }
        }

        @Override
        public String toolId() {
            return SegmentToolIds.PREVIEW_SAFE;
        }

        @Override
        public ContentHash publicEvidenceHash() {
            return preview.contentHash();
        }
    }

    /** The grounded explanation built only from the accepted resolution, document and Preview card. */
    record Explanation(GroundedExplanation explanation) implements SegmentToolArtifact {

        public Explanation {
            if (explanation == null) {
                throw SegmentToolException.invalid(
                        SegmentToolErrorCodes.TOOL_RESULT_INCOHERENT,
                        "An explanation artifact requires the accepted grounded explanation");
            }
        }

        @Override
        public String toolId() {
            return SegmentToolIds.EXPLANATION_GROUNDED;
        }

        @Override
        public ContentHash publicEvidenceHash() {
            return explanation.explanationHash();
        }
    }

    /**
     * The bounded, non-disclosing answer of an Existing Audience Reference read-only Tool.
     *
     * <p>It carries exactly one of the two registered answer shapes — the discovery summary list or the one
     * deterministic resolution outcome — never both, and never a written, retained, released, deleted or
     * published Audience Version, because no such Tool exists to produce one.
     */
    record ReferencedAudiences(
            String referenceToolId,
            ReferenceDiscoveryToolOutput discovery,
            ReferenceResolutionToolOutput resolution,
            ContentHash evidenceHash) implements SegmentToolArtifact {

        public ReferencedAudiences {
            if (!SegmentToolIds.AUDIENCE_REFERENCE_DISCOVER.equals(referenceToolId)
                    && !SegmentToolIds.AUDIENCE_REFERENCE_RESOLVE.equals(referenceToolId)) {
                throw SegmentToolException.invalid(
                        SegmentToolErrorCodes.TOOL_RESULT_INCOHERENT,
                        "A reference artifact must name one of the registered read-only reference Tools");
            }
            if (evidenceHash == null
                    || (discovery == null) == (resolution == null)
                    || (discovery != null && !SegmentToolIds.AUDIENCE_REFERENCE_DISCOVER.equals(referenceToolId))
                    || (resolution != null && !SegmentToolIds.AUDIENCE_REFERENCE_RESOLVE.equals(referenceToolId))) {
                throw SegmentToolException.invalid(
                        SegmentToolErrorCodes.TOOL_RESULT_INCOHERENT,
                        "A reference artifact carries exactly the one answer shape its Tool produces");
            }
        }

        @Override
        public String toolId() {
            return referenceToolId;
        }

        @Override
        public ContentHash publicEvidenceHash() {
            return evidenceHash;
        }
    }
}