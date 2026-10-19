package com.company.campaign.lifecycle;

import com.company.campaign.evaluation.segment.gate.SegmentReadinessProjection;

import java.util.Optional;

/**
 * Host-only port that loads the latest exact production readiness of one Gate-gated capability.
 *
 * <p>Readiness and Gate evidence are never accepted on a public command, a Tool payload or a model output:
 * a production path asks this port for the host-owned T14 projection and then requires an exact match.
 *
 * <p>It is deliberately <em>not</em> consumed by Apply. Saving an editable Draft is a development and local
 * authoring operation, and the accepted T16 rule is that development authoring stays available while the
 * Segment capability is non-active. A later publish phase consumes this port through the accepted T16
 * {@code SegmentActivationGuard} and fails closed unless the exact Segment Plan card matches the latest
 * host-owned {@code ACTIVE}, {@code aiReady} projection and its approved pins.
 *
 * <p>A port that returns an empty result, or that cannot answer, means "no production-ready evidence" — it
 * never means "assume active".
 */
@FunctionalInterface
public interface SegmentReadinessLookupPort {

    Optional<SegmentReadinessProjection> currentReadiness(String capabilityId);
}