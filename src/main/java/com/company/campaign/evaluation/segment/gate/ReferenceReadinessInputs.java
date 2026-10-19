package com.company.campaign.evaluation.segment.gate;

import com.company.campaign.segment.reference.ReferenceGraph;
import com.company.campaign.shared.canonical.CanonicalJson;
import com.company.campaign.shared.canonical.ContentHash;

/**
 * The complete frozen input bundle of the Existing Audience Reference capability.
 *
 * <p>It is the whole of "the reference gated inputs": the reference corpus identity and content hash, the
 * seven {@link ReferenceReadinessVersions frozen subsystem versions}, the reference graph format, the
 * reference policy hash, the catalog/directory contract hash and the dependency-graph inputs hash.
 *
 * <p>{@link #contentHash()} covers every one of those components, so changing any single pinned input — the
 * corpus, a version, the graph format or any of the three hashes — produces a different canonical hash. That
 * makes drift observable rather than implicit, and it is the exact value a reference Gate decision pins and a
 * readiness projection compares.
 *
 * <p>A reference-free compile supplies no reference inputs at all, so it never constructs this value: the
 * reference Gate port is not consulted and no reference readiness is projected from it.
 */
public record ReferenceReadinessInputs(
        String referenceCorpusId,
        String referenceCorpusVersion,
        ContentHash referenceCorpusHash,
        ReferenceReadinessVersions versions,
        String referenceGraphFormat,
        ContentHash referencePolicyHash,
        ContentHash catalogDirectoryContractHash,
        ContentHash dependencyGraphInputsHash
) {

    private static final CanonicalJson CANONICAL_JSON = new CanonicalJson();

    public ReferenceReadinessInputs {
        if (referenceCorpusId == null || referenceCorpusVersion == null || referenceCorpusHash == null
                || versions == null || referenceGraphFormat == null || referencePolicyHash == null
                || catalogDirectoryContractHash == null || dependencyGraphInputsHash == null) {
            throw GateException.of(
                    GateErrorCodes.REFERENCE_INPUT_INVALID,
                    "Reference readiness inputs require every pinned component of the reference bundle");
        }
    }

    /**
     * The current reference inputs: the given corpus, policy, catalog/directory contract and dependency-graph
     * pins paired with {@link ReferenceReadinessVersions#current()} and the current reference graph format.
     */
    public static ReferenceReadinessInputs current(
            String referenceCorpusId,
            String referenceCorpusVersion,
            ContentHash referenceCorpusHash,
            ContentHash referencePolicyHash,
            ContentHash catalogDirectoryContractHash,
            ContentHash dependencyGraphInputsHash) {
        return new ReferenceReadinessInputs(
                referenceCorpusId,
                referenceCorpusVersion,
                referenceCorpusHash,
                ReferenceReadinessVersions.current(),
                ReferenceGraph.GRAPH_FORMAT_VERSION,
                referencePolicyHash,
                catalogDirectoryContractHash,
                dependencyGraphInputsHash);
    }

    /** The canonical hash of every pinned reference input; identical inputs produce identical bytes. */
    public ContentHash contentHash() {
        return CANONICAL_JSON.hash(this);
    }
}