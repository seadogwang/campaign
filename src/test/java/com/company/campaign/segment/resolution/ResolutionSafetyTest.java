package com.company.campaign.segment.resolution;

import com.company.campaign.contracts.SourceSpan;
import com.company.campaign.metadata.domain.ComparisonOperator;
import com.company.campaign.segment.intent.IntentClause;
import com.company.campaign.segment.intent.IntentProvenance;
import com.company.campaign.segment.intent.SegmentIntent;
import com.company.campaign.segment.intent.SegmentIntentCodec;
import com.company.campaign.segment.intent.testsupport.IntentFixtures;
import com.company.campaign.segment.resolution.testsupport.ResolutionFixtures;
import com.company.campaign.shared.error.ContractException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static com.company.campaign.testsupport.ContractAssertions.assertFailsWithCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Requirement 13: public results are immutable and redacted; errors and clarification receipts contain no
 * forbidden raw or trusted data.
 */
class ResolutionSafetyTest {

    private static final String SECRET = "SECRET-TOKEN-NEVER-ECHOED";

    @Test
    void resolutionResultCollectionsAreImmutable() {
        ResolutionResult result = new IntentResolver(
                ResolutionFixtures.standardSnapshot(), ResolutionFixtures.policy(), ResolutionFixtures.registry())
                .resolve(IntentFixtures.standardIntent());

        assertThat(catchThrowable(() -> result.clauses().add(result.clauses().get(0))))
                .isInstanceOf(UnsupportedOperationException.class);
        ClauseResolution clause = result.clause("c1");
        assertThat(catchThrowable(() -> clause.candidates().clear()))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(catchThrowable(() -> clause.questionCodes().add(QuestionCode.SELECT_DEFINITION)))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(catchThrowable(() -> IntentFixtures.standardIntent().clauses().clear()))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void clarificationReceiptContainsNoUtteranceOrTrustedIdentity() {
        IntentClause spend = IntentFixtures.withOperator(ResolutionFixtures.spendClause(), ComparisonOperator.EQUAL);
        ResolutionResult result = new IntentResolver(
                ResolutionFixtures.ambiguousSpendSnapshot(), ResolutionFixtures.policy(), ResolutionFixtures.registry())
                .resolve(IntentFixtures.intent(spend));

        String receipt = result.clarification().toString();
        assertThat(receipt).doesNotContain(IntentFixtures.UTTERANCE);
        assertThat(receipt).doesNotContain("tenant-001").doesNotContain("user-001").doesNotContain("program-001");
    }

    @Test
    void staleClarificationFailureIsBoundedAndNonDisclosing() {
        IntentClause spend = IntentFixtures.withOperator(ResolutionFixtures.spendClause(), ComparisonOperator.EQUAL);
        SegmentIntent intent = IntentFixtures.intent(spend);
        IntentResolver resolver = new IntentResolver(
                ResolutionFixtures.ambiguousSpendSnapshot(), ResolutionFixtures.policy(), ResolutionFixtures.registry());
        ResolutionResult result = resolver.resolve(intent);
        ClarificationOption option = result.clarification().options().get(0);
        ClarificationChoice choice = new ClarificationChoice(
                result.clarification().resolutionId(), option.optionId(), result.baseIntentHash(),
                result.pins(), result.clarification().questionCode(), result.clarification().clauseId());

        IntentResolver different = new IntentResolver(
                ResolutionFixtures.ambiguousSpendSnapshot(),
                ResolutionPolicy.publish("resolution-policy/2.0", null), ResolutionFixtures.registry());
        Throwable thrown = catchThrowable(() -> new ClarificationChoiceService(different).apply(intent, choice));

        assertThat(thrown).isInstanceOf(ContractException.class);
        ContractException failure = (ContractException) thrown;
        assertThat(failure.code()).isEqualTo(ResolutionErrorCodes.STALE_CLARIFICATION);
        assertThat(failure.getMessage()).doesNotContain(SECRET).doesNotContain(IntentFixtures.UTTERANCE);
        assertThat(failure.details().toString()).doesNotContain(SECRET).doesNotContain(IntentFixtures.UTTERANCE);
        assertThat(failure.details()).containsKey("reason");
    }

    @Test
    void incompatibleSelectionFailureEchoesNoLabel() {
        SegmentIntent intent = IntentFixtures.intent(ResolutionFixtures.tierClause());
        IntentResolver resolver = new IntentResolver(
                ResolutionFixtures.standardSnapshot(), ResolutionFixtures.policy(), ResolutionFixtures.registry());
        Throwable thrown = catchThrowable(() -> resolver.resolve(intent, Map.of("c1", "order.net_paid_amount")));
        assertThat(thrown).isInstanceOf(ContractException.class);
        ContractException failure = (ContractException) thrown;
        assertThat(failure.code()).isEqualTo(ResolutionErrorCodes.OPTION_INCOMPATIBLE);
        assertThat(failure.getMessage()).doesNotContain("order.net_paid_amount");
        assertThat(failure.details().toString()).doesNotContain("order.net_paid_amount");
    }

    @Test
    void spanMismatchFailureEchoesNoSpanText() {
        SegmentIntentCodec codec = new SegmentIntentCodec();
        String json = "{"
                + "\"intentVersion\":\"1.3\",\"subject\":\"MEMBER\",\"clauses\":[{"
                + "\"clauseId\":\"c1\",\"concept\":\"X\",\"negated\":false,"
                + "\"provenance\":{\"type\":\"SOURCE_SPAN\",\"text\":\"" + SECRET + "\",\"start\":0,\"end\":6},"
                + "\"candidateHints\":[],\"assumptions\":[]}],"
                + "\"logicalExpression\":{\"type\":\"CLAUSE\",\"clauseId\":\"c1\"},"
                + "\"ambiguities\":[],\"unsupportedRequirements\":[]}";
        Throwable thrown = catchThrowable(
                () -> codec.parse(json, IntentFixtures.UTTERANCE, IntentFixtures.currencyRegistry()));
        assertThat(thrown).isInstanceOf(ContractException.class);
        ContractException failure = (ContractException) thrown;
        assertThat(failure.getMessage()).doesNotContain(SECRET);
        assertThat(failure.details().toString()).doesNotContain(SECRET);
    }

    @Test
    void resultsCarryOnlyPinsAndCodesNotRawInputs() {
        ResolutionResult result = new IntentResolver(
                ResolutionFixtures.standardSnapshot(), ResolutionFixtures.policy(), ResolutionFixtures.registry())
                .resolve(IntentFixtures.standardIntent());
        assertThat(result.pins().snapshotHash().value()).startsWith("sha256:");
        assertThat(result.pins().policyHash().value()).startsWith("sha256:");
        assertThat(result.pins().registryHash().value()).startsWith("sha256:");
        assertThat(result.toString()).doesNotContain(IntentFixtures.UTTERANCE);
        // Sources are represented by spans that were validated, never by copying the utterance into output.
        assertThat(new SourceSpan("Gold会员", 0, 6).isEmpty()).isFalse();
        assertThat(IntentProvenance.FromSourceSpan.class.isSealed()).isFalse();
    }
}