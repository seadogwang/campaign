package com.company.campaign.lifecycle;

import com.company.campaign.contracts.Issue;

import java.util.List;

/**
 * The deterministic host port that revalidates a resolved Manifest before it is applied.
 *
 * <p>It is the lifecycle boundary's own copy of the frozen authoring policy run: a validator receives the
 * exact final material and returns an ordered, bounded issue list. It must be a pure function of its
 * request, must not read a clock, a random source or a mutable registry, and must never return a raw
 * payload value.
 *
 * <p>The boundary fails closed on anything a trustworthy result cannot contain: a thrown exception, a null
 * list, an over-bound list, a null element, or any issue whose severity blocks processing. A port that
 * cannot answer is therefore indistinguishable from one that refuses.
 */
@FunctionalInterface
public interface ManifestValidationPort {

    List<Issue> validate(ManifestValidationRequest request);
}