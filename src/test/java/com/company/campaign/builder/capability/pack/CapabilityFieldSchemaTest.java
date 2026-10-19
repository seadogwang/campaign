package com.company.campaign.builder.capability.pack;

import com.company.campaign.builder.compiler.CompilerErrorCodes;
import com.company.campaign.builder.compiler.CompilerException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * One declared capability payload field binds a bounded canonical name to a typed constraint, and nothing
 * else. A malformed, unbounded or forbidden name fails closed, and the schema carries no required/optional
 * flag — that is decided by the containing contract.
 */
class CapabilityFieldSchemaTest {

    @Test
    void aSchemaBindsAFieldNameToItsTypedConstraint() {
        CapabilityFieldSchema schema =
                new CapabilityFieldSchema("referenceCode", new CapabilityFieldConstraint.Text(128));

        assertThat(schema.fieldName()).isEqualTo("referenceCode");
        assertThat(schema.kind()).isEqualTo(CapabilityValueKind.STRING);
        assertThat(schema.constraint()).isEqualTo(new CapabilityFieldConstraint.Text(128));
    }

    @Test
    void aDeclaredFieldNameMustBeABoundedCanonicalIdentifier() {
        for (String name : List.of("ReferenceCode", "reference code", "reference-code", "1reference", "",
                " ", "reference.code", "a".repeat(CapabilityFieldSchema.MAX_FIELD_NAME_LENGTH + 1))) {
            assertInvalidWithoutDisclosure(() -> new CapabilityFieldSchema(name, text()),
                    "capabilityPackFieldName", name);
        }
        assertInvalid(() -> new CapabilityFieldSchema(null, text()), "capabilityPackFieldName");
        assertThat(new CapabilityFieldSchema("a".repeat(CapabilityFieldSchema.MAX_FIELD_NAME_LENGTH), text())
                .fieldName()).hasSize(CapabilityFieldSchema.MAX_FIELD_NAME_LENGTH);
    }

    @Test
    void aFieldNameCarryingExecutableOrCredentialContentFailsClosed() {
        for (String forbidden : List.of("providerCredential", "execScript", "apiKey", "tenantId",
                "systemPrompt", "className", "connectionString", "sqlText", "memberId", "dataScope",
                "callbackUrl", "rewardFormula")) {
            assertInvalidWithoutDisclosure(() -> new CapabilityFieldSchema(forbidden, text()),
                    "capabilityPackFieldName", forbidden);
        }
    }

    @Test
    void anOrdinaryBusinessFieldNameIsAccepted() {
        for (String name : List.of("referenceCode", "variables", "quantity", "messageLogicalId", "factCode",
                "localTime", "maxRecipients", "currency", "startDate", "duration", "anchor", "terminal",
                "onUnknown", "rewardKind", "value")) {
            assertThat(new CapabilityFieldSchema(name, text()).fieldName()).isEqualTo(name);
        }
    }

    @Test
    void aMissingConstraintFailsClosed() {
        assertInvalid(() -> new CapabilityFieldSchema("referenceCode", null), "capabilityPackFieldConstraint");
    }

    @Test
    void equalityCoversTheNameAndTheTypedConstraint() {
        CapabilityFieldSchema schema = new CapabilityFieldSchema("quantity", new CapabilityFieldConstraint.Text(8));

        assertThat(schema).isEqualTo(new CapabilityFieldSchema("quantity", new CapabilityFieldConstraint.Text(8)))
                .hasSameHashCodeAs(schema);
        assertThat(schema).isNotEqualTo(new CapabilityFieldSchema("quantity", new CapabilityFieldConstraint.Text(9)));
        assertThat(schema).isNotEqualTo(new CapabilityFieldSchema(
                "quantity", new CapabilityFieldConstraint.IntegerBounds(1, 8)));
    }

    @Test
    void theDiagnosticsCarryTheNameAndKindOnly() {
        CapabilityFieldSchema schema =
                new CapabilityFieldSchema("referenceCode", new CapabilityFieldConstraint.Text(128));

        assertThat(schema.toString()).contains("referenceCode", "STRING");
    }

    @Test
    void everyKindIsRepresentableBySomeConstraint() {
        List<CapabilityFieldConstraint> constraints = List.of(
                new CapabilityFieldConstraint.Text(8),
                new CapabilityFieldConstraint.IntegerBounds(0, 8),
                new CapabilityFieldConstraint.DecimalBounds(9, 2, new BigDecimal("0"), new BigDecimal("8")),
                CapabilityFieldConstraint.unconstrained(CapabilityValueKind.BOOLEAN),
                CapabilityFieldConstraint.unconstrained(CapabilityValueKind.DATE),
                CapabilityFieldConstraint.unconstrained(CapabilityValueKind.DATETIME),
                new CapabilityFieldConstraint.DurationBounds(0, 86400),
                new CapabilityFieldConstraint.EnumCodes(List.of("GOLD")),
                new CapabilityFieldConstraint.ReferenceTarget("MESSAGE_TEMPLATE"));

        assertThat(constraints.stream().map(CapabilityFieldConstraint::kind))
                .containsExactlyInAnyOrder(CapabilityValueKind.values());
    }

    private static CapabilityFieldConstraint text() {
        return new CapabilityFieldConstraint.Text(64);
    }

    private static void assertInvalid(Runnable construction, String invariant) {
        assertThatThrownBy(construction::run)
                .isInstanceOf(CompilerException.class)
                .satisfies(throwable -> {
                    CompilerException failure = (CompilerException) throwable;
                    assertThat(failure.code()).isEqualTo(CompilerErrorCodes.COMPILER_FROZEN_INPUT_INVALID);
                    assertThat(failure.details()).containsEntry("invariant", invariant);
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