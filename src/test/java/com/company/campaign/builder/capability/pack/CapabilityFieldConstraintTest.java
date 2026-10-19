package com.company.campaign.builder.capability.pack;

import com.company.campaign.builder.compiler.CompilerErrorCodes;
import com.company.campaign.builder.compiler.CompilerException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A capability pack field constraint is a closed, kind-matched, bounded declaration. Every kind has exactly
 * one representable bound, every bound is itself finite, and an unknown, mismatched or unbounded declaration
 * fails closed instead of being coerced.
 */
class CapabilityFieldConstraintTest {

    // ------------------------------------------------------------------ kind matching

    @Test
    void everyConstraintDeclaresTheKindItBelongsTo() {
        assertThat(new CapabilityFieldConstraint.Text(64).kind()).isEqualTo(CapabilityValueKind.STRING);
        assertThat(new CapabilityFieldConstraint.IntegerBounds(0, 10).kind()).isEqualTo(CapabilityValueKind.INTEGER);
        assertThat(new CapabilityFieldConstraint.DecimalBounds(9, 2, new BigDecimal("0"), new BigDecimal("10"))
                .kind()).isEqualTo(CapabilityValueKind.DECIMAL);
        assertThat(new CapabilityFieldConstraint.DurationBounds(0, 3600).kind())
                .isEqualTo(CapabilityValueKind.DURATION);
        assertThat(new CapabilityFieldConstraint.EnumCodes(List.of("GOLD", "SILVER")).kind())
                .isEqualTo(CapabilityValueKind.ORDERED_ENUM);
        assertThat(new CapabilityFieldConstraint.ReferenceTarget("MESSAGE_TEMPLATE").kind())
                .isEqualTo(CapabilityValueKind.REFERENCE);
        assertThat(CapabilityFieldConstraint.unconstrained(CapabilityValueKind.BOOLEAN).kind())
                .isEqualTo(CapabilityValueKind.BOOLEAN);
    }

    @Test
    void onlyTheKindsWithoutABoundMayBeDeclaredUnconstrained() {
        for (CapabilityValueKind kind : Set.of(CapabilityValueKind.BOOLEAN, CapabilityValueKind.DATE,
                CapabilityValueKind.DATETIME)) {
            assertThat(CapabilityFieldConstraint.unconstrained(kind).kind()).isEqualTo(kind);
        }
        for (CapabilityValueKind bound : Set.of(CapabilityValueKind.STRING, CapabilityValueKind.INTEGER,
                CapabilityValueKind.DECIMAL, CapabilityValueKind.DURATION, CapabilityValueKind.ORDERED_ENUM,
                CapabilityValueKind.REFERENCE)) {
            assertInvalid(() -> CapabilityFieldConstraint.unconstrained(bound),
                    "capabilityPackFieldConstraintKind");
        }
        assertInvalid(() -> new CapabilityFieldConstraint.Unconstrained(null),
                "capabilityPackFieldConstraintKind");
    }

    // ------------------------------------------------------------------ text bounds

    @Test
    void aTextBoundMustBePositiveAndBounded() {
        assertThat(new CapabilityFieldConstraint.Text(1).maxLength()).isEqualTo(1);
        assertThat(new CapabilityFieldConstraint.Text(CapabilityFieldConstraint.MAX_TEXT_LENGTH).maxLength())
                .isEqualTo(CapabilityFieldConstraint.MAX_TEXT_LENGTH);

        for (int maxLength : List.of(0, -1, CapabilityFieldConstraint.MAX_TEXT_LENGTH + 1, Integer.MAX_VALUE)) {
            assertInvalid(() -> new CapabilityFieldConstraint.Text(maxLength), "capabilityPackFieldTextBound");
        }
    }

    // ------------------------------------------------------------------ integer bounds

    @Test
    void anIntegerRangeMustBeOrderedAndFinite() {
        assertThat(new CapabilityFieldConstraint.IntegerBounds(1, 1).min()).isEqualTo(1);
        assertThat(new CapabilityFieldConstraint.IntegerBounds(-5, 5).max()).isEqualTo(5);

        assertInvalid(() -> new CapabilityFieldConstraint.IntegerBounds(2, 1),
                "capabilityPackFieldIntegerRange");
        long over = CapabilityFieldConstraint.MAX_INTEGER_MAGNITUDE + 1;
        assertInvalid(() -> new CapabilityFieldConstraint.IntegerBounds(0, over),
                "capabilityPackFieldIntegerBound");
    }

