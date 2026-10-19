package com.company.campaign.evaluation.segment;

import com.company.campaign.contracts.Disposition;
import com.company.campaign.contracts.IssueSeverity;
import com.company.campaign.contracts.LocaleTag;
import com.company.campaign.contracts.SourceSpan;
import com.company.campaign.contracts.TrustedExecutionContextRef;
import com.company.campaign.evaluation.segment.contract.CorpusBundle;
import com.company.campaign.evaluation.segment.contract.EvaluationErrorCodes;
import com.company.campaign.evaluation.segment.contract.ExpectedIntentSlot;
import com.company.campaign.evaluation.segment.contract.RiskLevel;
import com.company.campaign.evaluation.segment.contract.FixtureRole;
import com.company.campaign.evaluation.segment.contract.SegmentCategory;
import com.company.campaign.evaluation.segment.loader.SegmentEvaluationLoader;
import com.company.campaign.evaluation.segment.validation.EvaluationValidationReport;
import com.company.campaign.evaluation.segment.validation.SegmentEvaluationValidator;
import com.company.campaign.evaluation.testsupport.EvaluationCorpusFixture;
import com.fasterxml.jackson.databind.node.TextNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Semantic validation of a loaded corpus: everything the loader cannot decide because it needs the case,
 * its expectation, the catalogs and the trusted context at once.
 */
class SegmentEvaluationValidationTest {

    private static final String PRIMARY = EvaluationCorpusFixture.PRIMARY_FIXTURE_ID;
    private static final String COUNTEREXAMPLE = EvaluationCorpusFixture.COUNTEREXAMPLE_FIXTURE_ID;
    private static final String CONTEXT = EvaluationCorpusFixture.CONTEXT_FIXTURE_ID;

    @TempDir
    Path repositoryRoot;

    // --- baseline --------------------------------------------------------------------------------

    @Test
    void aWellFormedCorpusValidatesWithoutFindingsButIsNotGateReady() {
        validCorpus().write();

        EvaluationValidationReport report = semanticValidator().validate(bundle());

        assertThat(report.issues()).isEmpty();
        assertThat(report.isValid()).isTrue();
        assertThat(report.gateEvaluated()).isFalse();
        assertThat(report.isGateReady()).as("a one-case example is never Gate-ready").isFalse();
    }

    @Test
    void issuesAreBlockingDeterministicAndLocated() {
        EvaluationCorpusFixture.in(repositoryRoot)
                .addCase(EvaluationCorpusFixture.caseSpec("case-attr-001",
                        SegmentCategory.ATTRIBUTE_EQUALITY_SETS_RANGES_STRINGS).utterance("   "))
                .write();

        EvaluationValidationReport first = semanticValidator().validate(bundle());
        EvaluationValidationReport second = semanticValidator().validate(bundle());

        assertThat(first.issueCodes()).containsExactlyElementsOf(second.issueCodes());
        assertThat(first.issues()).isNotEmpty();
        assertThat(first.issues()).allSatisfy(issue -> {
            assertThat(issue.severity()).isEqualTo(IssueSeverity.ERROR);
            assertThat(issue.blocksProcessing()).isTrue();
            assertThat(issue.path()).startsWith("/cases/0/");
        });
    }

    // --- case content ----------------------------------------------------------------------------

    @Test
    void aCaseWithoutLocaleOrUtteranceOrFixtureIsRejected() {
        EvaluationCorpusFixture.in(repositoryRoot)
                .addCase(EvaluationCorpusFixture.caseSpec("case-attr-001",
                                SegmentCategory.ATTRIBUTE_EQUALITY_SETS_RANGES_STRINGS)
                        .locales(List.of())
                        .utterance("")
                        .fixtures(List.of()))
                .write();

        assertThat(semanticValidator().validate(bundle()).issueCodes())
                .contains(EvaluationErrorCodes.CASE_LOCALES_MISSING,
                        EvaluationErrorCodes.UTTERANCE_BLANK,
                        EvaluationErrorCodes.FIXTURES_MISSING);
    }

    // --- catalog resolution ----------------------------------------------------------------------

    @Test
    void aCaseThatReferencesAnUnknownMetadataSnapshotIsRejected() {
        EvaluationCorpusFixture.in(repositoryRoot)
                .addCase(EvaluationCorpusFixture.caseSpec("case-attr-001",
                        SegmentCategory.ATTRIBUTE_EQUALITY_SETS_RANGES_STRINGS)
                        .metadataSnapshot("metadata-missing"))
                .write();

        assertThat(semanticValidator().validate(bundle()).issueCodes())
                .contains(EvaluationErrorCodes.METADATA_SNAPSHOT_UNKNOWN,
                        EvaluationErrorCodes.CONTEXT_SNAPSHOT_MISMATCH);
    }

