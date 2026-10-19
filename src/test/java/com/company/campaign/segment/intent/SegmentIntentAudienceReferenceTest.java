package com.company.campaign.segment.intent;

import com.company.campaign.contracts.SourceSpan;
import com.company.campaign.segment.intent.testsupport.IntentFixtures;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.company.campaign.testsupport.ContractAssertions.assertFailsWithCode;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T18 section 6: the Existing Audience Reference is a first-class Intent condition with an exact grounded
 * source span, natural-language candidates instead of a version pin, and no representable trusted fact.
 */
class SegmentIntentAudienceReferenceTest {

    private final SegmentIntentCodec codec = new SegmentIntentCodec();

    private SegmentIntent parse(String json) {
        return codec.parse(json, IntentFixtures.REFERENCE_UTTERANCE, IntentFixtures.emptyRegistry());
    }

    // ------------------------------------------------------------------ the representable shape

    @Test
    void referenceRoundTripsThroughTheStrictCodec() {
        SegmentIntent parsed = parse(IntentFixtures.referenceIntentJson());

        assertEquals(1, parsed.audienceReferences().size());
        IntentAudienceReference reference = parsed.audienceReferences().get(0);
        assertEquals("ref-1", reference.referenceId());
        assertEquals(List.of(IntentFixtures.AUDIENCE_PHRASE), reference.candidateTexts());
        assertEquals(
                new SourceSpan(
                        IntentFixtures.AUDIENCE_PHRASE,
                        IntentFixtures.AUDIENCE_START,
                        IntentFixtures.AUDIENCE_START + IntentFixtures.AUDIENCE_PHRASE.length()),
                ((IntentProvenance.FromSourceSpan) reference.provenance()).span());

        String rewritten = codec.writeString(parsed);
        SegmentIntent reparsed = parse(rewritten);
        assertEquals(parsed, reparsed);
        assertEquals(SegmentIntentHasher.hash(parsed), SegmentIntentHasher.hash(reparsed));
    }

    @Test
    void referenceIsAddressedThroughTheOneLogicalExpressionLeaf() {
        SegmentIntent parsed = parse(IntentFixtures.referenceIntentJson());

        assertTrue(parsed.hasAudienceReferences());
        assertNotNull(parsed.audienceReference("ref-1"));
        assertTrue(parsed.clauseIds().contains("c1"));
        assertFalse(parsed.clauseIds().contains("ref-1"));
    }

    @Test
    void referenceOnlyIntentIsRepresentable() {
        String json = IntentFixtures.referenceIntentJson()
                .replace("\"clauses\":[{\"clauseId\":\"c1\",\"concept\":\"SPEND\",\"definitionKind\":\"METRIC\","
                        + "\"negated\":false,\"operator\":\"GREATER_THAN\",\"value\":{\"kind\":\"MONEY\","
                        + "\"raw\":\"1000\"},"
                        + "\"window\":{\"kind\":\"ROLLING\",\"amount\":90,\"unit\":\"DAY\"},"
                        + "\"currency\":{\"mode\":\"EXPLICIT\",\"code\":\"CNY\"},\"aggregation\":\"SUM\","
                        + "\"provenance\":{\"type\":\"SOURCE_SPAN\","
                        + "\"text\":\"过去90天消费超过1000元\",\"start\":0,\"end\":14},"
                        + "\"candidateHints\":[],\"assumptions\":[]}]", "\"clauses\":[]")
                .replace("{\"type\":\"ALL\",\"children\":[{\"type\":\"CLAUSE\",\"clauseId\":\"c1\"},"
                        + "{\"type\":\"CLAUSE\",\"clauseId\":\"ref-1\"}]}",
                        "{\"type\":\"CLAUSE\",\"clauseId\":\"ref-1\"}");

        SegmentIntent parsed = parse(json);

        assertTrue(parsed.clauses().isEmpty());
        assertEquals(1, parsed.audienceReferences().size());
        assertEquals("ref-1", parsed.audienceReferences().get(0).referenceId());
    }

