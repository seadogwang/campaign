package com.company.campaign.segment.intent;

import com.company.campaign.segment.intent.testsupport.IntentFixtures;
import org.junit.jupiter.api.Test;

import static com.company.campaign.testsupport.ContractAssertions.assertFailsWithCode;

/**
 * Requirement 2: unknown fields, missing/null/oversized values, duplicate and dangling clause references,
 * invalid NOT, depth and width overflow, and malformed discriminators all fail closed with stable codes.
 */
class SegmentIntentStrictnessTest {

    private final SegmentIntentCodec codec = new SegmentIntentCodec();
    private final String valid = IntentFixtures.standardIntentJson();

    @Test
    void unknownPropertyAtRootFailsClosed() {
        String json = valid.replace("\"subject\":\"MEMBER\",", "\"subject\":\"MEMBER\",\"extra\":1,");
        assertFailsWithCode(() -> parse(json), IntentErrorCodes.UNKNOWN_FIELD);
    }

    @Test
    void reservedTrustedPropertyFailsClosed() {
        String json = valid.replace("\"subject\":\"MEMBER\",", "\"subject\":\"MEMBER\",\"dataScopeId\":\"scope-1\",");
        assertFailsWithCode(() -> parse(json), IntentErrorCodes.RESERVED_FIELD);
    }

    @Test
    void unknownPropertyInsideClauseFailsClosed() {
        String json = valid.replace("\"candidateHints\":[],\"assumptions\":[]}",
                "\"candidateHints\":[],\"assumptions\":[],\"sql\":\"DROP\"}");
        assertFailsWithCode(() -> parse(json), IntentErrorCodes.RESERVED_FIELD);
    }

    @Test
    void missingRequiredPropertyFailsClosed() {
        String json = valid.replace("\"intentVersion\":\"1.3\",", "");
        assertFailsWithCode(() -> parse(json), IntentErrorCodes.SHAPE_INVALID);
    }

    @Test
    void nullRequiredPropertyFailsClosed() {
        String json = valid.replace("\"concept\":\"MEMBER_TIER\"", "\"concept\":null");
        assertFailsWithCode(() -> parse(json), IntentErrorCodes.SHAPE_INVALID);
    }

    @Test
    void oversizedTextFailsClosed() {
        String huge = "x".repeat(IntentLimits.MAX_TEXT_LENGTH + 1);
        String json = valid.replace("\"raw\":\"Gold\"", "\"raw\":\"" + huge + "\"");
        assertFailsWithCode(() -> parse(json), IntentErrorCodes.TEXT_INVALID);
    }

    @Test
    void duplicateJsonKeyFailsClosed() {
        String json = valid.replace("\"intentVersion\":\"1.3\",", "\"intentVersion\":\"1.3\",\"intentVersion\":\"1.3\",");
        assertFailsWithCode(() -> parse(json), IntentErrorCodes.JSON_DUPLICATE_KEY);
    }

    @Test
    void duplicateClauseIdFailsClosed() {
        String json = valid.replace("\"clauseId\":\"c2\"", "\"clauseId\":\"c1\"");
        assertFailsWithCode(() -> parse(json), IntentErrorCodes.DUPLICATE_CLAUSE_ID);
    }

    @Test
    void danglingExpressionReferenceFailsClosed() {
        String json = valid.replace("{\"type\":\"CLAUSE\",\"clauseId\":\"c2\"}", "{\"type\":\"CLAUSE\",\"clauseId\":\"c9\"}");
        assertFailsWithCode(() -> parse(json), IntentErrorCodes.EXPRESSION_DANGLING_REFERENCE);
    }

    @Test
    void repeatedExpressionReferenceFailsClosed() {
        String json = valid.replace("{\"type\":\"CLAUSE\",\"clauseId\":\"c2\"}", "{\"type\":\"CLAUSE\",\"clauseId\":\"c1\"}");
        assertFailsWithCode(() -> parse(json), IntentErrorCodes.EXPRESSION_DUPLICATE_REFERENCE);
    }

