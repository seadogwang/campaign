package com.company.campaign.evaluation.segment.reference;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

/**
 * The request one reference-corpus case makes, holding exactly the parts its scenario is allowed to carry.
 *
 * <p>The record is deliberately flat and closed rather than a polymorphic union, so an unknown field fails
 * at load time and a part that belongs to another scenario is reported by {@link ReferenceCorpusValidator}
 * as over-specification rather than silently ignored. That is what keeps a case's meaning independent of any
 * default: a resolution case cannot smuggle a document in, and a document case cannot smuggle an answer to a
 * question nobody asked.
 *
 * @param document            the embedded editable DSL document a document case validates, or null
 * @param references          the reference sites a document case declares, in discovery order
 * @param candidateRootVersion the version identity the edited document will become, or null when unknown
 * @param utterance           the untrusted request wording a resolution case resolves, or null
 * @param candidateTexts      the candidate texts a resolution case offers to discovery, or null
 * @param audienceSelection   the host-applied audience answer, or null when it was never asked
 * @param versionSelection    the host-applied exact-pin answer, or null when it was never asked
 * @param pins                the exact pins a membership case looks up, or null
 * @param retention           the retention program a retention case runs, or null
 * @param recheck             the drift a document case applies after a first clean resolution, or null
 */
@JsonIgnoreProperties(ignoreUnknown = false)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ReferenceCaseRequest(
        JsonNode document,
        List<ReferenceSiteSpec> references,
        ReferencePin candidateRootVersion,
        String utterance,
        List<String> candidateTexts,
        String audienceSelection,
        String versionSelection,
        List<ReferencePin> pins,
        ReferenceRetentionRequest retention,
        ReferenceRecheck recheck
) {

    public ReferenceCaseRequest {
        references = references == null ? null : List.copyOf(new ArrayList<>(references));
        candidateTexts = candidateTexts == null ? null : List.copyOf(new ArrayList<>(candidateTexts));
        pins = pins == null ? null : List.copyOf(new ArrayList<>(pins));
    }

    /** True when a document, its declared sites or its root-version identity is present. */
    public boolean declaresDocumentParts() {
        return document != null || references != null || candidateRootVersion != null;
    }

    /** True when any resolution wording or answer is present. */
    public boolean declaresResolutionParts() {
        return utterance != null || candidateTexts != null || audienceSelection != null || versionSelection != null;
    }

    /** The declared reference sites, never null. */
    public List<ReferenceSiteSpec> declaredSites() {
        return references == null ? List.of() : references;
    }

    /** The declared candidate texts, never null. */
    public List<String> declaredCandidateTexts() {
        return candidateTexts == null ? List.of() : candidateTexts;
    }

    /** The declared membership pins, never null. */
    public List<ReferencePin> declaredPins() {
        return pins == null ? List.of() : pins;
    }
}