    @Test
    void negationOverAReferenceIsRepresentable() {
        String json = IntentFixtures.referenceIntentJson()
                .replace("{\"type\":\"ALL\",\"children\":[{\"type\":\"CLAUSE\",\"clauseId\":\"c1\"},"
                        + "{\"type\":\"CLAUSE\",\"clauseId\":\"ref-1\"}]}",
                        "{\"type\":\"ALL\",\"children\":[{\"type\":\"CLAUSE\",\"clauseId\":\"c1\"},"
                                + "{\"type\":\"NOT\",\"child\":{\"type\":\"CLAUSE\",\"clauseId\":\"ref-1\"}}]}");

        SegmentIntent parsed = parse(json);

        assertEquals(1, parsed.audienceReferences().size());
        assertEquals(SegmentIntentHasher.hash(parsed), SegmentIntentHasher.hash(parse(codec.writeString(parsed))));
    }

    // ------------------------------------------------------------------ no-reference preservation

    @Test
    void intentWithoutReferencesProjectsNoReferenceMember() {
        SegmentIntent plain = codec.parse(
                IntentFixtures.standardIntentJson(), IntentFixtures.UTTERANCE, IntentFixtures.emptyRegistry());

        assertFalse(plain.hasAudienceReferences());
        assertTrue(plain.audienceReferences().isEmpty());
        assertFalse(codec.writeString(plain).contains("audienceReferences"));
    }

    // ------------------------------------------------------------------ candidates are not pins

    @Test
    void referenceHashCoversIdentityCandidatesAndProvenance() {
        SegmentIntent base = parse(IntentFixtures.referenceIntentJson());
        String otherIdentity = IntentFixtures.referenceIntentJson().replace("\"ref-1\"", "\"ref-2\"");
        String otherCandidate = IntentFixtures.referenceIntentJson()
                .replace("[" + "\"" + IntentFixtures.AUDIENCE_PHRASE + "\"]", "[\"VIP\"]");

        assertNotEquals(SegmentIntentHasher.hash(base), SegmentIntentHasher.hash(parse(otherIdentity)));
        assertNotEquals(SegmentIntentHasher.hash(base), SegmentIntentHasher.hash(parse(otherCandidate)));
    }

    // ------------------------------------------------------------------ forged trusted facts

    @Test
    void forgedVersionPinIsRefusedAsReserved() {
        for (String forged : List.of(
                "\"versionId\":\"version-7\"",
                "\"versionHash\":\"" + IntentFixtures.hash("v").value() + "\"",
                "\"audienceId\":\"audience-vip\"",
                "\"audienceVersion\":\"version-7\"",
                "\"current\":true",
                "\"latest\":true")) {
            String json = IntentFixtures.referenceIntentJson()
                    .replace("\"candidateTexts\"", forged + ",\"candidateTexts\"");
            assertFailsWithCode(() -> parse(json), IntentErrorCodes.RESERVED_FIELD);
        }
    }

    @Test
    void forgedTrustedContextFactsAreRefusedAsReserved() {
        for (String forged : List.of(
                "\"tenantId\":\"tenant-1\"",
                "\"programId\":\"program-1\"",
                "\"dataScopeId\":\"scope-1\"",
                "\"permission\":\"SEGMENT_EVALUATE\"",
                "\"dependencies\":[]",
                "\"retention\":\"hold-1\"",
                "\"readiness\":\"ACTIVE\"",
                "\"capabilityId\":\"segment.existing-audience-reference\"")) {
            String json = IntentFixtures.referenceIntentJson()
                    .replace("\"candidateTexts\"", forged + ",\"candidateTexts\"");
            assertFailsWithCode(() -> parse(json), IntentErrorCodes.RESERVED_FIELD);
        }
    }

    @Test
    void unknownReferencePropertyIsRefused() {
        String json = IntentFixtures.referenceIntentJson()
                .replace("\"candidateTexts\"", "\"displayLabel\":\"VIP\",\"candidateTexts\"");

        assertFailsWithCode(() -> parse(json), IntentErrorCodes.UNKNOWN_FIELD);
    }

    // ------------------------------------------------------------------ structural bounds