    @Test
    void unaryNotWithChildrenFailsClosed() {
        String json = valid.replace(
                "\"logicalExpression\":{\"type\":\"ALL\",\"children\":[{\"type\":\"CLAUSE\",\"clauseId\":\"c1\"},{\"type\":\"CLAUSE\",\"clauseId\":\"c2\"}]}",
                "\"logicalExpression\":{\"type\":\"NOT\",\"children\":[{\"type\":\"CLAUSE\",\"clauseId\":\"c1\"}]}");
        assertFailsWithCode(() -> parse(json), IntentErrorCodes.EXPRESSION_NOT_ARITY);
    }

    @Test
    void groupWithSingleChildFailsClosed() {
        String json = valid.replace(
                "\"logicalExpression\":{\"type\":\"ALL\",\"children\":[{\"type\":\"CLAUSE\",\"clauseId\":\"c1\"},{\"type\":\"CLAUSE\",\"clauseId\":\"c2\"}]}",
                "\"logicalExpression\":{\"type\":\"ALL\",\"children\":[{\"type\":\"CLAUSE\",\"clauseId\":\"c1\"}]}");
        assertFailsWithCode(() -> parse(json), IntentErrorCodes.EXPRESSION_WIDTH_INVALID);
    }

    @Test
    void expressionDepthOverflowFailsClosed() {
        StringBuilder expression = new StringBuilder("{\"type\":\"CLAUSE\",\"clauseId\":\"c1\"}");
        for (int index = 0; index < IntentLimits.MAX_EXPRESSION_DEPTH + 4; index++) {
            expression.insert(0, "{\"type\":\"NOT\",\"child\":").append("}");
        }
        String json = valid.replace(
                "\"logicalExpression\":{\"type\":\"ALL\",\"children\":[{\"type\":\"CLAUSE\",\"clauseId\":\"c1\"},{\"type\":\"CLAUSE\",\"clauseId\":\"c2\"}]}",
                "\"logicalExpression\":" + expression);
        assertFailsWithCode(() -> parse(json), IntentErrorCodes.LIMIT_EXCEEDED);
    }

    @Test
    void malformedValueDiscriminatorFailsClosed() {
        String json = valid.replace("\"kind\":\"ENUM\"", "\"kind\":\"BOGUS\"");
        assertFailsWithCode(() -> parse(json), IntentErrorCodes.UNKNOWN_DISCRIMINATOR);
    }

    @Test
    void malformedLogicalDiscriminatorFailsClosed() {
        String json = valid.replace("\"logicalExpression\":{\"type\":\"ALL\"", "\"logicalExpression\":{\"type\":\"XOR\"");
        assertFailsWithCode(() -> parse(json), IntentErrorCodes.UNKNOWN_DISCRIMINATOR);
    }

    @Test
    void unsupportedVersionFailsClosed() {
        String json = valid.replace("\"intentVersion\":\"1.3\"", "\"intentVersion\":\"1.2\"");
        assertFailsWithCode(() -> parse(json), IntentErrorCodes.VERSION_UNSUPPORTED);
    }

    @Test
    void unsupportedSubjectFailsClosed() {
        String json = valid.replace("\"subject\":\"MEMBER\"", "\"subject\":\"ORDER\"");
        assertFailsWithCode(() -> parse(json), IntentErrorCodes.SUBJECT_INVALID);
    }

    @Test
    void oversizedListValueFailsClosed() {
        StringBuilder items = new StringBuilder();
        for (int index = 0; index < IntentLimits.MAX_LIST_ITEMS + 1; index++) {
            if (index > 0) {
                items.append(',');
            }
            items.append("{\"kind\":\"ENUM\",\"raw\":\"V").append(index).append("\"}");
        }
        String json = valid.replace(
                "\"value\":{\"kind\":\"ENUM\",\"raw\":\"Gold\"}",
                "\"value\":{\"kind\":\"LIST\",\"items\":[" + items + "]}");
        assertFailsWithCode(() -> parse(json), IntentErrorCodes.LIMIT_EXCEEDED);
    }

    private SegmentIntent parse(String json) {
        return codec.parse(json, IntentFixtures.UTTERANCE, IntentFixtures.currencyRegistry());
    }
}