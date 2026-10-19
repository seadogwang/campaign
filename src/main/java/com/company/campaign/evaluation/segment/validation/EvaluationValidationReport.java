package com.company.campaign.evaluation.segment.validation;

import com.company.campaign.contracts.Issue;
import com.company.campaign.shared.ImmutableValues;

import java.util.List;

/**
 * The deterministic outcome of validating a corpus, together with the stage that produced it.
 *
 * <p>The report is a list of machine-readable issues in a fixed order, not a boolean, because a Gate has
 * to explain every reason a corpus is not ready instead of stopping at the first one. Order is
 * deterministic: cases are visited in manifest order and each case emits its findings in a fixed sequence.
 *
 * <p>The {@link EvaluationStage} keeps three verdicts apart that must never be conflated:
 *
 * <ul>
 *   <li>{@link EvaluationStage#SEMANTIC} — only meaning-level checks ran. It is never a Gate verdict, and
 *       {@link #isGateReady()} is false however clean the report looks.</li>
 *   <li>{@link EvaluationStage#CANDIDATE} — the full structural, semantic and quality rules ran, but human
 *       review is only required to be <em>recorded</em> (pending is allowed). {@link #isCandidateComplete()}
 *       is the verdict that the candidate corpus is machine-complete.</li>
 *   <li>{@link EvaluationStage#GATE} — the candidate rules plus the human-approval readiness rules. Only
 *       this stage can be {@link #isGateReady()}, and only when three real independent approvals exist.</li>
 * </ul>
 *
 * <p>A candidate-complete report is deliberately not Gate-ready: the two are different claims, and T06's
 * terminal automated state is candidate-complete with Gate readiness blocked on human review.
 */
public record EvaluationValidationReport(List<Issue> issues, EvaluationStage stage) {

    public EvaluationValidationReport {
        issues = ImmutableValues.freezeList(issues == null ? List.of() : issues);
        if (stage == null) {
            throw new IllegalArgumentException("A validation report must declare the stage that produced it");
        }
    }

    /** Compatibility constructor: {@code true} means the Gate rules were applied. */
    public EvaluationValidationReport(List<Issue> issues, boolean gateEvaluated) {
        this(issues, gateEvaluated ? EvaluationStage.GATE : EvaluationStage.SEMANTIC);
    }

    /** A report from a validation pass that only applied meaning-level rules. */
    public static EvaluationValidationReport semanticsOnly(List<Issue> issues) {
        return new EvaluationValidationReport(issues, EvaluationStage.SEMANTIC);
    }

    /** A report from the full candidate-completeness pass, before human-approval readiness is applied. */
    public static EvaluationValidationReport candidate(List<Issue> issues) {
        return new EvaluationValidationReport(issues, EvaluationStage.CANDIDATE);
    }

    /** A report from the Gate readiness pass. */
    public static EvaluationValidationReport gate(List<Issue> issues) {
        return new EvaluationValidationReport(issues, EvaluationStage.GATE);
    }

    /** True when at least one finding blocks processing. */
    public boolean hasBlockingIssues() {
        return issues.stream().anyMatch(Issue::blocksProcessing);
    }

    /** True when this pass found nothing that blocks processing. */
    public boolean isValid() {
        return !hasBlockingIssues();
    }

    /** True only when the Gate rules were applied. */
    public boolean gateEvaluated() {
        return stage == EvaluationStage.GATE;
    }

    /** True only when the candidate-completeness rules were applied. */
    public boolean candidateEvaluated() {
        return stage == EvaluationStage.CANDIDATE;
    }

    /**
     * True only when the Gate rules were applied and nothing blocked.
     *
     * <p>A report that never applied the Gate rules is never Gate-ready, however clean it looks.
     */
    public boolean isGateReady() {
        return stage == EvaluationStage.GATE && !hasBlockingIssues();
    }

    /** True only when the candidate rules were applied and nothing blocked. */
    public boolean isCandidateComplete() {
        return stage == EvaluationStage.CANDIDATE && !hasBlockingIssues();
    }

    /** The issue codes, in report order, for compact assertions and reporting. */
    public List<String> issueCodes() {
        return issues.stream().map(Issue::code).toList();
    }
}