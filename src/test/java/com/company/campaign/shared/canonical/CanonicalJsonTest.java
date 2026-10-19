package com.company.campaign.shared.canonical;

import com.company.campaign.shared.error.ContractErrorCodes;
import com.company.campaign.testsupport.ContractAssertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Canonical JSON and content hashing.
 *
 * <p>These tests cover the properties the whole system depends on: two inputs that mean the same thing
 * must produce the same bytes and the same hash, and inputs that mean different things must not.
 */
class CanonicalJsonTest {

    private static final String ABC_SHA256 = "sha256:ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";
    private static final String EMPTY_SHA256 = "sha256:e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    private final CanonicalJson canonicalJson = new CanonicalJson();

    // Requirement 1: map/object key order does not affect bytes or hash.
    @Test
    void mapInsertionOrderDoesNotAffectBytesOrHash() {
        Map<String, Object> first = new LinkedHashMap<>();
        first.put("zeta", new BigDecimal("1000.00"));
        first.put("alpha", Map.of("second", 2, "first", 1));

        Map<String, Object> second = new LinkedHashMap<>();
        second.put("alpha", Map.of("first", 1, "second", 2));
        second.put("zeta", new BigDecimal("1000.0"));

        assertThat(canonicalJson.write(first)).isEqualTo(canonicalJson.write(second));
        assertThat(canonicalJson.hash(first)).isEqualTo(canonicalJson.hash(second));
        assertThat(canonicalJson.writeString(first))
                .isEqualTo("{\"alpha\":{\"first\":1,\"second\":2},\"zeta\":1000}");
    }

    // Requirement 2: nested object keys are sorted at every depth.
    @Test
    void nestedObjectKeysAreSortedAtEveryDepth() {
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("z", new LinkedHashMap<>(Map.of("beta", Map.of("yankee", 1, "alpha", 2), "alpha", 3)));

        assertThat(canonicalJson.writeString(nested))
                .isEqualTo("{\"z\":{\"alpha\":3,\"beta\":{\"alpha\":2,\"yankee\":1}}}");
    }

    // Requirement 3: array order is preserved and changes the hash.
    @Test
    void arrayOrderIsPreservedAndChangesTheHash() {
        assertThat(canonicalJson.writeString(Map.of("values", List.of("second", "first"))))
                .isEqualTo("{\"values\":[\"second\",\"first\"]}");

        assertThat(canonicalJson.hash(Map.of("values", List.of("second", "first"))))
                .isNotEqualTo(canonicalJson.hash(Map.of("values", List.of("first", "second"))));
    }

    // Requirement 4: equivalent decimals have identical canonical bytes using plain notation.
    @Test
    void equivalentDecimalsShareOnePlainNotationRepresentation() {
        assertThat(canonicalJson.writeString(new BigDecimal("1000.00"))).isEqualTo("1000");
        assertThat(canonicalJson.writeString(new BigDecimal("1000.0"))).isEqualTo("1000");
        assertThat(canonicalJson.writeString(new BigDecimal("1E+3"))).isEqualTo("1000");
        assertThat(canonicalJson.writeString(new BigDecimal("1.0E+10"))).isEqualTo("10000000000");
        assertThat(canonicalJson.writeString(new BigDecimal("0.10"))).isEqualTo("0.1");
        assertThat(canonicalJson.writeString(new BigDecimal("1000.50"))).isEqualTo("1000.5");
        assertThat(canonicalJson.writeString(BigDecimal.ZERO)).isEqualTo("0");

        assertThat(canonicalJson.hash(new BigDecimal("1000.00"))).isEqualTo(canonicalJson.hash(new BigDecimal("1E+3")));
    }

    // Requirement 4: plain notation never degrades into scientific notation for small or large values.
    @ParameterizedTest
    @ValueSource(strings = {"0.000000001", "0.00000000000000000001", "123456789012345678901234567890"})
    void decimalNotationIsAlwaysPlainAndNeverScientific(String literal) {
        String written = canonicalJson.writeString(new BigDecimal(literal));

        assertThat(written).doesNotContainIgnoringCase("e");
        assertThat(new BigDecimal(written)).isEqualByComparingTo(new BigDecimal(literal));
    }

    @Test
    void integralAndFloatingPointValuesCollapseToTheSameCanonicalForm() {
        assertThat(canonicalJson.writeString(1)).isEqualTo("1");
        assertThat(canonicalJson.writeString(1L)).isEqualTo("1");
        assertThat(canonicalJson.writeString(1.0d)).isEqualTo("1");
        assertThat(canonicalJson.writeString(new BigDecimal("1.000"))).isEqualTo("1");
        assertThat(canonicalJson.hash(1)).isEqualTo(canonicalJson.hash(1.0d));
    }

    @ParameterizedTest
    @ValueSource(doubles = {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY})
    void nonFiniteNumbersAreRejected(double value) {
        ContractAssertions.assertFailsWithCode(
                () -> canonicalJson.write(value), ContractErrorCodes.JSON_NON_CANONICAL_VALUE);
    }

