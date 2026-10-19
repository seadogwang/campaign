package com.company.campaign.query.fixture;

import com.company.campaign.evaluation.segment.contract.FixtureRole;
import com.company.campaign.evaluation.segment.contract.MemberIdSet;
import com.company.campaign.shared.canonical.ContentHash;
import com.company.campaign.testsupport.ContractAssertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static com.company.campaign.query.fixture.testsupport.FixtureTestSupport.FIXTURE_ID;
import static com.company.campaign.query.fixture.testsupport.FixtureTestSupport.PAYLOAD_REFERENCE;
import static com.company.campaign.query.fixture.testsupport.FixtureTestSupport.UNIVERSE;
import static com.company.campaign.query.fixture.testsupport.FixtureTestSupport.load;
import static com.company.campaign.query.fixture.testsupport.FixtureTestSupport.member;
import static com.company.campaign.query.fixture.testsupport.FixtureTestSupport.order;
import static com.company.campaign.query.fixture.testsupport.FixtureTestSupport.payload;
import static com.company.campaign.query.fixture.testsupport.FixtureTestSupport.refund;
import static com.company.campaign.query.fixture.testsupport.FixtureTestSupport.standardMembers;
import static com.company.campaign.query.fixture.testsupport.FixtureTestSupport.standardPayload;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Requirements 1 and 2: exact bytes/hash/time/version/universe validation, a genuinely immutable dataset, and
 * strict malformed/duplicate/unknown/dangling failures with bounded codes that echo no payload value.
 */
class FixtureDatasetLoaderTest {

    @TempDir
    Path repositoryRoot;

    @Test
    void loadsTheReviewedPayloadWithExactIdentityTimeAndUniversePins() {
        FixtureDataset dataset = load(repositoryRoot, standardPayload());

        assertThat(dataset.fixtureId()).isEqualTo(FIXTURE_ID);
        assertThat(dataset.fixtureVersion()).isEqualTo("1");
        assertThat(dataset.role()).isEqualTo(FixtureRole.PRIMARY);
        assertThat(dataset.asOf()).isEqualTo(com.company.campaign.query.fixture.testsupport.FixtureTestSupport.AS_OF);
        assertThat(dataset.dataWatermark())
                .isEqualTo(com.company.campaign.query.fixture.testsupport.FixtureTestSupport.WATERMARK);
        assertThat(dataset.timezone().getId()).isEqualTo("Asia/Shanghai");
        assertThat(dataset.memberUniverse()).isEqualTo(UNIVERSE);
        assertThat(dataset.payloadHash()).isEqualTo(
                ContentHash.sha256(readPayloadBytes(repositoryRoot)));
        assertThat(dataset.members()).hasSize(6);
        assertThat(dataset.orders()).hasSize(7);
        assertThat(dataset.events()).isEmpty();
        assertThat(dataset.refunds()).hasSize(1);
    }

