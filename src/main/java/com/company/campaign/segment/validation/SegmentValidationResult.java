package com.company.campaign.segment.validation;

import com.company.campaign.contracts.Issue;
import com.company.campaign.contracts.SchemaVersion;
import com.company.campaign.segment.canonical.CanonicalErrorCodes;
import com.company.campaign.segment.canonical.CanonicalInvariantException;
import com.company.campaign.segment.canonical.CanonicalSegment;
import com.company.campaign.segment.canonical.CanonicalSegmentCodec;
import com.company.campaign.segment.canonical.CanonicalizerVersions;
import com.company.campaign.segment.dsl.contract.MetadataBinding;
import com.company.campaign.segment.reference.ReferenceGraph;
import com.company.campaign.shared.canonical.ContentHash;

import java.util.List;
import java.util.Objects;

/**
 * Immutable result of one deterministic validation run.
 *
 * <p>It carries the ordered {@link Issue} list, the caller's editable DSL hash unchanged, the canonical
 * semantic AST and {@code canonicalAstHash} <em>only</em> when no blocking issue exists, and the exact
 * version/binding pins that identify what produced it. A partial canonical AST is never returned as a
 * success: when {@link #canonicalSegment()} is null the document did not validate.
 *
 * <p>The boundary is a proof, not a container. This class has no public constructor and no public factory:
 * every instance is created inside {@code com.company.campaign.segment.validation} by {@link SegmentValidator}
 * through the package-private {@link #success} and {@link #rejected} factories, so a caller outside the T07
 * validation implementation can inspect a genuine result but can never manufacture one. Construction still
 * recomputes the canonical hash and requires it to equal the declared one, requires every version/schema/
 * binding pin to agree with the canonical root, forbids a success that carries a blocking issue and forbids a
 * rejection that carries none — so even the internal factories cannot be used to forge a contradictory state.
 * Every failure is a stable {@link CanonicalInvariantException} that echoes no payload value.
 *
 * <p>The issue list is defensively frozen and null-hostile. The optional identity fields are null exactly
 * when their input was absent — for example a null-input result has no DSL schema version, no binding and no
 * editable hash — and are documented as such rather than silently replaced by a fabricated default.
 *
 * <p>{@code equals}, {@code hashCode} and {@code toString} keep the component-wise value semantics the former
 * record exposed, so existing comparisons and diagnostics are unchanged.
 */
public final class SegmentValidationResult {

    private static final CanonicalSegmentCodec CODEC = new CanonicalSegmentCodec();

    private final String canonicalizerVersion;
    private final SchemaVersion dslSchemaVersion;
    private final MetadataBinding metadataBinding;
    private final ContentHash editableDslHash;
    private final CanonicalSegment canonicalSegment;
    private final ContentHash canonicalAstHash;
    private final List<Issue> issues;
    private final ReferenceGraph referenceGraph;

    /**
     * The accepted non-reference construction path, kept at its original arity so every existing caller and
     * adversarial in-package test compiles and behaves exactly as before.
     */
    SegmentValidationResult(
            String canonicalizerVersion,
            SchemaVersion dslSchemaVersion,
            MetadataBinding metadataBinding,
            ContentHash editableDslHash,
            CanonicalSegment canonicalSegment,
            ContentHash canonicalAstHash,
            List<Issue> issues) {
        this(canonicalizerVersion, dslSchemaVersion, metadataBinding, editableDslHash, canonicalSegment,
                canonicalAstHash, issues, null);
    }

