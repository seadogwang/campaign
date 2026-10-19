package com.company.campaign.evaluation.segment.validation;

import com.company.campaign.contracts.Issue;
import com.company.campaign.evaluation.segment.contract.CaseInput;
import com.company.campaign.evaluation.segment.contract.CorpusBundle;
import com.company.campaign.evaluation.segment.contract.CorpusManifest;
import com.company.campaign.evaluation.segment.contract.EvaluationErrorCodes;
import com.company.campaign.evaluation.segment.contract.SegmentCategory;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Decides whether a corpus is Gate-ready, and separately whether it is at least candidate-complete.
 *
 * <p>Two verdicts are produced from one rule set so they can never drift apart:
 *
 * <ul>
 *   <li>{@link #validateCandidate} applies every machine rule — manifest minimums, category coverage,
 *       case semantics, corpus quality, T05 DSL binding, Metadata payload compatibility and review-ledger
 *       structure — and reports {@code CANDIDATE_COMPLETE} when nothing blocks. Pending reviews are allowed:
 *       a candidate is expected to carry ledgers that bind its exact hashes while review is still due.</li>
 *   <li>{@link #validate} adds the human-approval readiness rules, where every role must have approved the
 *       exact revision. Only this pass can be {@link EvaluationValidationReport#isGateReady()}.</li>
 * </ul>
 *
 * <p>This replaces T03's count-only readiness, which could accept a corpus with no review evidence at all.
 * It still runs the semantic validator, so a corpus is never "ready except for the things nobody checked".
 * It defines readiness only: it runs no model, computes no metric and issues no {@code PASSED} verdict.
 */
public final class GateReadinessValidator {

    private final SegmentEvaluationValidator semanticValidator = new SegmentEvaluationValidator();
    private final CorpusQualityValidator qualityValidator = new CorpusQualityValidator();
    private final MetadataPayloadValidator metadataValidator = new MetadataPayloadValidator();
    private final SegmentDslExpectationValidator dslValidator = new SegmentDslExpectationValidator();
    private final ReviewEvidenceValidator reviewValidator = new ReviewEvidenceValidator();

    /** Validates Gate readiness, including every candidate rule and the human-approval readiness rules. */
    public EvaluationValidationReport validate(CorpusBundle bundle) {
        Objects.requireNonNull(bundle, "bundle");
        List<Issue> issues = candidateIssues(bundle);
        issues.addAll(reviewValidator.validateReadiness(bundle));
        return EvaluationValidationReport.gate(issues);
    }

    /** Validates candidate completeness, ignoring only whether the required approvals exist yet. */
    public EvaluationValidationReport validateCandidate(CorpusBundle bundle) {
        Objects.requireNonNull(bundle, "bundle");
        return EvaluationValidationReport.candidate(candidateIssues(bundle));
    }

    private List<Issue> candidateIssues(CorpusBundle bundle) {
        List<Issue> issues = new ArrayList<>(countIssues(bundle));
        issues.addAll(semanticValidator.validate(bundle).issues());
        issues.addAll(qualityValidator.validate(bundle));
        issues.addAll(metadataValidator.validate(bundle));
        issues.addAll(dslValidator.validate(bundle));
        issues.addAll(reviewValidator.validateStructure(bundle));
        return issues;
    }

    private List<Issue> countIssues(CorpusBundle bundle) {
        CorpusManifest manifest = bundle.manifest();
        List<Issue> issues = new ArrayList<>();

        if (manifest.minimumCasesPerCategory() < CorpusManifest.GATE_MINIMUM_CASES_PER_CATEGORY) {
            issues.add(SegmentEvaluationValidator.issue(
                    EvaluationErrorCodes.GATE_MINIMUM_TOO_LOW, "/manifest/minimumCasesPerCategory",
                    "Gate-ready manifest must require at least "
                            + CorpusManifest.GATE_MINIMUM_CASES_PER_CATEGORY
                            + " cases per category but declares " + manifest.minimumCasesPerCategory()));
        }
        if (manifest.requiredHighRiskRepeats() < CorpusManifest.GATE_REQUIRED_HIGH_RISK_REPEATS) {
            issues.add(SegmentEvaluationValidator.issue(
                    EvaluationErrorCodes.GATE_REPEATS_TOO_LOW, "/manifest/requiredHighRiskRepeats",
                    "Gate-ready manifest must require at least "
                            + CorpusManifest.GATE_REQUIRED_HIGH_RISK_REPEATS
                            + " high-risk repeats but declares " + manifest.requiredHighRiskRepeats()));
        }

        int requiredPerCategory = Math.max(
                manifest.minimumCasesPerCategory(), CorpusManifest.GATE_MINIMUM_CASES_PER_CATEGORY);
        Map<SegmentCategory, Integer> counts = new EnumMap<>(SegmentCategory.class);
        for (CaseInput caseInput : bundle.cases()) {
            counts.merge(caseInput.category(), 1, Integer::sum);
        }
        for (SegmentCategory category : SegmentCategory.ordered()) {
            int count = counts.getOrDefault(category, 0);
            if (count == 0) {
                issues.add(SegmentEvaluationValidator.issue(
                        EvaluationErrorCodes.CATEGORY_MISSING, "/manifest/categories/" + category.name(),
                        "Gate-ready corpus must cover category " + category.name()
                                + " (spec " + category.specNumber() + ") but has no case for it"));
            } else if (count < requiredPerCategory) {
                issues.add(SegmentEvaluationValidator.issue(
                        EvaluationErrorCodes.CATEGORY_UNDER_MINIMUM, "/manifest/categories/" + category.name(),
                        "Gate-ready corpus category " + category.name() + " (spec " + category.specNumber()
                                + ") has " + count + " case(s) but requires at least " + requiredPerCategory));
            }
        }
        return issues;
    }
}