    @Test
    void aCaseThatReferencesAnUnknownFixtureIsRejected() {
        EvaluationCorpusFixture.in(repositoryRoot)
                .addCase(EvaluationCorpusFixture.caseSpec("case-attr-001",
                                SegmentCategory.ATTRIBUTE_EQUALITY_SETS_RANGES_STRINGS)
                        .fixture("fixture-missing"))
                .write();

        assertThat(semanticValidator().validate(bundle()).issueCodes())
                .containsExactly(EvaluationErrorCodes.FIXTURE_UNKNOWN);
    }

    @Test
    void anExecutionContextFixtureUsedAsADataFixtureIsRejected() {
        EvaluationCorpusFixture.in(repositoryRoot)
                .addCase(EvaluationCorpusFixture.caseSpec("case-attr-001",
                                SegmentCategory.ATTRIBUTE_EQUALITY_SETS_RANGES_STRINGS)
                        .fixture(CONTEXT))
                .write();

        assertThat(semanticValidator().validate(bundle()).issueCodes())
                .contains(EvaluationErrorCodes.FIXTURE_ROLE_MISMATCH);
    }

    @Test
    void aDataFixtureUsedAsTheExecutionContextIsRejected() {
        EvaluationCorpusFixture.in(repositoryRoot)
                .addCase(EvaluationCorpusFixture.caseSpec("case-attr-001",
                                SegmentCategory.ATTRIBUTE_EQUALITY_SETS_RANGES_STRINGS)
                        .executionContextFixture(PRIMARY))
                .write();

        assertThat(semanticValidator().validate(bundle()).issueCodes())
                .contains(EvaluationErrorCodes.EXECUTION_CONTEXT_FIXTURE_INVALID);
    }

    @Test
    void aCaseAndItsTrustedContextMustNameTheSameMetadataSnapshot() {
        EvaluationCorpusFixture.in(repositoryRoot)
                .contextPayload(new TrustedExecutionContextRef(
                        "tenant-001", "program-001", "user-001", "scope-008", "metadata-other",
                        new LocaleTag("zh-CN"), EvaluationCorpusFixture.TIMEZONE, "CNY",
                        EvaluationCorpusFixture.AS_OF, EvaluationCorpusFixture.DATA_WATERMARK))
                .addCase(EvaluationCorpusFixture.caseSpec("case-attr-001",
                        SegmentCategory.ATTRIBUTE_EQUALITY_SETS_RANGES_STRINGS))
                .write();

        assertThat(semanticValidator().validate(bundle()).issueCodes())
                .contains(EvaluationErrorCodes.CONTEXT_SNAPSHOT_MISMATCH);
    }

    // --- fixture time context --------------------------------------------------------------------

    @Test
    void aFixtureDescriptorThatDisagreesWithItsContextPayloadIsRejected() {
        EvaluationCorpusFixture.in(repositoryRoot)
                .contextDescriptorTimeContext(EvaluationCorpusFixture.AS_OF,
                        EvaluationCorpusFixture.TIMEZONE,
                        Instant.parse("2026-10-01T01:00:00Z"))
                .addCase(EvaluationCorpusFixture.caseSpec("case-attr-001",
                        SegmentCategory.ATTRIBUTE_EQUALITY_SETS_RANGES_STRINGS))
                .write();

        assertThat(semanticValidator().validate(bundle()).issueCodes())
                .contains(EvaluationErrorCodes.FIXTURE_TIME_CONTEXT_MISMATCH);
    }

    @Test
    void fixturesComparedByOneCaseMustShareOneFixedTimeContext() {
        EvaluationCorpusFixture.in(repositoryRoot)
                .fixture(COUNTEREXAMPLE, FixtureRole.COUNTEREXAMPLE,
                        List.of("m-001", "m-002"))
                .dataFixtureDescriptorTimeContext(Instant.parse("2026-09-01T02:00:00Z"),
                        ZoneId.of("Asia/Shanghai"), Instant.parse("2026-09-01T01:55:00Z"))
                .addCase(EvaluationCorpusFixture.caseSpec("case-attr-001",
                        SegmentCategory.ATTRIBUTE_EQUALITY_SETS_RANGES_STRINGS)
                        .complexSemantics(true)
                        .fixture(COUNTEREXAMPLE))
                .write();

        // Both the context fixture and the data fixture disagree with the case's fixed instant, and the
        // complex-fixture requirement is still satisfied.
        assertThat(semanticValidator().validate(bundle()).issueCodes())
                .containsExactly(EvaluationErrorCodes.FIXTURE_TIME_CONTEXT_MISMATCH,
                        EvaluationErrorCodes.FIXTURE_TIME_CONTEXT_MISMATCH);
    }

    // --- expected member sets --------------------------------------------------------------------

