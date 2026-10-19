package com.company.campaign.evaluation.segment.validation;

import com.company.campaign.contracts.Issue;
import com.company.campaign.evaluation.segment.contract.CaseInput;
import com.company.campaign.evaluation.segment.contract.CorpusBundle;
import com.company.campaign.evaluation.segment.contract.EvaluationErrorCodes;
import com.company.campaign.evaluation.segment.contract.ExpectedResult;
import com.company.campaign.evaluation.segment.contract.MetadataSnapshotDescriptor;
import com.company.campaign.segment.dsl.codec.SegmentDslCodec;
import com.company.campaign.segment.dsl.contract.MetadataBinding;
import com.company.campaign.segment.dsl.contract.SegmentDsl;
import com.company.campaign.segment.dsl.contract.SegmentDslException;
import com.company.campaign.segment.dsl.predicate.AttributePredicate;
import com.company.campaign.segment.dsl.predicate.ExistsPredicate;
import com.company.campaign.segment.dsl.predicate.GroupPredicate;
import com.company.campaign.segment.dsl.predicate.MetricPredicate;
import com.company.campaign.segment.dsl.predicate.NotPredicate;
import com.company.campaign.segment.dsl.predicate.Predicate;
import com.company.campaign.shared.canonical.CanonicalJson;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Validates every expected canonical AST as a real T05 Segment DSL 1.3 document, not as an arbitrary tree.
 *
 * <p>An expected AST is corpus evidence, so "it is some JSON" is not enough. Each one is parsed through
 * {@link SegmentDslCodec}, which recomputes {@code editableDslHash} and rejects a tampered one, and its
 * Metadata binding must pin the exact snapshot the case's Metadata descriptor names. The document's
 * attribute, metric and relation ids must also be declared by that snapshot, which is the corpus
 * consistency check that stops an impossible fixture — not an operator or data-type re-validation, which
 * belongs to T04 and T07.
 *
 * <p>No {@code canonicalAstHash} is produced or required: semantic canonicalization is T07's contract, and
 * inventing that hash here would create a second, weaker definition of the canonical form.
 */
public final class SegmentDslExpectationValidator {

    private static final CanonicalJson CANONICAL_JSON = new CanonicalJson();
    private static final SegmentDslCodec CODEC = new SegmentDslCodec();

    /** Reports every expected-AST problem, in manifest order. */
    public List<Issue> validate(CorpusBundle bundle) {
        Objects.requireNonNull(bundle, "bundle");
        List<Issue> issues = new ArrayList<>();
        List<CaseInput> cases = bundle.cases();
        List<ExpectedResult> expectedResults = bundle.expectedResults();
        CorpusMetadataView metadataView = CorpusMetadataView.of(bundle);

        for (int index = 0; index < cases.size(); index++) {
            CaseInput caseInput = cases.get(index);
            ExpectedResult expected = expectedResults.get(index);
            if (expected.expectedCanonicalAst() == null) {
                continue;
            }
            String path = "/cases/" + index + "/" + caseInput.caseId() + "/expectedCanonicalAst";
            MetadataSnapshotDescriptor descriptor = descriptorFor(bundle, caseInput);
            Set<String> declared = metadataView.definitionIds(caseInput.metadataSnapshot());

            SegmentDsl document;
            try {
                document = CODEC.parse(CANONICAL_JSON.writeString(expected.expectedCanonicalAst()));
            } catch (SegmentDslException exception) {
                issues.add(SegmentEvaluationValidator.issue(
                        EvaluationErrorCodes.EXPECTED_AST_NOT_T05_VALID, path,
                        "Expected canonical AST for case '" + caseInput.caseId()
                                + "' is not a structurally valid T05 Segment DSL document"));
                continue;
            }

            if (descriptor != null && !bindingMatches(document.metadata(), descriptor)) {
                issues.add(SegmentEvaluationValidator.issue(
                        EvaluationErrorCodes.EXPECTED_AST_METADATA_BINDING_MISMATCH, path,
                        "Expected canonical AST for case '" + caseInput.caseId()
                                + "' does not pin the case's Metadata snapshot descriptor"));
            }

            if (declared != null) {
                requireDeclaredReferences(caseInput, document.predicate(), declared, path, issues);
            }
        }
        return issues;
    }

    private static MetadataSnapshotDescriptor descriptorFor(CorpusBundle bundle, CaseInput caseInput) {
        for (MetadataSnapshotDescriptor descriptor : bundle.metadataCatalog().snapshots()) {
            if (descriptor.snapshotId().equals(caseInput.metadataSnapshot())) {
                return descriptor;
            }
        }
        return null;
    }

    private static boolean bindingMatches(MetadataBinding binding, MetadataSnapshotDescriptor descriptor) {
        return binding.snapshotId().equals(descriptor.snapshotId())
                && binding.snapshotVersion().equals(descriptor.snapshotVersion())
                && binding.contentHash().equals(descriptor.contentHash());
    }

    private static void requireDeclaredReferences(
            CaseInput caseInput, Predicate predicate, Set<String> declared, String path, List<Issue> issues) {

        Set<String> unknown = new LinkedHashSet<>();
        collectUnknownReferences(predicate, declared, unknown);
        for (String reference : unknown) {
            issues.add(SegmentEvaluationValidator.issue(
                    EvaluationErrorCodes.EXPECTED_AST_REFERENCE_UNKNOWN, path,
                    "Expected canonical AST for case '" + caseInput.caseId() + "' references '" + reference
                            + "', which the Metadata snapshot does not declare"));
        }
    }

    private static void collectUnknownReferences(
            Predicate predicate, Set<String> declared, Set<String> unknown) {

        if (predicate instanceof GroupPredicate group) {
            for (Predicate child : group.children()) {
                collectUnknownReferences(child, declared, unknown);
            }
        } else if (predicate instanceof NotPredicate not) {
            collectUnknownReferences(not.child(), declared, unknown);
        } else if (predicate instanceof ExistsPredicate exists) {
            if (!declared.contains(exists.relationId())) {
                unknown.add(exists.relationId());
            }
            collectUnknownReferences(exists.predicate(), declared, unknown);
        } else if (predicate instanceof AttributePredicate attribute) {
            if (!declared.contains(attribute.attributeId())) {
                unknown.add(attribute.attributeId());
            }
        } else if (predicate instanceof MetricPredicate metric) {
            if (!declared.contains(metric.metricId())) {
                unknown.add(metric.metricId());
            }
        }
    }
}