    @Test
    void loadedCollectionsAreImmutableAndIdenticalBytesProduceIdenticalDatasets() {
        FixtureDataset first = load(repositoryRoot, standardPayload());
        FixtureDataset second = load(repositoryRoot, standardPayload());

        assertThat(first.datasetHash()).isEqualTo(second.datasetHash());
        assertThat(first).isEqualTo(second);
        assertThatThrownBy(() -> first.members().add(null)).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> first.orders().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> first.currencies().add("EUR")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rejectsBytesThatDoNotMatchTheDeclaredHash() {
        byte[] original = standardPayload().getBytes(StandardCharsets.UTF_8);
        com.company.campaign.query.fixture.testsupport.FixtureTestSupport.writeBytes(repositoryRoot, original);
        FixtureDescriptorHolder holder = new FixtureDescriptorHolder(
                repositoryRoot, original, UNIVERSE, FixtureRole.PRIMARY, FIXTURE_ID);

        com.company.campaign.query.fixture.testsupport.FixtureTestSupport.writeBytes(
                repositoryRoot, "{\"tampered\":true}".getBytes(StandardCharsets.UTF_8));

        ContractAssertions.assertFailsWithCode(
                () -> com.company.campaign.query.fixture.testsupport.FixtureTestSupport
                        .loaderFor(repositoryRoot).load(holder.descriptor()),
                FixtureErrorCodes.FIXTURE_HASH_MISMATCH);
    }

    @Test
    void rejectsAPayloadThatIsNotBeneathTheEvaluationRoot() {
        com.company.campaign.query.fixture.testsupport.FixtureTestSupport.loaderFor(repositoryRoot);
        var descriptor = new com.company.campaign.evaluation.segment.contract.FixtureDescriptor(
                FIXTURE_ID, "1", FixtureRole.PRIMARY, "evaluation/segment/fixtures/absent.json",
                ContentHash.sha256(new byte[0]),
                com.company.campaign.query.fixture.testsupport.FixtureTestSupport.AS_OF,
                java.time.ZoneId.of("Asia/Shanghai"),
                com.company.campaign.query.fixture.testsupport.FixtureTestSupport.WATERMARK,
                UNIVERSE);

        ContractAssertions.assertFailsWithCode(
                () -> com.company.campaign.query.fixture.testsupport.FixtureTestSupport
                        .loaderFor(repositoryRoot).load(descriptor),
                FixtureErrorCodes.FIXTURE_PAYLOAD_NOT_FOUND);
    }

    @Test
    void rejectsUnknownTopLevelAndRowFields() {
        ContractAssertions.assertFailsWithCode(
                () -> load(repositoryRoot, standardPayload().replace("\"dataset\":", "\"bogus\":1,\"dataset\":")),
                FixtureErrorCodes.FIXTURE_UNKNOWN_FIELD);
        ContractAssertions.assertFailsWithCode(
                () -> load(repositoryRoot, payload(
                        List.of(member("m-001", "GOLD", "ACTIVE", "Shanghai", "2026-01-15", "Alice",
                                null, null, null, null, null, null)
                                .replace("\"name\":\"Alice\"", "\"name\":\"Alice\",\"secret\":\"x\"")),
                        List.of(), List.of(), List.of())),
                FixtureErrorCodes.FIXTURE_UNKNOWN_FIELD);
    }

    @Test
    void rejectsMissingRequiredFields() {
        ContractAssertions.assertFailsWithCode(
                () -> load(repositoryRoot, payload(
                        List.of(member("m-001", "GOLD", "ACTIVE", "Shanghai", "2026-01-15", "Alice",
                                        null, null, null, null, null, null)
                                .replace("\"city\":\"Shanghai\",", "")),
                        List.of(), List.of(), List.of())),
                FixtureErrorCodes.FIXTURE_MISSING_FIELD);
    }

    @Test
    void rejectsAFieldOfTheWrongType() {
        ContractAssertions.assertFailsWithCode(
                () -> load(repositoryRoot, payload(
                        List.of(member("m-001", "GOLD", "ACTIVE", "Shanghai", "2026-01-15", "Alice",
                                        30, "1.50", true, null, null, null).replace("\"age\":30", "\"age\":\"thirty\"")),
                        List.of(), List.of(), List.of())),
                FixtureErrorCodes.FIXTURE_PAYLOAD_MALFORMED);
    }

    @Test
    void rejectsMalformedJsonDuplicateKeysAndInvalidUtf8() {
        ContractAssertions.assertFailsWithCode(
                () -> load(repositoryRoot, "{ not json"),
                FixtureErrorCodes.FIXTURE_PAYLOAD_MALFORMED);
        ContractAssertions.assertFailsWithCode(
                () -> load(repositoryRoot, payload(
                        List.of(member("m-001", "GOLD", "ACTIVE", "Shanghai", "2026-01-15", "Alice",
                                        null, null, null, null, null, null)
                                .replace("\"tier\":\"GOLD\"", "\"tier\":\"GOLD\",\"tier\":\"SILVER\"")),
                        List.of(), List.of(), List.of())),
                FixtureErrorCodes.FIXTURE_PAYLOAD_MALFORMED);

        com.company.campaign.query.fixture.testsupport.FixtureTestSupport.writeBytes(
                repositoryRoot, new byte[] {(byte) 0xC3, (byte) 0x28});
        var descriptor = new com.company.campaign.evaluation.segment.contract.FixtureDescriptor(
                FIXTURE_ID, "1", FixtureRole.PRIMARY, PAYLOAD_REFERENCE,
                ContentHash.sha256(new byte[] {(byte) 0xC3, (byte) 0x28}),
                com.company.campaign.query.fixture.testsupport.FixtureTestSupport.AS_OF,
                java.time.ZoneId.of("Asia/Shanghai"),
                com.company.campaign.query.fixture.testsupport.FixtureTestSupport.WATERMARK,
                UNIVERSE);
        ContractAssertions.assertFailsWithCode(
                () -> com.company.campaign.query.fixture.testsupport.FixtureTestSupport
                        .loaderFor(repositoryRoot).load(descriptor),
                FixtureErrorCodes.FIXTURE_PAYLOAD_MALFORMED);
    }

    @Test
    void rejectsDuplicateRowIdentifiers() {
        ContractAssertions.assertFailsWithCode(
                () -> load(repositoryRoot, payload(
                        List.of(standardMembers().get(0), standardMembers().get(0)),
                        List.of(), List.of(), List.of())),
                FixtureErrorCodes.FIXTURE_DUPLICATE_ID);
        ContractAssertions.assertFailsWithCode(
                () -> load(repositoryRoot, payload(
                        List.of(standardMembers().get(0)),
                        List.of(order("o-001", "m-001", "1.00", "CNY", "2026-09-30T09:00:00Z", "0.00", "PAID", null),
                                order("o-001", "m-001", "2.00", "CNY", "2026-09-30T09:00:00Z", "0.00", "PAID", null)),
                        List.of(), List.of())),
                FixtureErrorCodes.FIXTURE_DUPLICATE_ID);
    }

    @Test
    void rejectsASecondRefundRowForTheSameOrder() {
        // A refund's only identity key is its orderId; a repeated orderId would silently double-subtract.
        ContractAssertions.assertFailsWithCode(
                () -> load(repositoryRoot, payload(
                        List.of(standardMembers().get(0)),
                        List.of(order("o-001", "m-001", "50.00", "CNY",
                                "2026-09-20T09:00:00Z", "10.00", "COMPLETED", "STORE")),
                        List.of(),
                        List.of(refund("o-001", "10.00"), refund("o-001", "5.00")))),
                FixtureErrorCodes.FIXTURE_DUPLICATE_ID);
        ContractAssertions.assertFailsWithCode(
                () -> load(repositoryRoot, standardPayload().replace(
                        "\"refunds\":[{\"orderId\":\"o-002\",\"amount\":10.00}]",
                        "\"refunds\":[{\"orderId\":\"o-002\",\"amount\":10.00},"
                                + "{\"orderId\":\"o-002\",\"amount\":1.00}]")),
                FixtureErrorCodes.FIXTURE_DUPLICATE_ID);
    }

    @Test
    void aSingleRefundPerOrderStillLoadsNormally() {
        FixtureDataset dataset = load(repositoryRoot, standardPayload());

        assertThat(dataset.refunds()).hasSize(1);
        assertThat(dataset.refunds().get(0).orderId()).isEqualTo("o-002");
        assertThat(dataset.refunds().get(0).amount()).isEqualByComparingTo(new java.math.BigDecimal("10.00"));
    }

    @Test
    void rejectsRowsThatRelateToAnAbsentMemberOrOrder() {
        ContractAssertions.assertFailsWithCode(
                () -> load(repositoryRoot, payload(
                        List.of(standardMembers().get(0)),
                        List.of(order("o-001", "m-999", "1.00", "CNY", "2026-09-30T09:00:00Z", "0.00", "PAID", null)),
                        List.of(), List.of())),
                FixtureErrorCodes.FIXTURE_DANGLING_REFERENCE);
        ContractAssertions.assertFailsWithCode(
                () -> load(repositoryRoot, payload(
                        List.of(standardMembers().get(0)), List.of(),
                        List.of(com.company.campaign.query.fixture.testsupport.FixtureTestSupport
                                .event("e-001", "m-999", "2026-09-30T09:00:00Z", "LOGIN")),
                        List.of())),
                FixtureErrorCodes.FIXTURE_DANGLING_REFERENCE);
        ContractAssertions.assertFailsWithCode(
                () -> load(repositoryRoot, payload(
                        List.of(standardMembers().get(0)), List.of(), List.of(),
                        List.of("{\"orderId\":\"o-999\",\"amount\":1.00}"))),
                FixtureErrorCodes.FIXTURE_DANGLING_REFERENCE);
    }

    @Test
    void rejectsAPayloadWhoseIdentityRoleOrTimeContextContradictsItsDescriptor() {
        ContractAssertions.assertFailsWithCode(
                () -> load(repositoryRoot, payload("other-fixture", "PRIMARY",
                        standardMembers(), List.of(), List.of(), List.of())),
                FixtureErrorCodes.FIXTURE_IDENTITY_MISMATCH);
        ContractAssertions.assertFailsWithCode(
                () -> load(repositoryRoot, payload(FIXTURE_ID, "COUNTEREXAMPLE",
                        standardMembers(), List.of(), List.of(), List.of())),
                FixtureErrorCodes.FIXTURE_IDENTITY_MISMATCH);
        ContractAssertions.assertFailsWithCode(
                () -> load(repositoryRoot, standardPayload().replace(
                        "\"asOf\":\"2026-10-01T02:00:00Z\"", "\"asOf\":\"2026-10-01T03:00:00Z\"")),
                FixtureErrorCodes.FIXTURE_IDENTITY_MISMATCH);
    }

    @Test
    void rejectsMembersOutsideTheDeclaredUniverse() {
        ContractAssertions.assertFailsWithCode(
                () -> com.company.campaign.query.fixture.testsupport.FixtureTestSupport.load(
                        repositoryRoot, standardPayload(), MemberIdSet.of("m-001"), FixtureRole.PRIMARY,
                        FIXTURE_ID),
                FixtureErrorCodes.FIXTURE_UNIVERSE_MISMATCH);
    }

    @Test
    void requiresThePayloadMemberSetAndDescriptorUniverseToBeExactlyEqual() {
        // A payload member outside the universe, even when the two sets have equal size: m-007 replaces m-006.
        List<String> substituted = new ArrayList<>(standardMembers());
        substituted.set(5, member("m-007", "GOLD", "CHURNED", "Guangzhou", "2026-06-15", "Eric",
                35, "6.00", true, "2026-09-26T10:00:00Z", "600.00", "CNY"));
        ContractAssertions.assertFailsWithCode(
                () -> com.company.campaign.query.fixture.testsupport.FixtureTestSupport.load(
                        repositoryRoot, payload(substituted, List.of(), List.of(), List.of()),
                        UNIVERSE, FixtureRole.PRIMARY, FIXTURE_ID),
                FixtureErrorCodes.FIXTURE_UNIVERSE_MISMATCH);

        // A descriptor member absent from the payload: the universe is a superset of the payload members.
        MemberIdSet superset = MemberIdSet.of(
                "m-001", "m-002", "m-003", "m-004", "m-005", "m-006", "m-007");
        ContractAssertions.assertFailsWithCode(
                () -> com.company.campaign.query.fixture.testsupport.FixtureTestSupport.load(
                        repositoryRoot, payload(standardMembers(), List.of(), List.of(), List.of()),
                        superset, FixtureRole.PRIMARY, FIXTURE_ID),
                FixtureErrorCodes.FIXTURE_UNIVERSE_MISMATCH);
    }

    @Test
    void aNullDescriptorFailsWithTheBoundedInputCode() {
        ContractAssertions.assertFailsWithCode(
                () -> com.company.campaign.query.fixture.testsupport.FixtureTestSupport
                        .loaderFor(repositoryRoot).load(null),
                FixtureErrorCodes.FIXTURE_INPUT_MISSING);
    }

    private static byte[] readPayloadBytes(Path repositoryRoot) {
        try {
            return Files.readAllBytes(repositoryRoot.resolve(PAYLOAD_REFERENCE));
        } catch (java.io.IOException exception) {
            throw new java.io.UncheckedIOException(exception);
        }
    }

    /** A descriptor captured before the payload bytes are tampered with. */
    private record FixtureDescriptorHolder(
            Path repositoryRoot, byte[] bytes, MemberIdSet universe, FixtureRole role, String fixtureId) {

        com.company.campaign.evaluation.segment.contract.FixtureDescriptor descriptor() {
            return com.company.campaign.query.fixture.testsupport.FixtureTestSupport
                    .descriptorFor(repositoryRoot, bytes, universe, role, fixtureId);
        }
    }
}