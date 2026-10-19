package com.company.campaign.contracts;

import com.company.campaign.shared.canonical.CanonicalJson;
import com.company.campaign.shared.canonical.ContentHash;
import com.company.campaign.shared.error.ContractErrorCodes;
import com.company.campaign.testsupport.ContractAssertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Schema version, compatibility and disposition contracts.
 *
 * <p>Covers the "versioned" half of T01: one canonical version spelling, exact-match compatibility with a
 * stable failure code, and a closed disposition set.
 */
class ContractSerializationTest {

    private final CanonicalJson canonicalJson = new CanonicalJson();

    // Canonical dispositions are exactly the five defined by the Segment specification.
    @Test
    void dispositionsAreExactlyTheCanonicalFive() {
        assertThat(Arrays.stream(Disposition.values()).map(Enum::name))
                .containsExactly("RESOLVED", "NEEDS_INPUT", "UNSUPPORTED", "DENIED", "INVALID");
        assertThat(Disposition.RESOLVED.isResolved()).isTrue();
        assertThat(Arrays.stream(Disposition.values()).filter(d -> !d.isResolved()).map(Enum::name))
                .containsExactly("NEEDS_INPUT", "UNSUPPORTED", "DENIED", "INVALID");
        assertThat(Arrays.stream(IssueSeverity.values()).map(Enum::name))
                .containsExactly("ERROR", "WARNING", "INFO");
        assertThat(IssueSeverity.ERROR.blocksProcessing()).isTrue();
        assertThat(IssueSeverity.WARNING.blocksProcessing()).isFalse();
    }

    // Requirement 8: schema version scalar round-trip.
    @Test
    void schemaVersionRoundTripsAsAScalarString() {
        assertThat(canonicalJson.writeString(ContractVersions.V1_3)).isEqualTo("\"1.3\"");
        assertThat(canonicalJson.read("\"1.3\"", SchemaVersion.class)).isEqualTo(ContractVersions.V1_3);
        assertThat(SchemaVersion.parse("1.3").value()).isEqualTo("1.3");
        assertThat(SchemaVersion.parse("1.3")).isEqualTo(new SchemaVersion(1, 3));
        assertThat(SchemaVersion.parse("1.3")).hasSameHashCodeAs(new SchemaVersion(1, 3));
        assertThat(SchemaVersion.parse("2.0")).isGreaterThan(SchemaVersion.parse("1.99"));
        assertThat(SchemaVersion.parse("10.0")).isGreaterThan(SchemaVersion.parse("9.9"));
    }

    @Test
    void schemaVersionIsNeverWrittenAsAnObject() {
        String written = canonicalJson.writeString(ContractVersions.CURRENT);
        assertThat(written).isEqualTo("\"1.3\"").doesNotContain("major", "minor", "{");
    }

    // Requirement 8: malformed schema versions fail deterministically.
    @ParameterizedTest
    @ValueSource(strings = {
            "",
            "1",
            "1.",
            ".3",
            "1.3.0",
            "01.3",
            "1.03",
            " 1.3",
            "1.3 ",
            "v1.3",
            "1,3",
            "1.-3",
            "-1.3",
            "1.3-alpha",
            "1.3.0-SNAPSHOT",
            "one.three",
            "٣.١",
    })
    void malformedSchemaVersionsAreRejected(String malformed) {
        ContractAssertions.assertFailsWithCode(
                () -> SchemaVersion.parse(malformed), ContractErrorCodes.SCHEMA_VERSION_MALFORMED);
    }

    @Test
    void malformedSchemaVersionInsideAContractKeepsItsStableCode() {
        ContractAssertions.assertFailsWithCode(
                () -> canonicalJson.read("\"1.x\"", SchemaVersion.class),
                ContractErrorCodes.SCHEMA_VERSION_MALFORMED);
    }