    @Test
    void referenceMustNameAtLeastOneCandidate() {
        String json = IntentFixtures.referenceIntentJson()
                .replace("[\"" + IntentFixtures.AUDIENCE_PHRASE + "\"]", "[]");

        assertFailsWithCode(() -> parse(json), IntentErrorCodes.AUDIENCE_REFERENCE_INVALID);
    }

    @Test
    void repeatedCandidateIsRefused() {
        String json = IntentFixtures.referenceIntentJson()
                .replace("[\"" + IntentFixtures.AUDIENCE_PHRASE + "\"]",
                        "[\"" + IntentFixtures.AUDIENCE_PHRASE + "\",\"" + IntentFixtures.AUDIENCE_PHRASE + "\"]");

        assertFailsWithCode(() -> parse(json), IntentErrorCodes.AUDIENCE_REFERENCE_INVALID);
    }

    @Test
    void referenceIdentitySharesOneNamespaceWithClauses() {
        String json = IntentFixtures.referenceIntentJson().replace("\"referenceId\":\"ref-1\"",
                "\"referenceId\":\"c1\"");

        assertFailsWithCode(() -> parse(json), IntentErrorCodes.DUPLICATE_CLAUSE_ID);
    }

    @Test
    void unreferencedReferenceFailsClosed() {
        String json = IntentFixtures.referenceIntentJson()
                .replace("{\"type\":\"ALL\",\"children\":[{\"type\":\"CLAUSE\",\"clauseId\":\"c1\"},"
                        + "{\"type\":\"CLAUSE\",\"clauseId\":\"ref-1\"}]}",
                        "{\"type\":\"CLAUSE\",\"clauseId\":\"c1\"}");

        assertFailsWithCode(() -> parse(json), IntentErrorCodes.EXPRESSION_UNREFERENCED_CLAUSE);
    }

    @Test
    void referenceSpanMustBeGroundedInTheUtterance() {
        String json = IntentFixtures.referenceIntentJson().replace("\"start\":17,\"end\":24", "\"start\":0,\"end\":7");

        assertFailsWithCode(() -> parse(json), IntentErrorCodes.SPAN_TEXT_MISMATCH);
    }

    @Test
    void referenceWithoutProvenanceIsRefused() {
        String json = IntentFixtures.referenceIntentJson()
                .replace(",\"provenance\":{\"type\":\"SOURCE_SPAN\",\"text\":\"" + IntentFixtures.AUDIENCE_PHRASE
                        + "\",\"start\":17,\"end\":24}", "");

        assertFailsWithCode(() -> parse(json), IntentErrorCodes.SHAPE_INVALID);
    }

    @Test
    void referenceCountIsBounded() {
        IntentAudienceReference reference = IntentFixtures.standardAudienceReference();
        List<IntentAudienceReference> tooMany = java.util.stream.IntStream
                .range(0, IntentLimits.MAX_AUDIENCE_REFERENCES + 1)
                .mapToObj(index -> IntentFixtures.audienceReference("ref-" + index, List.of("name-" + index),
                        reference.provenance()))
                .toList();

        IntentException refused = assertThrows(IntentException.class, () -> new SegmentIntent(
                com.company.campaign.contracts.ContractVersions.CURRENT, com.company.campaign.metadata.domain.SubjectKind.MEMBER,
                List.of(), new LogicalExpression.Clause("ref-0"), List.of(), List.of(), List.of(), List.of(),
                tooMany));
        assertEquals(IntentErrorCodes.LIMIT_EXCEEDED, refused.code());
    }

    @Test
    void referenceRejectsBlankCandidateAndMalformedIdentity() {
        assertFailsWithCode(
                () -> IntentFixtures.audienceReference("ref-1", List.of("   "),
                        IntentFixtures.standardAudienceReference().provenance()),
                IntentErrorCodes.AUDIENCE_REFERENCE_INVALID);
        assertFailsWithCode(
                () -> IntentFixtures.audienceReference("ref/1", List.of("VIP"),
                        IntentFixtures.standardAudienceReference().provenance()),
                IntentErrorCodes.AUDIENCE_REFERENCE_INVALID);
    }
}