    // Requirement 5: ISO temporal serialization is stable and never epoch-numbered.
    @Test
    void temporalValuesAreWrittenAsStableIsoText() {
        assertThat(canonicalJson.writeString(Instant.parse("2026-10-01T02:00:00Z")))
                .isEqualTo("\"2026-10-01T02:00:00Z\"");
        assertThat(canonicalJson.writeString(OffsetDateTime.parse("2026-10-01T10:00:00+08:00")))
                .isEqualTo("\"2026-10-01T10:00:00+08:00\"");
        assertThat(canonicalJson.writeString(LocalDate.parse("2026-10-01")))
                .isEqualTo("\"2026-10-01\"");
        assertThat(canonicalJson.writeString(ZoneId.of("Asia/Shanghai")))
                .isEqualTo("\"Asia/Shanghai\"");
        assertThat(canonicalJson.writeString(Instant.parse("2026-10-01T02:00:00Z")))
                .doesNotContain("1780");
    }

    // Requirement 6: duplicate JSON keys fail, both for tree reads and for DTO deserialization.
    @Test
    void duplicateObjectKeysAreRejected() {
        ContractAssertions.assertFailsWithCode(
                () -> canonicalJson.readTree("{\"alpha\":1,\"alpha\":2}"),
                ContractErrorCodes.JSON_DUPLICATE_KEY);

        ContractAssertions.assertFailsWithCode(
                () -> canonicalJson.read("{\"values\":[1],\"values\":[2]}", ExampleContract.class),
                ContractErrorCodes.JSON_DUPLICATE_KEY);
    }

    @Test
    void malformedJsonIsRejectedWithItsOwnCode() {
        ContractAssertions.assertFailsWithCode(
                () -> canonicalJson.readTree("{\"alpha\":}"),
                ContractErrorCodes.JSON_MALFORMED);
    }

    // Requirement 7 and 12 at the base-utility level: unknown DTO fields fail.
    @Test
    void unknownContractFieldsAreRejected() {
        ContractAssertions.assertFailsWithCode(
                () -> canonicalJson.read("{\"at\":\"2026-10-01T02:00:00Z\",\"tenantId\":\"forged\"}", ExampleContract.class),
                ContractErrorCodes.CONTRACT_UNKNOWN_FIELD);
    }

    // Requirement 10: content hash format and known SHA-256 vectors.
    @Test
    void contentHashMatchesPublishedSha256Vectors() {
        assertThat(ContentHash.sha256("abc".getBytes(StandardCharsets.UTF_8)).value()).isEqualTo(ABC_SHA256);
        assertThat(ContentHash.sha256(new byte[0]).value()).isEqualTo(EMPTY_SHA256);
    }

    @Test
    void contentHashIsLowercasePrefixedSha256() {
        assertThat(ContentHash.sha256(new byte[0]).value()).matches("^sha256:[0-9a-f]{64}$");
        assertThat(canonicalJson.hash(Map.of())).isEqualTo(ContentHash.sha256("{}".getBytes(StandardCharsets.UTF_8)));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "",
            "deadbeef",
            "sha256:",
            "sha256:deadbeef",
            "SHA256:ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            "sha256:BA7816BF8F01CFEA414140DE5DAE2223B00361A396177A9CB410FF61F20015AD",
            "sha512:ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            "sha256:ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015azz",
    })
    void malformedContentHashesAreRejected(String malformed) {
        ContractAssertions.assertFailsWithCode(
                () -> new ContentHash(malformed), ContractErrorCodes.CONTENT_HASH_INVALID);
    }

    @Test
    void malformedContentHashInsideAContractKeepsItsStableCode() {
        ContractAssertions.assertFailsWithCode(
                () -> canonicalJson.read("\"sha256:NOTAHASH\"", ContentHash.class),
                ContractErrorCodes.CONTENT_HASH_INVALID);
    }

    @Test
    void canonicalOutputIsCompactUtf8WithoutBom() {
        byte[] bytes = canonicalJson.write(Map.of("name", "会员 Gold"));

        assertThat(bytes[0]).isNotEqualTo((byte) 0xEF);
        assertThat(new String(bytes, StandardCharsets.UTF_8)).isEqualTo("{\"name\":\"会员 Gold\"}");
    }

    @Test
    void writingDoesNotDependOnTheDefaultLocale() {
        java.util.Locale previous = java.util.Locale.getDefault();
        try {
            String before = canonicalJson.writeString(Map.of("name", "会员", "amount", new BigDecimal("1000.50")));
            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("tr-TR"));
            String after = canonicalJson.writeString(Map.of("name", "会员", "amount", new BigDecimal("1000.50")));
            assertThat(after).isEqualTo(before);
        } finally {
            java.util.Locale.setDefault(previous);
        }
    }

    @Test
    void writingDoesNotMutateTheSourceCollections() {
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("values", new ArrayList<>(List.of("b", "a")));
        Map<String, Object> nested = new HashMap<>();
        nested.put("inner", 1);
        source.put("object", nested);

        canonicalJson.write(source);

        assertThat(source).containsOnlyKeys("values", "object");
        assertThat(source.get("values")).isEqualTo(List.of("b", "a"));
        assertThat(nested).containsEntry("inner", 1);
    }

    private record ExampleContract(Instant at, List<String> values) {
    }
}