package com.company.campaign.evaluation.segment;

import com.company.campaign.contracts.SourceSpan;
import com.company.campaign.evaluation.segment.contract.CorpusBundle;
import com.company.campaign.evaluation.segment.contract.CorpusManifest;
import com.company.campaign.evaluation.segment.contract.EvaluationErrorCodes;
import com.company.campaign.evaluation.segment.contract.ExpectedIntentSlot;
import com.company.campaign.evaluation.segment.contract.RefinementOperation;
import com.company.campaign.evaluation.segment.contract.RefinementTargetPlaceholder;
import com.company.campaign.evaluation.segment.contract.RiskLevel;
import com.company.campaign.evaluation.segment.contract.SegmentCategory;
import com.company.campaign.evaluation.segment.loader.SegmentEvaluationLoader;
import com.company.campaign.evaluation.segment.validation.EvaluationValidationReport;
import com.company.campaign.evaluation.segment.validation.GateReadinessValidator;
import com.company.campaign.evaluation.segment.validation.SegmentEvaluationValidator;
import com.company.campaign.evaluation.testsupport.EvaluationCorpusFixture;
import com.fasterxml.jackson.databind.node.TextNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Gate readiness: the corpus-level requirements that a small reviewed example must fail while still
 * loading and validating cleanly.
 *
 * <p>Segment specification section 20 requires fifteen cases in each of the fifteen categories, and
 * section 23.2 requires high-risk cases to repeat five times. The generated 225-case corpus below is
 * deliberately synthetic — it exists to prove the rule accepts a corpus that meets it, not to claim a
 * reviewed corpus exists. No such corpus is checked in.
 */
class SegmentEvaluationGateTest {

    @TempDir
    Path repositoryRoot;

    // Requirement: a generated corpus that meets every rule is accepted.

    @Test
    void aGeneratedCorpusOfFifteenCasesInEveryCategoryIsGateReady() {
        writeGeneratedCorpus(15, 15, 5);

        CorpusBundle bundle = bundle();
        EvaluationValidationReport report = gateValidator().validate(bundle);

        assertThat(bundle.cases()).hasSize(225);
        assertThat(report.issues()).isEmpty();
        assertThat(report.isGateReady()).isTrue();
        assertThat(report.isValid()).isTrue();
        assertThat(SegmentCategory.values())
                .allSatisfy(category -> assertThat(bundle.cases().stream()
                        .filter(caseInput -> caseInput.category() == category).count())
                        .as("cases in %s", category)
                        .isEqualTo(15));
    }

    // Requirement: fewer than fifteen cases in any category is a Gate failure.

    @Test
    void aCategoryWithFourteenCasesIsNotGateReady() {
        writeGeneratedCorpus(15, 14, 5);

        EvaluationValidationReport report = gateValidator().validate(bundle());

        assertThat(report.isGateReady()).isFalse();
        assertThat(report.issueCodes())
                .hasSize(SegmentCategory.values().length)
                .containsOnly(EvaluationErrorCodes.CATEGORY_UNDER_MINIMUM);
    }

    @Test
    void aMissingCategoryIsNotGateReady() {
        List<SegmentCategory> withoutLast = new ArrayList<>(SegmentCategory.ordered());
        SegmentCategory omitted = withoutLast.remove(withoutLast.size() - 1);
        writeCorpus(withoutLast, 15, 15, 5);

        EvaluationValidationReport report = gateValidator().validate(bundle());

        assertThat(report.isGateReady()).isFalse();
        assertThat(report.issues()).anySatisfy(issue -> {
            assertThat(issue.code()).isEqualTo(EvaluationErrorCodes.CATEGORY_MISSING);
            assertThat(issue.path()).contains(omitted.name());
        });
    }

    // Requirement: a Gate-ready manifest must ask for at least fifteen cases and five repeats.

    @Test
    void aManifestThatRequiresTooFewCasesPerCategoryIsNotGateReady() {
        writeGeneratedCorpus(10, 15, 5);

        EvaluationValidationReport report = gateValidator().validate(bundle());

        assertThat(report.isGateReady()).isFalse();
        assertThat(report.issueCodes()).contains(EvaluationErrorCodes.GATE_MINIMUM_TOO_LOW);
        assertThat(report.issueCodes()).doesNotContain(EvaluationErrorCodes.CATEGORY_UNDER_MINIMUM);
    }

    @Test
    void aManifestThatRequiresTooFewHighRiskRepeatsIsNotGateReady() {
        writeGeneratedCorpus(15, 15, 4);

        EvaluationValidationReport report = gateValidator().validate(bundle());

        assertThat(report.isGateReady()).isFalse();
        assertThat(report.issueCodes()).containsExactly(EvaluationErrorCodes.GATE_REPEATS_TOO_LOW);
    }