    // ------------------------------------------------------------------ decimal bounds

    @Test
    void aDecimalRangeCarriesAValidPrecisionScaleAndOrderedEndpoints() {
        CapabilityFieldConstraint.DecimalBounds bounds =
                new CapabilityFieldConstraint.DecimalBounds(9, 2, new BigDecimal("1.5"), new BigDecimal("10"));

        assertThat(bounds.precision()).isEqualTo(9);
        assertThat(bounds.scale()).isEqualTo(2);
        assertThat(bounds.min()).isEqualByComparingTo("1.5");
        assertThat(bounds.max()).isEqualByComparingTo("10");
    }

    @Test
    void aDecimalBoundNormalizesToItsDeclaredScale() {
        CapabilityFieldConstraint.DecimalBounds plain =
                new CapabilityFieldConstraint.DecimalBounds(9, 2, new BigDecimal("1"), new BigDecimal("10"));
        CapabilityFieldConstraint.DecimalBounds scaled =
                new CapabilityFieldConstraint.DecimalBounds(9, 2, new BigDecimal("1.00"), new BigDecimal("10.00"));

        assertThat(plain).isEqualTo(scaled).hasSameHashCodeAs(scaled);
    }

    @Test
    void anInvalidDecimalPrecisionScaleOrRangeFailsClosed() {
        BigDecimal zero = new BigDecimal("0");
        BigDecimal ten = new BigDecimal("10");

        for (int precision : List.of(0, -1, CapabilityFieldConstraint.MAX_PRECISION + 1)) {
            assertInvalid(() -> new CapabilityFieldConstraint.DecimalBounds(precision, 0, zero, ten),
                    "capabilityPackFieldDecimalPrecision");
        }
        for (int scale : List.of(-1, 3)) {
            assertInvalid(() -> new CapabilityFieldConstraint.DecimalBounds(2, scale, zero, ten),
                    "capabilityPackFieldDecimalScale");
        }
        assertInvalid(() -> new CapabilityFieldConstraint.DecimalBounds(9, 2, ten, zero),
                "capabilityPackFieldDecimalRange");
        assertInvalid(() -> new CapabilityFieldConstraint.DecimalBounds(9, 2, null, ten),
                "capabilityPackFieldDecimalRange");
        assertInvalid(() -> new CapabilityFieldConstraint.DecimalBounds(9, 2, zero, null),
                "capabilityPackFieldDecimalRange");
        assertInvalid(() -> new CapabilityFieldConstraint.DecimalBounds(9, 2, zero, new BigDecimal("0.001")),
                "capabilityPackFieldDecimalScale");
    }

    // ------------------------------------------------------------------ duration bounds

    @Test
    void aDurationRangeMustBeNonNegativeOrderedAndBounded() {
        assertThat(new CapabilityFieldConstraint.DurationBounds(0, 3600).maxSeconds()).isEqualTo(3600);

        assertInvalid(() -> new CapabilityFieldConstraint.DurationBounds(-1, 3600),
                "capabilityPackFieldDurationRange");
        assertInvalid(() -> new CapabilityFieldConstraint.DurationBounds(10, 5),
                "capabilityPackFieldDurationRange");
        assertInvalid(() -> new CapabilityFieldConstraint.DurationBounds(0, 0),
                "capabilityPackFieldDurationRange");
        assertInvalid(() -> new CapabilityFieldConstraint.DurationBounds(
                0, CapabilityFieldConstraint.MAX_DURATION_SECONDS + 1),
                "capabilityPackFieldDurationBound");
    }

    // ------------------------------------------------------------------ ordered enumerations

    @Test
    void anOrderedEnumerationPreservesItsDeclaredOrder() {
        CapabilityFieldConstraint.EnumCodes codes =
                new CapabilityFieldConstraint.EnumCodes(List.of("GOLD", "SILVER", "STANDARD"));

        assertThat(codes.codes()).containsExactly("GOLD", "SILVER", "STANDARD");
    }