    /**
     * The one construction path; package-private so only the T07 validation implementation and its in-package
     * adversarial tests can reach it. Every consistency, hash and root-pin check from the accepted R1 boundary
     * is retained here, so the internal success/rejection factories cannot produce a contradictory state.
     *
     * <p>{@code referenceGraph} is the trusted Existing Audience Reference dependency graph, present exactly
     * when a validated document contained a reference and resolved cleanly. A rejected result never carries one,
     * so the graph can never be read off a document that did not validate.
     */
    SegmentValidationResult(
            String canonicalizerVersion,
            SchemaVersion dslSchemaVersion,
            MetadataBinding metadataBinding,
            ContentHash editableDslHash,
            CanonicalSegment canonicalSegment,
            ContentHash canonicalAstHash,
            List<Issue> issues,
            ReferenceGraph referenceGraph) {
        if (!CanonicalizerVersions.CANONICALIZER_VERSION.equals(canonicalizerVersion)) {
            throw inconsistent("canonicalizerVersion",
                    "A validation result must carry the one supported canonicalizer version.");
        }
        if (issues == null) {
            throw inconsistent("issues", "A validation result requires an issue list.");
        }
        for (Issue issue : issues) {
            if (issue == null) {
                throw inconsistent("issues", "A validation result must not carry a null issue.");
            }
        }
        issues = List.copyOf(issues);

        if (canonicalSegment == null) {
            if (canonicalAstHash != null) {
                throw inconsistent("hashWithoutSegment",
                        "A canonical hash cannot exist without a canonical segment.");
            }
            if (referenceGraph != null) {
                throw inconsistent("graphWithoutSegment",
                        "A reference graph cannot exist without a canonical segment.");
            }
            if (issues.stream().noneMatch(Issue::blocksProcessing)) {
                throw inconsistent("rejectionWithoutBlockingIssue",
                        "A rejected result must carry at least one blocking issue.");
            }
        } else {
            if (canonicalAstHash == null) {
                throw inconsistent("segmentWithoutHash", "A canonical segment must carry its canonical hash.");
            }
            if (!Objects.equals(dslSchemaVersion, canonicalSegment.schemaVersion())) {
                throw inconsistent("schemaVersion",
                        "The result DSL schema version must agree with the canonical root.");
            }
            if (!Objects.equals(metadataBinding, canonicalSegment.metadata())) {
                throw inconsistent("metadataBinding",
                        "The result Metadata binding must agree with the canonical root.");
            }
            if (!canonicalAstHash.equals(CODEC.hash(canonicalSegment))) {
                throw inconsistent("canonicalAstHash",
                        "The canonical hash must be the canonical hash of the canonical root.");
            }
            if (issues.stream().anyMatch(Issue::blocksProcessing)) {
                throw inconsistent("successWithBlockingIssue",
                        "A successful result must not carry a blocking issue.");
            }
        }

        this.canonicalizerVersion = canonicalizerVersion;
        this.dslSchemaVersion = dslSchemaVersion;
        this.metadataBinding = metadataBinding;
        this.editableDslHash = editableDslHash;
        this.canonicalSegment = canonicalSegment;
        this.canonicalAstHash = canonicalAstHash;
        this.issues = issues;
        this.referenceGraph = referenceGraph;
    }

    /**
     * Builds the one success shape: a canonical root, its recomputed hash and no issue.
     *
     * <p>Package-private and used only by {@link SegmentValidator}; the constructor re-proves the hash and the
     * root pins, so this factory cannot fabricate a valid result that never ran validation.
     */
    static SegmentValidationResult success(
            String canonicalizerVersion,
            SchemaVersion dslSchemaVersion,
            MetadataBinding metadataBinding,
            ContentHash editableDslHash,
            CanonicalSegment canonicalSegment,
            ContentHash canonicalAstHash) {
        return new SegmentValidationResult(
                canonicalizerVersion,
                dslSchemaVersion,
                metadataBinding,
                editableDslHash,
                canonicalSegment,
                canonicalAstHash,
                List.of());
    }

    /**
     * Builds the one reference-bearing success shape: a canonical root, its hash, the trusted reference graph
     * and no issue. The graph is null for a document that contained no reference.
     */
    static SegmentValidationResult success(
            String canonicalizerVersion,
            SchemaVersion dslSchemaVersion,
            MetadataBinding metadataBinding,
            ContentHash editableDslHash,
            CanonicalSegment canonicalSegment,
            ContentHash canonicalAstHash,
            ReferenceGraph referenceGraph) {
        return new SegmentValidationResult(
                canonicalizerVersion,
                dslSchemaVersion,
                metadataBinding,
                editableDslHash,
                canonicalSegment,
                canonicalAstHash,
                List.of(),
                referenceGraph);
    }