    @Test
    void aReferencedFixtureWithoutAnExpectedMemberSetIsRejected() {
        EvaluationCorpusFixture.in(repositoryRoot)
                .addCase(EvaluationCorpusFixture.caseSpec("case-attr-001",
                                SegmentCategory.ATTRIBUTE_EQUALITY_SETS_RANGES_STRINGS)
                        .memberSet(PRIMARY, List.of("m-001"))
                        .memberSet("fixture-orphan", List.of("m-001")))
                .write();

        assertThat(semanticValidator().validate(bundle()).issueCodes())
                .contains(EvaluationErrorCodes.EXPECTED_SET_ORPHANED);
    }

    @Test
    void anExpectedMemberOutsideTheFixtureUniverseIsRejected() {
        EvaluationCorpusFixture.in(repositoryRoot)
                .addCase(EvaluationCorpusFixture.caseSpec("case-attr-001",
                                SegmentCategory.ATTRIBUTE_EQUALITY_SETS_RANGES_STRINGS)
                        .memberSet(PRIMARY, List.of("m-001", "m-999")))
                .write();

        assertThat(semanticValidator().validate(bundle()).issueCodes())
                .contains(EvaluationErrorCodes.MEMBER_ID_OUTSIDE_UNIVERSE);
    }

    // --- complex semantics -----------------------------------------------------------------------

    @Test
    void aComplexCaseNeedsTwoFixturesIncludingACounterexample() {
        EvaluationCorpusFixture.in(repositoryRoot)
                .addCase(EvaluationCorpusFixture.caseSpec("case-single",
                                SegmentCategory.MULTIPLE_EXISTS_NODES_DIFFERENT_ROWS)
                        .complexSemantics(true))
                .write();

        assertThat(semanticValidator().validate(bundle()).issueCodes())
                .contains(EvaluationErrorCodes.COMPLEX_FIXTURES_INSUFFICIENT);
    }

    @Test
    void aComplexCaseWithTwoPrimariesAndNoCounterexampleIsRejected() {
        EvaluationCorpusFixture.in(repositoryRoot)
                .fixture("fixture-primary-002",
                        FixtureRole.PRIMARY,
                        List.of("m-001", "m-002"))
                .addCase(EvaluationCorpusFixture.caseSpec("case-complex",
                                SegmentCategory.MULTIPLE_EXISTS_NODES_DIFFERENT_ROWS)
                        .complexSemantics(true)
                        .fixture("fixture-primary-002"))
                .write();

        assertThat(semanticValidator().validate(bundle()).issueCodes())
                .contains(EvaluationErrorCodes.COMPLEX_FIXTURES_INSUFFICIENT);
    }

    @Test
    void aComplexCaseWithACounterexampleFixtureIsAccepted() {
        EvaluationCorpusFixture.in(repositoryRoot)
                .fixture(COUNTEREXAMPLE, FixtureRole.COUNTEREXAMPLE, List.of("m-001", "m-002"))
                .addCase(EvaluationCorpusFixture.caseSpec("case-complex",
                                SegmentCategory.MULTIPLE_EXISTS_NODES_DIFFERENT_ROWS)
                        .complexSemantics(true)
                        .fixture(COUNTEREXAMPLE))
                .write();

        assertThat(semanticValidator().validate(bundle()).issueCodes()).isEmpty();
    }

    // --- source spans ----------------------------------------------------------------------------

    @Test
    void aGroundedSlotWithoutSourceEvidenceIsRejected() {
        EvaluationCorpusFixture.in(repositoryRoot)
                .addCase(EvaluationCorpusFixture.caseSpec("case-attr-001",
                                SegmentCategory.ATTRIBUTE_EQUALITY_SETS_RANGES_STRINGS)
                        .intentSlots(List.of(new ExpectedIntentSlot(
                                "c1", "MEMBER_TIER", "EQUAL", true, null, TextNode.valueOf("GOLD")))))
                .write();

        assertThat(semanticValidator().validate(bundle()).issueCodes())
                .contains(EvaluationErrorCodes.SOURCE_SPAN_MISSING);
    }

    @Test
    void anUngroundedSlotNeedsNoSourceEvidence() {
        EvaluationCorpusFixture.in(repositoryRoot)
                .addCase(EvaluationCorpusFixture.caseSpec("case-attr-001",
                                SegmentCategory.ATTRIBUTE_EQUALITY_SETS_RANGES_STRINGS)
                        .intentSlots(List.of(new ExpectedIntentSlot(
                                "c1", "MEMBER_TIER", "EQUAL", false, null, TextNode.valueOf("GOLD")))))
                .write();

        assertThat(semanticValidator().validate(bundle()).issueCodes()).isEmpty();
    }

