package com.company.campaign.evaluation.segment.contract;

import com.company.campaign.contracts.ContractPatterns;
import com.company.campaign.contracts.SchemaVersion;
import com.company.campaign.shared.ImmutableValues;
import com.company.campaign.shared.canonical.CanonicalJson;
import com.company.campaign.shared.canonical.ContentHash;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The versioned review ledger for one Golden Corpus case: one file per case, bound to the exact revision
 * that was reviewed.
 *
 * <p>The ledger pins the case file hash and the expected-result file hash it was written against. That
 * binding is what makes approval mean something: if either half of the reviewed pair is edited, the case's
 * bytes change, the ledger no longer names the reviewed revision, and the corpus validator reports the
 * ledger as stale instead of letting a stale approval silently survive an expectation edit.
 *
 * <p>Every case carries exactly one entry per {@link ReviewRole}, stored in role declaration order. A
 * ledger is therefore never ambiguous about who reviewed what, and two ledgers that say the same thing
 * serialize to identical canonical bytes.
 *
 * <p>{@link #reviewHash()} covers the ledger with that field itself excluded, so the hash cannot be
 * self-referential. {@link #of} is the only way to build a ledger with a correct hash, and
 * {@link #requireValidContentHash()} re-derives it from the parsed value at load time.
 */
@JsonIgnoreProperties(ignoreUnknown = false)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CaseReviewEvidence(
        SchemaVersion schemaVersion,
        String caseId,
        ContentHash caseHash,
        ContentHash expectedHash,
        List<CaseReviewEntry> reviews,
        ContentHash reviewHash
) {

    private static final CanonicalJson CANONICAL_JSON = new CanonicalJson();
    private static final Pattern REFERENCE_ID = Pattern.compile(ContractPatterns.REFERENCE_ID);

    public CaseReviewEvidence {
        if (schemaVersion == null) {
            throw new EvaluationException(
                    EvaluationErrorCodes.ASSET_INVALID, "Review evidence must declare a schema version");
        }
        if (caseId == null || !REFERENCE_ID.matcher(caseId).matches()) {
            throw new EvaluationException(
                    EvaluationErrorCodes.ID_INVALID,
                    "Review evidence caseId '" + caseId + "' is not a valid reference identifier");
        }
        if (caseHash == null || expectedHash == null) {
            throw new EvaluationException(
                    EvaluationErrorCodes.ASSET_INVALID,
                    "Review evidence for case '" + caseId
                            + "' must bind both the case hash and the expected-result hash");
        }
        if (reviews == null) {
            throw new EvaluationException(
                    EvaluationErrorCodes.ASSET_INVALID,
                    "Review evidence for case '" + caseId + "' must declare its review entries");
        }
        reviews = canonicalReviews(caseId, reviews);
    }

    /**
     * Builds a ledger and stamps it with its own content hash.
     *
     * <p>The hash is computed over the canonical form of the same ledger with {@code reviewHash} absent,
     * which is exactly what {@link #requireValidContentHash()} recomputes.
     */
    public static CaseReviewEvidence of(
            SchemaVersion schemaVersion,
            String caseId,
            ContentHash caseHash,
            ContentHash expectedHash,
            List<CaseReviewEntry> reviews) {

        CaseReviewEvidence withoutHash = new CaseReviewEvidence(
                schemaVersion, caseId, caseHash, expectedHash, reviews, null);
        return withoutHash.withReviewHash(CANONICAL_JSON.hash(withoutHash));
    }

    /** The same ledger carrying the given hash. */
    public CaseReviewEvidence withReviewHash(ContentHash hash) {
        return new CaseReviewEvidence(schemaVersion, caseId, caseHash, expectedHash, reviews, hash);
    }

    /** The canonical content hash of this ledger with the hash field excluded. */
    public ContentHash recomputeContentHash() {
        return CANONICAL_JSON.hash(withReviewHash(null));
    }

    /** Rejects a ledger whose declared hash does not match its content. */
    public void requireValidContentHash() {
        if (reviewHash == null) {
            throw new EvaluationException(
                    EvaluationErrorCodes.REVIEW_HASH_MISMATCH,
                    "Review evidence for case '" + caseId + "' does not declare a content hash");
        }
        if (!reviewHash.equals(recomputeContentHash())) {
            throw new EvaluationException(
                    EvaluationErrorCodes.REVIEW_HASH_MISMATCH,
                    "Review evidence for case '" + caseId + "' does not match its declared content hash",
                    Map.of("caseId", caseId,
                            "declared", reviewHash.value(),
                            "computed", recomputeContentHash().value()));
        }
    }

    /** The entry for one role, or {@code null} when the ledger does not carry it. */
    public CaseReviewEntry entryFor(ReviewRole role) {
        for (CaseReviewEntry entry : reviews) {
            if (entry.role() == role) {
                return entry;
            }
        }
        return null;
    }

    /** True when every role has approved this exact revision. Not part of the wire shape. */
    @JsonIgnore
    public boolean isFullyApproved() {
        for (ReviewRole role : ReviewRole.ordered()) {
            CaseReviewEntry entry = entryFor(role);
            if (entry == null || entry.status() != ReviewStatus.APPROVED) {
                return false;
            }
        }
        return true;
    }

    private static List<CaseReviewEntry> canonicalReviews(String caseId, List<CaseReviewEntry> reviews) {
        Map<ReviewRole, CaseReviewEntry> byRole = new EnumMap<>(ReviewRole.class);
        for (CaseReviewEntry entry : reviews) {
            if (entry == null) {
                throw new EvaluationException(
                        EvaluationErrorCodes.REVIEW_ENTRY_INVALID,
                        "Review evidence for case '" + caseId + "' contains a null review entry");
            }
            if (byRole.put(entry.role(), entry) != null) {
                throw new EvaluationException(
                        EvaluationErrorCodes.REVIEW_ROLE_DUPLICATE,
                        "Review evidence for case '" + caseId + "' declares role " + entry.role()
                                + " more than once",
                        Map.of("caseId", caseId, "role", entry.role().name()));
            }
        }
        List<CaseReviewEntry> ordered = new ArrayList<>(ReviewRole.ordered().size());
        for (ReviewRole role : ReviewRole.ordered()) {
            CaseReviewEntry entry = byRole.get(role);
            if (entry == null) {
                throw new EvaluationException(
                        EvaluationErrorCodes.REVIEW_ROLE_MISSING,
                        "Review evidence for case '" + caseId + "' has no entry for role " + role,
                        Map.of("caseId", caseId, "role", role.name()));
            }
            ordered.add(entry);
        }
        // Role order is declaration order, so the stored list is canonical by construction.
        ordered.sort(Comparator.comparingInt(entry -> entry.role().ordinal()));
        return ImmutableValues.freezeList(ordered);
    }
}