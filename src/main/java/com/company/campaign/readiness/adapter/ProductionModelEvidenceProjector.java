package com.company.campaign.readiness.adapter;

import com.company.campaign.production.model.ProductionModelEvidence;
import com.company.campaign.production.model.ProductionModelEvidenceStatus;
import com.company.campaign.readiness.EvidenceOrigin;
import com.company.campaign.readiness.EvidenceStatus;
import com.company.campaign.readiness.EvidenceSubmission;
import com.company.campaign.readiness.ReadinessHostContext;
import com.company.campaign.readiness.ReadinessReasonCodes;
import com.company.campaign.readiness.ReadinessRequirement;
import com.company.campaign.readiness.ReadinessSupport;
import com.company.campaign.readiness.RequirementEvidence;

import java.time.Instant;

/**
 * The trusted projector that turns published production model evidence into one readiness record.
 *
 * <p>The rule it enforces is the one the task pack states: a configuration, a deterministic scripted HTTP
 * server, a fixture or an evaluation asset is never production evidence, so it can never satisfy the
 * production-only {@link ReadinessRequirement#LIVE_PRODUCTION_STRUCTURED_MODEL} requirement. Only an
 * environment-gated live verification of the <em>exact</em> configuration — the one whose status is
 * {@code VERIFIED_LIVE} — projected inside a production scope satisfies it.
 *
 * <p>Every other case becomes a bounded {@code BLOCKED} record with a closed reason code:
 * a configuration that was never verified or a scripted-only run is
 * {@link ReadinessReasonCodes#MODEL_EVIDENCE_ABSENT}; an exact configuration that was exercised live and
 * failed is {@link ReadinessReasonCodes#MODEL_LIVE_VERIFICATION_FAILED}; and a live verification projected
 * outside a production scope is {@link ReadinessReasonCodes#MODEL_EVIDENCE_NOT_PRODUCTION}. The projector
 * reads no clock, generates no identifier and consults no store: the instant and the expiry are inputs.
 */
public final class ProductionModelEvidenceProjector {

    /** Evidence identifier suffix for the production structured model requirement. */
    public static final String EVIDENCE_SUFFIX = "-production-model";

    /** The one bounded, non-sensitive evidence reference this projector emits. */
    public static final String EVIDENCE_REFERENCE = "readiness/model/production-structured-model";

    private ProductionModelEvidenceProjector() {
    }

    /** Projects one published production model evidence record onto the LIVE structured-model requirement. */
    public static RequirementEvidence project(
            ReadinessHostContext context,
            String evidenceIdPrefix,
            ProductionModelEvidence evidence,
            Instant observedAt,
            Instant expiresAt) {
        ReadinessHostContext host = ReadinessSupport.requireNonNull(context, "context");
        ProductionModelEvidence value = ReadinessSupport.requireNonNull(evidence, "evidence");
        String prefix = ReadinessSupport.requireId(evidenceIdPrefix, "evidenceIdPrefix");
        ReadinessSupport.requireInstant(observedAt, "observedAt");

        boolean productionScope = host.environment().production();
        String reasonCode;
        if (!value.liveVerified()) {
            reasonCode = value.lastLiveVerificationStatus() == ProductionModelEvidenceStatus.FAILED_LIVE
                    ? ReadinessReasonCodes.MODEL_LIVE_VERIFICATION_FAILED
                    : ReadinessReasonCodes.MODEL_EVIDENCE_ABSENT;
        } else if (!productionScope) {
            reasonCode = ReadinessReasonCodes.MODEL_EVIDENCE_NOT_PRODUCTION;
        } else {
            reasonCode = null;
        }
        boolean satisfied = reasonCode == null;
        EvidenceOrigin origin;
        if (satisfied) {
            origin = EvidenceOrigin.PRODUCTION;
        } else if (value.lastLiveVerificationStatus() == ProductionModelEvidenceStatus.NOT_VERIFIED) {
            // A configuration or scripted-server observation is a non-production substitute, never a real run.
            origin = EvidenceOrigin.FIXTURE;
        } else {
            origin = EvidenceOrigin.REAL_LOCAL;
        }
        EvidenceSubmission submission = new EvidenceSubmission(
                prefix + EVIDENCE_SUFFIX,
                ReadinessRequirement.LIVE_PRODUCTION_STRUCTURED_MODEL,
                satisfied ? EvidenceStatus.SATISFIED : EvidenceStatus.BLOCKED,
                origin,
                value.modelIdFingerprint(),
                value.contractVersion(),
                value.configurationHash(),
                EVIDENCE_REFERENCE,
                satisfied,
                observedAt,
                expiresAt,
                reasonCode);
        return RequirementEvidence.of(host.environment(), submission);
    }
}