    /**
     * Builds the one rejection shape: no canonical root and at least one blocking issue.
     *
     * <p>Package-private and used only by {@link SegmentValidator}; the constructor still requires a blocking
     * issue, so a warning-only list is rejected rather than published as a rejection.
     */
    static SegmentValidationResult rejected(
            String canonicalizerVersion,
            SchemaVersion dslSchemaVersion,
            MetadataBinding metadataBinding,
            ContentHash editableDslHash,
            List<Issue> issues) {
        return new SegmentValidationResult(
                canonicalizerVersion,
                dslSchemaVersion,
                metadataBinding,
                editableDslHash,
                null,
                null,
                issues);
    }

    /** The one supported canonicalizer version that produced this result. */
    public String canonicalizerVersion() {
        return canonicalizerVersion;
    }

    /** The Segment DSL schema version, or null exactly when the input document was absent. */
    public SchemaVersion dslSchemaVersion() {
        return dslSchemaVersion;
    }

    /** The Metadata binding the document claimed, or null exactly when the document was absent. */
    public MetadataBinding metadataBinding() {
        return metadataBinding;
    }

    /** The caller's editable DSL hash unchanged, or null exactly when the document was absent. */
    public ContentHash editableDslHash() {
        return editableDslHash;
    }

    /** The canonical semantic AST, or null when the document did not validate. */
    public CanonicalSegment canonicalSegment() {
        return canonicalSegment;
    }

    /** The canonical AST's content hash, or null when the document did not validate. */
    public ContentHash canonicalAstHash() {
        return canonicalAstHash;
    }

    /** The ordered, immutable issue list. */
    public List<Issue> issues() {
        return issues;
    }

    /**
     * The trusted Existing Audience Reference dependency graph, or null when the document had no reference.
     *
     * <p>It is present exactly for a validated document whose references all resolved: the exact pinned
     * versions, their frozen catalog facts, their trusted effective DataScope and the deterministic graph
     * hash. A rejected result never carries one, so a caller cannot read a graph off a document that did not
     * validate.
     */
    public ReferenceGraph referenceGraph() {
        return referenceGraph;
    }

    /**
     * True when validation succeeded: no issue blocks processing and the canonical AST was produced.
     *
     * <p>Requiring the canonical segment makes "valid" mean exactly what it says — a caller can never observe
     * a valid result without the semantic artifact, and a rejected result without issues (which would be a
     * defect in the pipeline) is rejected at construction rather than silently accepted.
     */
    public boolean valid() {
        return canonicalSegment != null && issues.stream().noneMatch(Issue::blocksProcessing);
    }

    /** True when at least one issue blocks downstream deterministic processing. */
    public boolean hasBlockingIssues() {
        return !valid();
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof SegmentValidationResult result)) {
            return false;
        }
        return Objects.equals(canonicalizerVersion, result.canonicalizerVersion)
                && Objects.equals(dslSchemaVersion, result.dslSchemaVersion)
                && Objects.equals(metadataBinding, result.metadataBinding)
                && Objects.equals(editableDslHash, result.editableDslHash)
                && Objects.equals(canonicalSegment, result.canonicalSegment)
                && Objects.equals(canonicalAstHash, result.canonicalAstHash)
                && Objects.equals(issues, result.issues)
                && Objects.equals(referenceGraph, result.referenceGraph);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                canonicalizerVersion,
                dslSchemaVersion,
                metadataBinding,
                editableDslHash,
                canonicalSegment,
                canonicalAstHash,
                issues,
                referenceGraph);
    }

    @Override
    public String toString() {
        return "SegmentValidationResult["
                + "canonicalizerVersion=" + canonicalizerVersion
                + ", dslSchemaVersion=" + dslSchemaVersion
                + ", metadataBinding=" + metadataBinding
                + ", editableDslHash=" + editableDslHash
                + ", canonicalSegment=" + canonicalSegment
                + ", canonicalAstHash=" + canonicalAstHash
                + ", issues=" + issues
                + ", referenceGraph=" + referenceGraph
                + ']';
    }

    private static CanonicalInvariantException inconsistent(String invariant, String message) {
        return new CanonicalInvariantException(
                CanonicalErrorCodes.CANONICAL_RESULT_INCONSISTENT, invariant, message);
    }
}