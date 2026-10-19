package com.company.campaign.evaluation.segment.reference;

/**
 * The frozen identities, versions and bounds of the T18 Existing Audience Reference corpus.
 *
 * <p>Every value here is part of a hash material or of a pinned corpus identity. The corpus identity
 * ({@link #CORPUS_ID}, {@link #CORPUS_VERSION}) and the content hash derived from the committed bytes are
 * exactly what a {@code segment.existing-audience-reference} Gate decision would be taken over, so a
 * changed value is a different corpus rather than a reinterpretation of an approved one.
 *
 * <p>{@link #GATE_MINIMUM_CASES} is the honest floor the T18 specification states ("at least 40 cases").
 * It is a <em>gate input</em>: the corpus validator refuses to treat a smaller corpus as Gate-ready, and the
 * manifest declares its own minimum so a smaller reviewed example could still be loaded.
 */
public final class ReferenceCorpusVersions {

    /** Repository-relative root of the committed reference corpus. */
    public static final String CORPUS_ROOT = "evaluation/segment/reference-corpus-v1";

    /** Repository-relative reference of the committed reference-corpus manifest. */
    public static final String MANIFEST_REFERENCE = CORPUS_ROOT + "/manifest.json";

    /** The bounded corpus identity a reference Gate decision would name. */
    public static final String CORPUS_ID = "reference-corpus-v1";

    /** The corpus revision identity; a changed revision is a different reviewed corpus. */
    public static final String CORPUS_VERSION = "reference-corpus-1.0";

    /** The canonical document format version of the reference corpus assets. */
    public static final String CORPUS_FORMAT_VERSION = "segment-reference-corpus/1.0";

    /** The fixture catalog revision the corpus manifest pins. */
    public static final String FIXTURE_CATALOG_VERSION = "reference-corpus-v1-fixtures-1";

    /** The catalog/directory contract identity the reference readiness inputs pin. */
    public static final String CATALOG_DIRECTORY_CONTRACT_VERSION = "audience-catalog-directory-contract/1.0";

    /** The dependency-graph inputs identity the reference readiness inputs pin. */
    public static final String DEPENDENCY_GRAPH_INPUTS_VERSION = "audience-reference-graph-inputs/1.0";

    /** The T18 specification floor: a reference corpus is never Gate-ready below forty cases. */
    public static final int GATE_MINIMUM_CASES = 40;

    /** The floor this corpus applies to every declared coverage category. */
    public static final int GATE_MINIMUM_CASES_PER_CATEGORY = 3;

    private ReferenceCorpusVersions() {
    }
}