    @Test
    void theGateMinimumsAreTheFifteenAndFiveFromTheSpecification() {
        assertThat(CorpusManifest.GATE_MINIMUM_CASES_PER_CATEGORY).isEqualTo(15);
        assertThat(CorpusManifest.GATE_REQUIRED_HIGH_RISK_REPEATS).isEqualTo(5);
        assertThat(SegmentCategory.values()).hasSize(15);
        assertThat(CorpusManifest.GATE_MINIMUM_CASES_PER_CATEGORY * SegmentCategory.values().length)
                .isEqualTo(225);
    }

    // Requirement: a semantic-only report can never be mistaken for a Gate verdict.

    @Test
    void aSemanticOnlyReportIsNeverGateReady() {
        writeGeneratedCorpus(15, 15, 5);

        EvaluationValidationReport semantic = new SegmentEvaluationValidator().validate(bundle());
        EvaluationValidationReport gate = gateValidator().validate(bundle());

        assertThat(semantic.issues()).isEmpty();
        assertThat(semantic.isValid()).isTrue();
        assertThat(semantic.isGateReady()).isFalse();
        assertThat(gate.isGateReady()).isTrue();
    }

    @Test
    void gateReportingIsDeterministic() {
        writeGeneratedCorpus(9, 4, 2);

        List<String> first = gateValidator().validate(bundle()).issueCodes();
        List<String> second = gateValidator().validate(bundle()).issueCodes();

        assertThat(first).isEqualTo(second);
        assertThat(first).isNotEmpty();
    }

    // The example corpus that is checked into the repository is not Gate-ready and must never be read as
    // if it were.

    @Test
    void theCheckedInExampleIsLoadableAndExplicitlyNotGateReady() {
        CorpusBundle bundle = new SegmentEvaluationLoader(Path.of(""))
                .loadCorpus("evaluation/segment/manifest.json");

        EvaluationValidationReport semantic = new SegmentEvaluationValidator().validate(bundle);
        EvaluationValidationReport gate = gateValidator().validate(bundle);

        assertThat(bundle.cases()).isNotEmpty();
        assertThat(semantic.issues()).as("the checked-in example is semantically clean").isEmpty();
        assertThat(gate.isGateReady()).as("the example must never be Gate-ready").isFalse();
        assertThat(gate.issueCodes()).contains(EvaluationErrorCodes.GATE_MINIMUM_TOO_LOW,
                EvaluationErrorCodes.GATE_REPEATS_TOO_LOW);
    }

    // --- helpers ---------------------------------------------------------------------------------

    private void writeGeneratedCorpus(int declaredMinimum, int casesPerCategory, int repeats) {
        writeCorpus(SegmentCategory.ordered(), declaredMinimum, casesPerCategory, repeats);
    }

    private void writeCorpus(
            List<SegmentCategory> categories, int declaredMinimum, int casesPerCategory, int repeats) {
        EvaluationCorpusFixture fixture = EvaluationCorpusFixture.in(repositoryRoot)
                .minimumCasesPerCategory(declaredMinimum)
                .requiredHighRiskRepeats(repeats);
        for (SegmentCategory category : categories) {
            for (int index = 0; index < casesPerCategory; index++) {
                EvaluationCorpusFixture.CaseSpec spec = EvaluationCorpusFixture.caseSpec(
                                "case-" + category.name().toLowerCase(java.util.Locale.ROOT) + "-" + index, category)
                        .risk(index == 0 ? RiskLevel.HIGH : RiskLevel.MEDIUM)
                        .utterance("Gold会员 " + index)
                        // Every case carries a distinct Intent concept, so the quality validator cannot
                        // mistake this synthetic corpus for one template with numeric suffixes.
                        .intentSlots(List.of(new ExpectedIntentSlot("c1", "MEMBER_TIER_" + index, "EQUAL",
                                true, new SourceSpan("Gold", 0, 4), TextNode.valueOf("GOLD"))));
                if (index == 0) {
                    spec.forbiddenAssumptions(List.of("ASSUME_CURRENT_TIER"));
                }
                if (category == SegmentCategory.REFINEMENT_ADD_CHANGE_DELETE_MOVE) {
                    spec.refinementTarget(new RefinementTargetPlaceholder(
                            RefinementOperation.REPLACE_FIELD, "n-tier-" + index, "n-root-" + index));
                }
                fixture.addCase(spec);
            }
        }
        fixture.write();
    }

    private CorpusBundle bundle() {
        return new SegmentEvaluationLoader(repositoryRoot).loadCorpus();
    }

    private GateReadinessValidator gateValidator() {
        return new GateReadinessValidator();
    }
}