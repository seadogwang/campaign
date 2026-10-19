package com.company.campaign.segment.intent;

import com.company.campaign.contracts.SourceSpan;
import org.junit.jupiter.api.Test;

import static com.company.campaign.testsupport.ContractAssertions.assertFailsWithCode;

/**
 * Requirement 3: exact UTF-16 half-open source spans work with ASCII, Chinese and surrogate-pair text, while
 * a mismatch, a surrogate split and an out-of-range span each fail with a stable code.
 */
class SourceSpanGroundingTest {

    @Test
    void asciiSpanGroundsExactly() {
        SourceSpanValidator.validate("spend over 1000", new SourceSpan("1000", 11, 15));
    }

    @Test
    void chineseSpanGroundsExactly() {
        SourceSpanValidator.validate("Gold会员", new SourceSpan("会员", 4, 6));
    }

    @Test
    void surrogatePairSpanGroundsWhenBothHalvesAreCovered() {
        // "A" + U+1F600 (two UTF-16 units) + "B"
        SourceSpanValidator.validate("A😀B", new SourceSpan("😀", 1, 3));
    }

    @Test
    void emptySpanFailsClosed() {
        assertFailsWithCode(
                () -> SourceSpanValidator.validate("abc", new SourceSpan("", 1, 1)),
                IntentErrorCodes.SPAN_EMPTY);
    }

    @Test
    void outOfRangeSpanFailsClosed() {
        assertFailsWithCode(
                () -> SourceSpanValidator.validate("abc", new SourceSpan("abc", 0, 99)),
                IntentErrorCodes.SPAN_OUT_OF_RANGE);
    }

    @Test
    void textMismatchFailsClosed() {
        assertFailsWithCode(
                () -> SourceSpanValidator.validate("Gold会员", new SourceSpan("Silver", 0, 6)),
                IntentErrorCodes.SPAN_TEXT_MISMATCH);
    }

    @Test
    void surrogateSplitAtStartFailsClosed() {
        assertFailsWithCode(
                () -> SourceSpanValidator.validate("A😀B", new SourceSpan("\uDE00B", 2, 4)),
                IntentErrorCodes.SPAN_SURROGATE_SPLIT);
    }

    @Test
    void surrogateSplitAtEndFailsClosed() {
        assertFailsWithCode(
                () -> SourceSpanValidator.validate("A😀B", new SourceSpan("\uD83D", 1, 2)),
                IntentErrorCodes.SPAN_SURROGATE_SPLIT);
    }

    @Test
    void missingUtteranceFailsClosed() {
        assertFailsWithCode(
                () -> SourceSpanValidator.validate(null, new SourceSpan("x", 0, 1)),
                IntentErrorCodes.UTTERANCE_MISSING);
    }

    @Test
    void codecParsesSurrogateGroundedSpanAndRejectsSplit() {
        SegmentIntentCodec codec = new SegmentIntentCodec();
        String utterance = "A😀B";
        String valid = singleClauseJson("😀", 1, 3);
        codec.parse(valid, utterance, IntentFixturesRegistry.empty());

        String split = singleClauseJson("\\uDE00B", 2, 4);
        assertFailsWithCode(
                () -> codec.parse(split, utterance, IntentFixturesRegistry.empty()),
                IntentErrorCodes.SPAN_SURROGATE_SPLIT);
    }

    private static String singleClauseJson(String text, int start, int end) {
        return "{"
                + "\"intentVersion\":\"1.3\",\"subject\":\"MEMBER\",\"clauses\":[{"
                + "\"clauseId\":\"c1\",\"concept\":\"X\",\"negated\":false,"
                + "\"provenance\":{\"type\":\"SOURCE_SPAN\",\"text\":\"" + text + "\","
                + "\"start\":" + start + ",\"end\":" + end + "},"
                + "\"candidateHints\":[],\"assumptions\":[]}],"
                + "\"logicalExpression\":{\"type\":\"CLAUSE\",\"clauseId\":\"c1\"},"
                + "\"ambiguities\":[],\"unsupportedRequirements\":[]}";
    }

    /** A local empty registry keeps this test independent of the fixture package. */
    private static final class IntentFixturesRegistry {
        static RegisteredDefaultSource empty() {
            return new RegisteredDefaultSource.Registry("registered-defaults/empty", java.util.List.of());
        }
    }
}