    @Test
    void aSourceSpanOutsideTheUtteranceIsRejected() {
        EvaluationCorpusFixture.in(repositoryRoot)
                .addCase(EvaluationCorpusFixture.caseSpec("case-attr-001",
                                SegmentCategory.ATTRIBUTE_EQUALITY_SETS_RANGES_STRINGS)
                        .intentSlots(List.of(new ExpectedIntentSlot("c1", "MEMBER_TIER", "EQUAL", true,
                                new SourceSpan("Gold会员XYZ", 0, 12), TextNode.valueOf("GOLD")))))
                .write();

        assertThat(semanticValidator().validate(bundle()).issueCodes())
                .contains(EvaluationErrorCodes.SOURCE_SPAN_OUT_OF_BOUNDS);
    }

    @Test
    void aSourceSpanThatDisagreesWithTheUtteranceTextIsRejected() {
        EvaluationCorpusFixture.in(repositoryRoot)
                .addCase(EvaluationCorpusFixture.caseSpec("case-attr-001",
                                SegmentCategory.ATTRIBUTE_EQUALITY_SETS_RANGES_STRINGS)
                        .intentSlots(List.of(new ExpectedIntentSlot("c1", "MEMBER_TIER", "EQUAL", true,
                                new SourceSpan("Silver", 0, 4), TextNode.valueOf("GOLD")))))
                .write();

        assertThat(semanticValidator().validate(bundle()).issueCodes())
                .contains(EvaluationErrorCodes.SOURCE_SPAN_TEXT_MISMATCH);
    }

    // --- disposition coherence -------------------------------------------------------------------

    @Test
    void needsInputRequiresClarificationQuestionCodes() {
        EvaluationCorpusFixture.in(repositoryRoot)
                .addCase(EvaluationCorpusFixture.caseSpec("case-clarify",
                                SegmentCategory.MULTIPLE_CANDIDATES_AND_REQUIRED_CLARIFICATION)
                        .disposition(Disposition.NEEDS_INPUT))
                .write();

        assertThat(semanticValidator().validate(bundle()).issueCodes())
                .contains(EvaluationErrorCodes.CLARIFICATION_CODES_MISSING);
    }

    @Test
    void needsInputWithQuestionCodesIsAccepted() {
        EvaluationCorpusFixture.in(repositoryRoot)
                .addCase(EvaluationCorpusFixture.caseSpec("case-clarify",
                                SegmentCategory.MULTIPLE_CANDIDATES_AND_REQUIRED_CLARIFICATION)
                        .disposition(Disposition.NEEDS_INPUT)
                        .clarificationCodes(List.of("SELECT_SPEND_DEFINITION")))
                .write();

        assertThat(semanticValidator().validate(bundle()).issueCodes()).isEmpty();
    }

    @Test
    void nonResolvedDispositionsMustNotCarryAnExpectedCanonicalAst() {
        for (Disposition disposition : List.of(
                Disposition.NEEDS_INPUT, Disposition.UNSUPPORTED, Disposition.DENIED)) {
            EvaluationCorpusFixture.in(repositoryRoot)
                    .addCase(EvaluationCorpusFixture.caseSpec("case-ast",
                                    SegmentCategory.UNSUPPORTED_CONCEPTS_AND_RAW_SQL)
                            .disposition(disposition)
                            .clarificationCodes(List.of("WHICH_DEFINITION"))
                            .canonicalAst(TextNode.valueOf("{\"type\":\"ALL\"}")))
                    .write();

            assertThat(semanticValidator().validate(bundle()).issueCodes())
                    .as("expected AST must be refused for %s", disposition)
                    .contains(EvaluationErrorCodes.EXPECTED_AST_NOT_PERMITTED);
        }
    }

    @Test
    void aResolvedDispositionMayCarryAnExpectedCanonicalAst() {
        EvaluationCorpusFixture.in(repositoryRoot)
                .addCase(EvaluationCorpusFixture.caseSpec("case-ast",
                                SegmentCategory.ATTRIBUTE_EQUALITY_SETS_RANGES_STRINGS)
                        .canonicalAst(TextNode.valueOf("{\"type\":\"ALL\"}")))
                .write();

        assertThat(semanticValidator().validate(bundle()).issueCodes()).isEmpty();
    }

    // --- helpers ---------------------------------------------------------------------------------

    private EvaluationCorpusFixture validCorpus() {
        return EvaluationCorpusFixture.in(repositoryRoot)
                .addCase(EvaluationCorpusFixture.caseSpec("case-attr-001",
                        SegmentCategory.ATTRIBUTE_EQUALITY_SETS_RANGES_STRINGS).risk(RiskLevel.HIGH));
    }

    private SegmentEvaluationValidator semanticValidator() {
        return new SegmentEvaluationValidator();
    }

    private CorpusBundle bundle() {
        return new SegmentEvaluationLoader(repositoryRoot).loadCorpus();
    }
}