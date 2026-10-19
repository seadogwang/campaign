package com.company.campaign.evaluation.capabilities.t19;

import java.util.List;

/**
 * One loaded and hash-verified T19 capability evaluation corpus: the manifest plus every fixture document it
 * pins, in the manifest's declaration order.
 */
public record T19EvaluationCorpus(
        T19EvaluationManifest manifest,
        List<T19EvaluationFixtureDocument> documents,
        T19CompositionDocument compositions) {

    public T19EvaluationCorpus {
        if (manifest == null || documents == null || compositions == null) {
            throw new T19EvaluationException(
                    T19EvaluationErrorCodes.ASSET_NON_CANONICAL,
                    "A loaded corpus requires its manifest, documents and composition document.");
        }
        documents = List.copyOf(documents);
    }

    /** The fixture document of one capability, or {@code null} when the corpus does not cover it. */
    public T19EvaluationFixtureDocument documentFor(String capabilityCode) {
        for (T19EvaluationFixtureDocument document : documents) {
            if (document.capabilityCode().equals(capabilityCode)) {
                return document;
            }
        }
        return null;
    }
}