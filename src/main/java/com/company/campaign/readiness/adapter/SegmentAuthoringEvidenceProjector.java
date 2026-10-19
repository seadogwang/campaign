package com.company.campaign.readiness.adapter;

import com.company.campaign.builder.authoring.SegmentAuthoringProof;
import com.company.campaign.builder.authoring.SegmentAuthoringReferencePins;
import com.company.campaign.builder.authoring.SegmentAuthoringVersions;
import com.company.campaign.readiness.EvidenceOrigin;
import com.company.campaign.readiness.EvidenceStatus;
import com.company.campaign.readiness.EvidenceSubmission;
import com.company.campaign.readiness.ReadinessHostContext;
import com.company.campaign.readiness.ReadinessReasonCodes;
import com.company.campaign.readiness.ReadinessRequirement;
import com.company.campaign.readiness.ReadinessSupport;
import com.company.campaign.readiness.RequirementEvidence;
import com.company.campaign.shared.canonical.CanonicalJson;
import com.company.campaign.shared.canonical.ContentHash;

import java.time.Instant;
import java.util.List;

/**
 * The trusted projector that turns accepted Segment Authoring Integration evidence into readiness evidence.
 *
 * <p>It accepts the accepted integration's own typed proof — the editable DSL hash, the canonical AST hash,
 * the plan hash, the preview hash and the frozen Skill hash — and binds all five into one exact evidence
 * hash. Development-only reference readiness is reported as a blocker rather than silently ignored, and an
 * absent integration is a blocker rather than an omission.
 *
 * <p>It never restates a version: the capability code and contract version come from
 * {@link SegmentAuthoringVersions}, so the readiness record cannot claim a contract version the integration
 * does not produce.
 */
public final class SegmentAuthoringEvidenceProjector {

    /** Evidence identifier suffix for the authoring integration requirement. */
    public static final String AUTHORS_SUFFIX = "-authors";

    private static final CanonicalJson CANONICAL_JSON = new CanonicalJson();

    private SegmentAuthoringEvidenceProjector() {
    }

    /**
     * The exact accepted version/hash evidence, extracted from one accepted integration proof.
     *
     * <p>{@code developmentOnly} is true when the proof's reference pins say the reference readiness is
     * development-only; a reference-free proof is not development-only.
     */
    public record AuthoringFacts(
            String capabilityCode,
            String contractVersion,
            boolean present,
            boolean developmentOnly,
            ContentHash dslHash,
            ContentHash canonicalAstHash,
            ContentHash planHash,
            ContentHash previewHash,
            ContentHash skillHash) {

        public AuthoringFacts {
            ReadinessSupport.requireId(capabilityCode, "capabilityCode");
            ReadinessSupport.requireVersion(contractVersion, "contractVersion");
        }

        /** No accepted integration evidence exists. */
        public static AuthoringFacts absent() {
            return new AuthoringFacts(
                    SegmentAuthoringVersions.CAPABILITY_CODE,
                    SegmentAuthoringVersions.CONTRACT_VERSION,
                    false, false, null, null, null, null, null);
        }

        /** Extracts the facts from one accepted integration proof and its optional reference pins. */
        public static AuthoringFacts from(SegmentAuthoringProof proof, SegmentAuthoringReferencePins referencePins) {
            if (proof == null) {
                return absent();
            }
            return new AuthoringFacts(
                    SegmentAuthoringVersions.CAPABILITY_CODE,
                    SegmentAuthoringVersions.CONTRACT_VERSION,
                    true,
                    referencePins != null && referencePins.developmentOnly(),
                    proof.dslHash(),
                    proof.canonicalAstHash(),
                    proof.planHash(),
                    proof.previewHash(),
                    proof.skillHash());
        }

        /** True when every accepted hash is present. */
        public boolean complete() {
            return dslHash != null && canonicalAstHash != null && planHash != null
                    && previewHash != null && skillHash != null;
        }

        /** The canonical hash of the exact binding this evidence proves. */
        public ContentHash factsHash() {
            return CANONICAL_JSON.hash(this);
        }
    }

    /** Projects the Segment Authoring Integration requirement. */
    public static List<RequirementEvidence> project(
            ReadinessHostContext context,
            String evidenceIdPrefix,
            AuthoringFacts facts,
            Instant observedAt,
            Instant expiresAt) {
        ReadinessHostContext host = ReadinessSupport.requireNonNull(context, "context");
        AuthoringFacts value = ReadinessSupport.requireNonNull(facts, "facts");
        String prefix = ReadinessSupport.requireId(evidenceIdPrefix, "evidenceIdPrefix");

        String reasonCode;
        if (!value.present()) {
            reasonCode = ReadinessReasonCodes.AUTHORING_INTEGRATION_ABSENT;
        } else if (value.developmentOnly()) {
            reasonCode = ReadinessReasonCodes.AUTHORING_INTEGRATION_DEVELOPMENT_ONLY;
        } else if (!value.complete()) {
            reasonCode = ReadinessReasonCodes.AUTHORING_INTEGRATION_MISMATCH;
        } else {
            reasonCode = null;
        }
        boolean production = host.environment().production();
        EvidenceSubmission submission = new EvidenceSubmission(
                prefix + AUTHORS_SUFFIX,
                ReadinessRequirement.AI_SEGMENT_AUTHORING_INTEGRATION,
                reasonCode == null ? EvidenceStatus.SATISFIED : EvidenceStatus.BLOCKED,
                production ? EvidenceOrigin.PRODUCTION : EvidenceOrigin.REAL_LOCAL,
                value.capabilityCode(),
                value.contractVersion(),
                value.factsHash(),
                "readiness/segment-authoring-integration",
                production,
                observedAt,
                expiresAt,
                reasonCode);
        return List.of(RequirementEvidence.of(host.environment(), submission));
    }
}