    @Test
    void anEmptyUnboundedDuplicateOrForeignEnumerationFailsClosed() {
        assertInvalid(() -> new CapabilityFieldConstraint.EnumCodes(List.of()),
                "capabilityPackFieldEnumCodes");
        assertInvalid(() -> new CapabilityFieldConstraint.EnumCodes(null),
                "capabilityPackFieldEnumCodes");
        List<String> many = new ArrayList<>();
        for (int index = 0; index <= CapabilityFieldConstraint.MAX_ENUM_CODES; index++) {
            many.add("CODE_" + index);
        }
        assertInvalid(() -> new CapabilityFieldConstraint.EnumCodes(many), "capabilityPackFieldEnumCodes");
        assertInvalid(() -> new CapabilityFieldConstraint.EnumCodes(List.of("GOLD", "GOLD")),
                "capabilityPackFieldEnumCodes");
        for (String code : List.of("gold", "GOLD ", "", "GOLD;DROP", "A".repeat(65))) {
            assertInvalidWithoutDisclosure(() -> new CapabilityFieldConstraint.EnumCodes(List.of(code)),
                    "capabilityPackFieldEnumCode", code);
        }
    }

    @Test
    void anEnumerationCodeThatCarriesForbiddenContentFailsClosed() {
        assertInvalid(() -> new CapabilityFieldConstraint.EnumCodes(List.of("SCRIPT")),
                "capabilityPackFieldEnumCode");
        assertInvalid(() -> new CapabilityFieldConstraint.EnumCodes(List.of("PROVIDER_CREDENTIAL")),
                "capabilityPackFieldEnumCode");
    }

    // ------------------------------------------------------------------ references

    @Test
    void aReferenceNamesOnlyABoundedReferenceKind() {
        assertThat(new CapabilityFieldConstraint.ReferenceTarget("MESSAGE_TEMPLATE").referenceKind())
                .isEqualTo("MESSAGE_TEMPLATE");

        for (String kind : List.of("", " ", "message_template", "MESSAGE TEMPLATE", "A".repeat(65))) {
            assertInvalidWithoutDisclosure(() -> new CapabilityFieldConstraint.ReferenceTarget(kind),
                    "capabilityPackFieldReferenceKind", kind);
        }
        assertInvalid(() -> new CapabilityFieldConstraint.ReferenceTarget(null),
                "capabilityPackFieldReferenceKind");
    }

    @Test
    void aReferenceKindThatCarriesForbiddenContentFailsClosed() {
        assertInvalid(() -> new CapabilityFieldConstraint.ReferenceTarget("PROVIDER_ENDPOINT"),
                "capabilityPackFieldReferenceKind");
        assertInvalid(() -> new CapabilityFieldConstraint.ReferenceTarget("AI_PROMPT"),
                "capabilityPackFieldReferenceKind");
    }

    // ------------------------------------------------------------------ helpers

    private static void assertInvalid(Runnable construction, String invariant) {
        assertThatThrownBy(construction::run)
                .isInstanceOf(CompilerException.class)
                .satisfies(throwable -> {
                    CompilerException failure = (CompilerException) throwable;
                    assertThat(failure.code()).isEqualTo(CompilerErrorCodes.COMPILER_FROZEN_INPUT_INVALID);
                    assertThat(failure.details()).containsEntry("invariant", invariant);
                    assertThat(failure.getCause()).isNull();
                });
    }

    private static void assertInvalidWithoutDisclosure(Runnable construction, String invariant, String rejected) {
        assertThatThrownBy(construction::run)
                .isInstanceOf(CompilerException.class)
                .satisfies(throwable -> {
                    CompilerException failure = (CompilerException) throwable;
                    assertThat(failure.code()).isEqualTo(CompilerErrorCodes.COMPILER_FROZEN_INPUT_INVALID);
                    assertThat(failure.details()).containsEntry("invariant", invariant);
                    if (rejected == null || rejected.isBlank() || rejected.length() < 4) {
                        return;
                    }
                    assertThat(failure.getMessage()).doesNotContain(rejected);
                });
    }
}