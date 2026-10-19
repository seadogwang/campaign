package com.company.campaign.evaluation.segment.reference;

import com.company.campaign.contracts.ContractPatterns;
import com.company.campaign.contracts.SchemaVersion;
import com.company.campaign.evaluation.segment.contract.CaseFileReference;
import com.company.campaign.evaluation.segment.contract.CatalogReference;
import com.company.campaign.evaluation.segment.contract.EvaluationErrorCodes;
import com.company.campaign.evaluation.segment.contract.EvaluationException;
import com.company.campaign.shared.ImmutableValues;
import com.company.campaign.shared.canonical.CanonicalJson;
import com.company.campaign.shared.canonical.ContentHash;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The immutable manifest of the T18 Existing Audience Reference corpus.
 *
 * <p>It is the one place that says which files the corpus consists of, and it also states — in the file
 * itself, where a reviewer reads it — what the corpus claims about its own evidence: whether human review is
 * pending or complete, whether a configured-model run has been recorded, and the release status. Those claims
 * are checked against the per-case review ledgers by {@link ReferenceCorpusValidator}, so a manifest cannot
 * assert an approval the ledgers do not prove.
 *
 * <p>{@link ReferenceReleaseStatus} has no {@code PASSED} or {@code ACTIVE} constant at all: a production
 * activation claim is unrepresentable rather than merely forbidden, because no corpus file can produce the
 * Gate decision that would justify one.
 *
 * <p>{@link #manifestHash()} covers the manifest with that field itself excluded, so the hash cannot be
 * self-referential; {@link #of} is the only way to build a manifest with a correct hash, and the loader
 * re-derives it from the parsed value.
 */
@JsonIgnoreProperties(ignoreUnknown = false)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ReferenceCorpusManifest(
        SchemaVersion schemaVersion,
        String corpusVersion,
        CatalogReference fixtureCatalog,
        int minimumCases,
        int minimumCasesPerCategory,
        ReferenceHumanReviewStatus humanReviewStatus,
        ReferenceModelEvidenceStatus modelEvidenceStatus,
        String modelEvidenceReference,
        ReferenceReleaseStatus releaseStatus,
        List<ReferenceCategory> requiredCategories,
        List<CaseFileReference> cases,
        ContentHash manifestHash
) {

    private static final CanonicalJson CANONICAL_JSON = new CanonicalJson();
    private static final Pattern REFERENCE_ID = Pattern.compile(ContractPatterns.REFERENCE_ID);

    public ReferenceCorpusManifest {
        if (schemaVersion == null) {
            throw new EvaluationException(
                    EvaluationErrorCodes.ASSET_INVALID,
                    "A reference corpus manifest must declare a schema version");
        }
        if (corpusVersion == null || !REFERENCE_ID.matcher(corpusVersion).matches()) {
            throw new EvaluationException(
                    EvaluationErrorCodes.ID_INVALID,
                    "Reference corpus version '" + corpusVersion + "' is not a valid reference identifier");
        }
        if (fixtureCatalog == null) {
            throw new EvaluationException(
                    EvaluationErrorCodes.ASSET_INVALID,
                    "A reference corpus manifest must reference its fixture catalog");
        }
        if (minimumCases < 1 || minimumCasesPerCategory < 1) {
            throw new EvaluationException(
                    EvaluationErrorCodes.ASSET_INVALID,
                    "A reference corpus manifest must declare positive case minimums");
        }
        if (humanReviewStatus == null || modelEvidenceStatus == null || releaseStatus == null) {
            throw new ReferenceCorpusException(
                    ReferenceCorpusErrorCodes.EXPECTATION_INCOMPLETE,
                    "A reference corpus manifest must declare its review, model-evidence and release status");
        }
        if (modelEvidenceStatus == ReferenceModelEvidenceStatus.RECORDED) {
            if (modelEvidenceReference == null || !REFERENCE_ID.matcher(modelEvidenceReference).matches()) {
                throw new ReferenceCorpusException(
                        ReferenceCorpusErrorCodes.MODEL_EVIDENCE_FABRICATED,
                        "A manifest that claims recorded model evidence must name the bounded artifact that holds it");
            }
        } else if (modelEvidenceReference != null) {
            throw new ReferenceCorpusException(
                    ReferenceCorpusErrorCodes.MODEL_EVIDENCE_FABRICATED,
                    "A manifest that claims no model run must not name a model-evidence artifact");
        }
        requiredCategories = canonicalCategories(requiredCategories);
        if (cases == null) {
            throw new EvaluationException(
                    EvaluationErrorCodes.ASSET_INVALID, "A reference corpus manifest must declare its cases");
        }
        cases = ImmutableValues.freezeList(cases);
    }

    /**
     * Builds a manifest and stamps it with its own content hash.
     *
     * <p>The hash is computed over the canonical form of the same manifest with {@code manifestHash} absent,
     * which is exactly what {@link #requireValidContentHash()} recomputes.
     */
    public static ReferenceCorpusManifest of(
            SchemaVersion schemaVersion,
            String corpusVersion,
            CatalogReference fixtureCatalog,
            int minimumCases,
            int minimumCasesPerCategory,
            ReferenceHumanReviewStatus humanReviewStatus,
            ReferenceModelEvidenceStatus modelEvidenceStatus,
            String modelEvidenceReference,
            ReferenceReleaseStatus releaseStatus,
            List<ReferenceCategory> requiredCategories,
            List<CaseFileReference> cases) {
        ReferenceCorpusManifest withoutHash = new ReferenceCorpusManifest(
                schemaVersion, corpusVersion, fixtureCatalog, minimumCases, minimumCasesPerCategory,
                humanReviewStatus, modelEvidenceStatus, modelEvidenceReference, releaseStatus,
                requiredCategories, cases, null);
        return withoutHash.withManifestHash(CANONICAL_JSON.hash(withoutHash));
    }

    /** The same manifest carrying the given hash. */
    public ReferenceCorpusManifest withManifestHash(ContentHash hash) {
        return new ReferenceCorpusManifest(
                schemaVersion, corpusVersion, fixtureCatalog, minimumCases, minimumCasesPerCategory,
                humanReviewStatus, modelEvidenceStatus, modelEvidenceReference, releaseStatus,
                requiredCategories, cases, hash);
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
                    "A reference corpus manifest must declare its content hash");
        }
        if (!manifestHash.equals(recomputeContentHash())) {
            throw new EvaluationException(
                    EvaluationErrorCodes.MANIFEST_HASH_MISMATCH,
                    "A reference corpus manifest's content hash does not match its content",
                    Map.of("declared", manifestHash.value(), "computed", recomputeContentHash().value()));
        }
    }

    /** True when the manifest claims that every required owner role approved every pinned revision. */
    public boolean claimsHumanReviewComplete() {
        return humanReviewStatus == ReferenceHumanReviewStatus.COMPLETE;
    }

    /** True when the manifest asks to be judged against the T18 floor of forty cases. */
    public boolean requiresGateMinimum() {
        return minimumCases >= ReferenceCorpusVersions.GATE_MINIMUM_CASES;
    }

    private static List<ReferenceCategory> canonicalCategories(List<ReferenceCategory> categories) {
        if (categories == null || categories.isEmpty()) {
            throw new ReferenceCorpusException(
                    ReferenceCorpusErrorCodes.CATEGORY_MISSING,
                    "A reference corpus manifest must declare the coverage categories it requires");
        }
        List<ReferenceCategory> ordered = new ArrayList<>(categories);
        for (ReferenceCategory category : ordered) {
            if (category == null) {
                throw new ReferenceCorpusException(
                        ReferenceCorpusErrorCodes.CATEGORY_MISSING,
                        "A required coverage category must be a declared reference category");
            }
        }
        ordered.sort(java.util.Comparator.comparingInt(Enum::ordinal));
        for (int index = 1; index < ordered.size(); index++) {
            if (ordered.get(index - 1) == ordered.get(index)) {
                throw new ReferenceCorpusException(
                        ReferenceCorpusErrorCodes.CATEGORY_MISSING,
                        "A required coverage category is declared more than once");
            }
        }
        return ImmutableValues.freezeList(ordered);
    }
}