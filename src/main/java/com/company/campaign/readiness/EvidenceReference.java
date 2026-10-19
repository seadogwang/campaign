package com.company.campaign.readiness;

import com.company.campaign.shared.canonical.ContentHash;

/**
 * A bounded reference from an assessment to the one evidence record it accepted for a requirement.
 *
 * <p>It carries identifiers and hashes only: no evidence payload, no subject, no reference string and no
 * Provider or member value. An assessment is therefore safe to persist, log and expose as a status.
 */
public record EvidenceReference(
        String evidenceId,
        ReadinessRequirement requirement,
        EvidenceOrigin origin,
        ContentHash identityHash,
        ContentHash materialHash) {

    public EvidenceReference {
        ReadinessSupport.requireId(evidenceId, "evidenceId");
        ReadinessSupport.requireNonNull(requirement, "requirement");
        ReadinessSupport.requireNonNull(origin, "origin");
        ReadinessSupport.requireNonNull(identityHash, "identityHash");
        ReadinessSupport.requireNonNull(materialHash, "materialHash");
    }

    /** The reference to the exact record that was accepted. */
    public static EvidenceReference of(RequirementEvidence evidence) {
        RequirementEvidence value = ReadinessSupport.requireNonNull(evidence, "evidence");
        return new EvidenceReference(
                value.evidenceId(),
                value.requirement(),
                value.origin(),
                value.identityHash(),
                value.materialHash());
    }

    /** Package-private hash material. */
    ReferenceMaterial material() {
        return new ReferenceMaterial(
                evidenceId, requirement.code(), origin.name(), identityHash, materialHash);
    }

    /** Package-private canonical hash material for one accepted evidence reference. */
    record ReferenceMaterial(
            String evidenceId,
            String requirementCode,
            String origin,
            ContentHash identityHash,
            ContentHash materialHash) {
    }
}