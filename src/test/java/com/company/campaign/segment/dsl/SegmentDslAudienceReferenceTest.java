package com.company.campaign.segment.dsl;

import com.company.campaign.segment.dsl.codec.SegmentDslCodec;
import com.company.campaign.segment.dsl.contract.SegmentDsl;
import com.company.campaign.segment.dsl.contract.SegmentDslErrorCodes;
import com.company.campaign.segment.dsl.contract.SegmentDslException;
import com.company.campaign.segment.dsl.predicate.AudienceReferencePredicate;
import com.company.campaign.segment.dsl.predicate.GroupPredicate;
import com.company.campaign.segment.dsl.predicate.NotPredicate;
import com.company.campaign.segment.dsl.predicate.Predicate;
import com.company.campaign.segment.dsl.predicate.PredicateType;
import com.company.campaign.segment.dsl.testsupport.SegmentDslFixtures;
import com.company.campaign.shared.canonical.ContentHash;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The T18 {@code AUDIENCE_REFERENCE} predicate on the one T05 editable DSL.
 *
 * <p>The node is a closed, five-field shape: a stable {@code nodeId}, the wire discriminator and an exact
 * immutable version pin of three parts. No name, query, SQL, owner, DataScope, permission, canonical byte or
 * dependency field exists, and no mutable {@code current}/{@code latest} pointer is representable, so a
 * model- or client-supplied payload can never widen the reference beyond one immutable version.
 */
class SegmentDslAudienceReferenceTest {

    private static final SegmentDslCodec CODEC = new SegmentDslCodec();

    /** The recorded editable hash and bytes of the pre-T18 document; adding the subtype must not move them. */
    private static final String LEGACY_EDITABLE_HASH =
            "sha256:224510ea6552aa404c563538a3f217a56bdbaa620a22f16719a41199f6393694";

    private static ContentHash hash(String text) {
        return ContentHash.sha256(text.getBytes(StandardCharsets.UTF_8));
    }

    private static AudienceReferencePredicate reference(String nodeId) {
        return new AudienceReferencePredicate(nodeId, "audience-vip", "version-7", hash("version-7-bytes"));
    }

    private static SegmentDsl document(Predicate predicate) {
        return SegmentDslFixtures.publish(predicate);
    }

    @Test
    void roundTripsTheExactWireShapeAndRetainsItsStableNodeId() {
        AudienceReferencePredicate node = reference("n-ref");
        SegmentDsl document = document(new GroupPredicate(
                "n-root", PredicateType.ALL, List.of(SegmentDslFixtures.tierEqualsGold("n-tier"), node)));

        String json = CODEC.writeString(document);

        assertThat(json).contains("\"type\":\"AUDIENCE_REFERENCE\"");
        assertThat(json).contains("\"nodeId\":\"n-ref\"");
        assertThat(json).contains("\"audienceId\":\"audience-vip\"");
        assertThat(json).contains("\"versionId\":\"version-7\"");
        assertThat(json).contains("\"versionHash\":\"" + hash("version-7-bytes").value() + "\"");

        SegmentDsl reparsed = CODEC.parse(json);
        assertThat(reparsed).isEqualTo(document);
        assertThat(reparsed.predicate()).isEqualTo(document.predicate());
        assertThat(((GroupPredicate) reparsed.predicate()).children().get(1).nodeId()).isEqualTo("n-ref");
    }

    @Test
    void acceptsAReferenceAtThePredicateRootAndInsideANegation() {
        SegmentDsl root = document(reference("n-ref"));
        assertThat(CODEC.parse(CODEC.writeString(root))).isEqualTo(root);

        SegmentDsl negated = document(new NotPredicate("n-not", reference("n-ref")));
        assertThat(CODEC.parse(CODEC.writeString(negated))).isEqualTo(negated);
    }

    @Test
    void rejectsEveryFieldOutsideTheClosedShape() {
        // A name, a query, raw SQL, an owner, a scope, a permission, canonical bytes, dependencies and the two
        // mutable-pointer spellings must all be unrecognized rather than silently ignored or coerced.
        for (String forbidden : List.of(
                "\"name\":\"VIP\"",
                "\"query\":{\"sql\":\"select 1\"}",
                "\"sql\":\"select 1\"",
                "\"tenantId\":\"tenant-001\"",
                "\"programId\":\"program-001\"",
                "\"dataScopeId\":\"ds-1\"",
                "\"permission\":\"segment.evaluate\"",
                "\"canonicalAstHash\":\"" + hash("x").value() + "\"",
                "\"dependencies\":[]",
                "\"current\":true",
                "\"latest\":true",
                "\"visibility\":\"PUBLIC\"")) {
            assertCode(
                    referenceDocumentJson("\"audienceId\":\"audience-vip\",\"versionId\":\"version-7\","
                            + "\"versionHash\":\"" + hash("x").value() + "\"," + forbidden),
                    SegmentDslErrorCodes.UNKNOWN_FIELD);
        }
    }