    @Test
    void missingSchemaVersionIsRejectedByValidation() {
        assertThatThrownBy(() -> new SchemaVersion(-1, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("negative");
    }

    // A component can be all digits and still not be storable. That is a malformed wire value like any
    // other, so it must not leak a raw NumberFormatException from the parser.
    @Test
    void oversizedSchemaVersionComponentsFailWithTheStableSchemaCode() {
        for (String oversized : List.of(
                "2147483648.0",
                "0.2147483648",
                "99999999999999999999.1",
                "1.99999999999999999999",
                "123456789012345678901234567890.0",
                "0.123456789012345678901234567890")) {
            Throwable thrown = catchThrowable(() -> SchemaVersion.parse(oversized));

            assertThat(thrown)
                    .as("oversized schema version %s", oversized)
                    .isInstanceOf(SchemaVersionException.class);
            assertThat(((SchemaVersionException) thrown).code())
                    .as("stable code for %s", oversized)
                    .isEqualTo(ContractErrorCodes.SCHEMA_VERSION_MALFORMED);
            assertThat(((SchemaVersionException) thrown).details())
                    .containsEntry("value", oversized);
        }
    }

    @Test
    void largestRepresentableSchemaVersionComponentsStillParse() {
        SchemaVersion largest = SchemaVersion.parse("2147483647.2147483647");

        assertThat(largest.major()).isEqualTo(Integer.MAX_VALUE);
        assertThat(largest.minor()).isEqualTo(Integer.MAX_VALUE);
        assertThat(largest.value()).isEqualTo("2147483647.2147483647");
        assertThat(canonicalJson.writeString(largest)).isEqualTo("\"2147483647.2147483647\"");
        assertThat(canonicalJson.read("\"2147483647.2147483647\"", SchemaVersion.class)).isEqualTo(largest);
    }

    @Test
    void oversizedSchemaVersionInsideAContractKeepsItsStableCode() {
        ContractAssertions.assertFailsWithCode(
                () -> canonicalJson.read("\"99999999999999999999.1\"", SchemaVersion.class),
                ContractErrorCodes.SCHEMA_VERSION_MALFORMED);
    }

    // Requirement 9: exact version mismatch reports a stable code and both versions.
    @Test
    void exactVersionMismatchReportsCodeAndBothVersions() {
        Throwable thrown = catchThrowable(
                () -> SchemaCompatibility.requireExact(ContractVersions.V1_3, SchemaVersion.parse("1.2")));

        assertThat(thrown).isInstanceOf(SchemaCompatibilityException.class);
        SchemaCompatibilityException failure = (SchemaCompatibilityException) thrown;
        assertThat(failure.code()).isEqualTo(ContractErrorCodes.SCHEMA_VERSION_INCOMPATIBLE);
        assertThat(failure.expected()).isEqualTo(SchemaVersion.parse("1.3"));
        assertThat(failure.actual()).isEqualTo(SchemaVersion.parse("1.2"));
        assertThat(failure.details())
                .containsEntry("expected", "1.3")
                .containsEntry("actual", "1.2");
        assertThat(failure.getMessage()).isEqualTo("Expected schema version 1.3 but received 1.2");
    }

    // A newer version is not compatible merely because it is newer.
    @Test
    void compatibilityIsExactEqualityNotOrdering() {
        ContractAssertions.assertFailsWithCode(
                () -> SchemaCompatibility.requireExact(ContractVersions.V1_3, SchemaVersion.parse("1.4")),
                ContractErrorCodes.SCHEMA_VERSION_INCOMPATIBLE);
        ContractAssertions.assertFailsWithCode(
                () -> SchemaCompatibility.requireExact(ContractVersions.V1_3, SchemaVersion.parse("2.0")),
                ContractErrorCodes.SCHEMA_VERSION_INCOMPATIBLE);
        ContractAssertions.assertFailsWithCode(
                () -> SchemaCompatibility.requireExact(ContractVersions.V1_3, SchemaVersion.parse("1.2")),
                ContractErrorCodes.SCHEMA_VERSION_INCOMPATIBLE);

        ContractAssertions.assertFailsWithCode(
                () -> SchemaCompatibility.requireSupported(SchemaVersion.parse("1.4")),
                ContractErrorCodes.SCHEMA_VERSION_INCOMPATIBLE);
        ContractAssertions.assertFailsWithCode(
                () -> SchemaCompatibility.requireCurrent(SchemaVersion.parse("0.9")),
                ContractErrorCodes.SCHEMA_VERSION_INCOMPATIBLE);

        assertThat(ContractVersions.SUPPORTED).containsExactly(ContractVersions.V1_3);
        assertThat(ContractVersions.isSupported(ContractVersions.V1_3)).isTrue();
        assertThat(ContractVersions.isCurrent(ContractVersions.V1_3)).isTrue();
    }

    @Test
    void matchingVersionsPass() {
        SchemaCompatibility.requireExact(ContractVersions.V1_3, ContractVersions.CURRENT);
        SchemaCompatibility.requireSupported(ContractVersions.V1_3);
        SchemaCompatibility.requireCurrent(ContractVersions.V1_3);
    }

    // Issues are machine-readable and their collections are immutable.
    @Test
    void issueDetailsAreDeeplyImmutableAndOmittedWhenEmpty() {
        Issue minimal = Issue.error("SCHEMA_VERSION_INCOMPATIBLE", "Schema version is not supported");

        assertThat(minimal.details()).isEmpty();
        assertThat(canonicalJson.writeString(minimal))
                .isEqualTo("{\"code\":\"SCHEMA_VERSION_INCOMPATIBLE\","
                        + "\"message\":\"Schema version is not supported\","
                        + "\"severity\":\"ERROR\"}");

        Issue detailed = new Issue(
                "ATTRIBUTE_UNKNOWN",
                IssueSeverity.ERROR,
                "/predicate",
                "node-1",
                new SourceSpan("Gold会员", 0, 6),
                "Attribute is not in the metadata snapshot",
                java.util.Map.of("candidates", new java.util.ArrayList<>(List.of("tier"))));

        assertThatThrownBy(() -> detailed.details().put("x", 1))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> ((List<?>) detailed.details().get("candidates")).clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void sourceSpanUsesAHalfOpenRange() {
        SourceSpan span = new SourceSpan("Gold会员", 0, 6);

        assertThat(span.isEmpty()).isFalse();
        assertThat(new SourceSpan("", 4, 4).isEmpty()).isTrue();
        assertThatThrownBy(() -> new SourceSpan("Gold", 6, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not precede start");
    }

    @Test
    void issuesSerializeInStableFieldOrder() {
        List<Issue> issues = List.of(
                Issue.error("B_CODE", "second"),
                Issue.error("A_CODE", "first"));

        String written = canonicalJson.writeString(issues);

        assertThat(written).isEqualTo(
                "[{\"code\":\"B_CODE\",\"message\":\"second\",\"severity\":\"ERROR\"},"
                        + "{\"code\":\"A_CODE\",\"message\":\"first\",\"severity\":\"ERROR\"}]");
        assertThat(issues.stream().map(Issue::code).collect(Collectors.joining(","))).isEqualTo("B_CODE,A_CODE");
    }

    @Test
    void contentHashIsUsableAsAContractField() {
        ContentHash hash = ContentHash.sha256("payload".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        assertThat(canonicalJson.writeString(hash)).isEqualTo("\"" + hash.value() + "\"");
        assertThat(canonicalJson.read("\"" + hash.value() + "\"", ContentHash.class)).isEqualTo(hash);
        assertThat(hash.matches("payload".getBytes(java.nio.charset.StandardCharsets.UTF_8))).isTrue();
        assertThat(hash.matches("other".getBytes(java.nio.charset.StandardCharsets.UTF_8))).isFalse();
    }
}