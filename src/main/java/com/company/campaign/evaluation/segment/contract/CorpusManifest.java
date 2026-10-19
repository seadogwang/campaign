package com.company.campaign.evaluation.segment.contract;

import com.company.campaign.contracts.ContractPatterns;
import com.company.campaign.contracts.SchemaVersion;
import com.company.campaign.shared.ImmutableValues;
import com.company.campaign.shared.canonical.CanonicalJson;
import com.company.campaign.shared.canonical.ContentHash;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The immutable corpus manifest: the one place that says which files a corpus consists of.
 *
 * <p>The manifest pins the corpus version, the Metadata and fixture catalogs (each by version
 * <em>and</em> content hash) and the ordered case/expected pairs (each by path and content hash). Nothing
 * is discovered by directory listing, so adding a file to the corpus without reviewing the manifest
 * cannot change what a Gate evaluates.
 *
 * <p>{@code minimumCasesPerCategory} and {@code requiredHighRiskRepeats} are declared here rather than
 * hard-coded so a small checked-in example can be loaded at all. They are gate <em>inputs</em>, not gate
 * requirements: the Gate validator refuses to treat a manifest as Gate-ready unless it asks for at least
 * fifteen cases per category and at least five high-risk repeats.
 *
 * <p>{@link #manifestHash()} covers the manifest with that field itself excluded, so the hash cannot be
 * self-referential. {@link #of} is the only way to build a manifest with a correct hash, and
 * {@link #requireValidContentHash()} re-derives it from the parsed value at load time.
 */
@JsonIgnoreProperties(ignoreUnknown = false)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CorpusManifest(
        SchemaVersion schemaVersion,
        String corpusVersion,
        CatalogReference metadataCatalog,
        CatalogReference fixtureCatalog,
        int minimumCasesPerCategory,
        int requiredHighRiskRepeats,
        List<CaseFileReference> cases,
        ContentHash manifestHash
) {

    /** Gate minimum from Segment specification section 20: fifteen cases in each of fifteen categories. */
    public static final int GATE_MINIMUM_CASES_PER_CATEGORY = 15;

    /** Gate minimum from Segment specification section 23.2: high-risk cases run five times. */
    public static final int GATE_REQUIRED_HIGH_RISK_REPEATS = 5;

    private static final CanonicalJson CANONICAL_JSON = new CanonicalJson();
    private static final Pattern REFERENCE_ID = Pattern.compile(ContractPatterns.REFERENCE_ID);

    public CorpusManifest {
        if (schemaVersion == null) {
            throw new EvaluationException(
                    EvaluationErrorCodes.ASSET_INVALID, "Corpus manifest must declare a schema version");
        }
        if (corpusVersion == null || !REFERENCE_ID.matcher(corpusVersion).matches()) {
            throw new EvaluationException(
                    EvaluationErrorCodes.ID_INVALID,
                    "Corpus version '" + corpusVersion + "' is not a valid reference identifier");
        }
        if (metadataCatalog == null || fixtureCatalog == null) {
            throw new EvaluationException(
                    EvaluationErrorCodes.ASSET_INVALID,
                    "Corpus manifest must reference both the Metadata catalog and the fixture catalog");
        }
        if (minimumCasesPerCategory < 1) {
            throw new EvaluationException(
                    EvaluationErrorCodes.ASSET_INVALID,
                    "minimumCasesPerCategory must be at least 1 but was " + minimumCasesPerCategory);
        }
        if (requiredHighRiskRepeats < 1) {
            throw new EvaluationException(
                    EvaluationErrorCodes.ASSET_INVALID,
                    "requiredHighRiskRepeats must be at least 1 but was " + requiredHighRiskRepeats);
        }
        if (cases == null) {
            throw new EvaluationException(
                    EvaluationErrorCodes.ASSET_INVALID, "Corpus manifest must declare its case references");
        }
        cases = ImmutableValues.freezeList(cases);
    }

    /**
     * Builds a manifest and stamps it with its own content hash.
     *
     * <p>The hash is computed over the canonical form of the same manifest with {@code manifestHash}
     * absent, which is exactly what {@link #requireValidContentHash()} recomputes.
     */
    public static CorpusManifest of(
            SchemaVersion schemaVersion,
            String corpusVersion,
            CatalogReference metadataCatalog,
            CatalogReference fixtureCatalog,
            int minimumCasesPerCategory,
            int requiredHighRiskRepeats,
            List<CaseFileReference> cases) {

        CorpusManifest withoutHash = new CorpusManifest(
                schemaVersion, corpusVersion, metadataCatalog, fixtureCatalog,
                minimumCasesPerCategory, requiredHighRiskRepeats, cases, null);
        return withoutHash.withManifestHash(CANONICAL_JSON.hash(withoutHash));
    }

    /** The same manifest carrying the given hash. */
    public CorpusManifest withManifestHash(ContentHash hash) {
        return new CorpusManifest(
                schemaVersion, corpusVersion, metadataCatalog, fixtureCatalog,
                minimumCasesPerCategory, requiredHighRiskRepeats, cases, hash);
    }

    /** The canonical content hash of this manifest with the hash field excluded. */
    public ContentHash recomputeContentHash() {
        return CANONICAL_JSON.hash(withManifestHash(null));
    }

    /** Rejects a manifest whose declared hash does not match its content. */
    public void requireValidContentHash() {
        if (manifestHash == null) {
            throw new EvaluationException(
                    EvaluationErrorCodes.MANIFEST_HASH_MISMATCH,
                    "Corpus manifest does not declare a content hash");
        }
        if (!manifestHash.equals(recomputeContentHash())) {
            throw new EvaluationException(
                    EvaluationErrorCodes.MANIFEST_HASH_MISMATCH,
                    "Corpus manifest content hash does not match the manifest content",
                    Map.of("declared", manifestHash.value(), "computed", recomputeContentHash().value()));
        }
    }
}