    @Test
    void rejectsAMissingOrMalformedVersionPin() {
        assertCode(
                referenceDocumentJson("\"audienceId\":\"audience-vip\",\"versionId\":\"version-7\""),
                SegmentDslErrorCodes.SHAPE_INVALID);
        assertCode(
                referenceDocumentJson("\"audienceId\":\"audience-vip\",\"versionHash\":\""
                        + hash("x").value() + "\""),
                SegmentDslErrorCodes.SHAPE_INVALID);
        assertCode(
                referenceDocumentJson("\"audienceId\":\"audience-vip\",\"versionId\":\"version-7\","
                        + "\"versionHash\":\"sha256:not-a-hash\""),
                SegmentDslErrorCodes.AUDIENCE_REFERENCE_INVALID);
        // A bare audience identifier is never accepted in place of an exact version pin.
        assertCode(
                referenceDocumentJson("\"audienceId\":\"audience-vip\""),
                SegmentDslErrorCodes.SHAPE_INVALID);
    }

    @Test
    void rejectsAnUnboundedAudienceOrVersionIdentifier() {
        for (String bad : List.of("\"\"", "\" audience-vip\"", "\"audience/vip\"", "\"audience vip\"")) {
            assertCode(
                    referenceDocumentJson("\"audienceId\":" + bad + ",\"versionId\":\"version-7\","
                            + "\"versionHash\":\"" + hash("x").value() + "\""),
                    SegmentDslErrorCodes.REFERENCE_ID_INVALID);
        }
        for (String bad : List.of("\"\"", "\" version-7\"", "\"version/7\"")) {
            assertCode(
                    referenceDocumentJson("\"audienceId\":\"audience-vip\",\"versionId\":" + bad
                            + ",\"versionHash\":\"" + hash("x").value() + "\""),
                    SegmentDslErrorCodes.REFERENCE_ID_INVALID);
        }
    }

    @Test
    void rejectsADuplicateNodeIdAcrossAReferenceAndAnotherNode() {
        String json = SegmentDslFixtures.documentJson("{\"nodeId\":\"n-shared\",\"type\":\"ALL\",\"children\":["
                + "{\"nodeId\":\"n-shared\",\"type\":\"AUDIENCE_REFERENCE\",\"audienceId\":\"audience-vip\","
                + "\"versionId\":\"version-7\",\"versionHash\":\"" + hash("x").value() + "\"}]}");
        assertThatThrownBy(() -> CODEC.parse(json))
                .isInstanceOf(SegmentDslException.class)
                .extracting(error -> ((SegmentDslException) error).code())
                .isEqualTo(SegmentDslErrorCodes.DUPLICATE_NODE_ID);
    }

    @Test
    void rejectsAnUnknownDiscriminatorRatherThanMappingItToAReference() {
        String json = SegmentDslFixtures.documentJson(
                "{\"nodeId\":\"n-ref\",\"type\":\"audience_reference\",\"audienceId\":\"audience-vip\","
                        + "\"versionId\":\"version-7\",\"versionHash\":\"" + hash("x").value() + "\"}");
        assertCode(json, SegmentDslErrorCodes.UNKNOWN_DISCRIMINATOR);
    }

    @Test
    void theReferencePinIsPartOfTheEditableHashMaterial() {
        AudienceReferencePredicate first = reference("n-ref");
        AudienceReferencePredicate differentVersion =
                new AudienceReferencePredicate("n-ref", "audience-vip", "version-8", hash("version-7-bytes"));

        assertThat(document(first).editableDslHash())
                .isNotEqualTo(document(differentVersion).editableDslHash());
    }

    @Test
    void legacyNonReferenceDocumentsKeepTheirExactBytesAndHash() {
        SegmentDsl legacy = SegmentDslFixtures.document();

        assertThat(legacy.editableDslHash().value()).isEqualTo(LEGACY_EDITABLE_HASH);
        assertThat(CODEC.parse(CODEC.write(legacy))).isEqualTo(legacy);
        assertThat(CODEC.writeString(legacy)).contains("\"n-order-2\"").doesNotContain("AUDIENCE_REFERENCE");
    }

    /** One full document whose root predicate is a reference node built from the given node fields. */
    private static String referenceDocumentJson(String nodeFields) {
        return SegmentDslFixtures.documentJson(
                "{\"nodeId\":\"n-ref\",\"type\":\"AUDIENCE_REFERENCE\"," + nodeFields + "}");
    }

    private static void assertCode(String documentJson, String expectedCode) {
        assertThatThrownBy(() -> CODEC.parse(documentJson))
                .isInstanceOf(SegmentDslException.class)
                .extracting(error -> ((SegmentDslException) error).code())
                .isEqualTo(expectedCode);
    }
}