package com.company.campaign.builder.capability.pack;

import com.company.campaign.builder.compiler.CompilerErrorCodes;
import com.company.campaign.builder.compiler.CompilerException;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A capability payload contract declares required and optional fields separately, and the two lists together
 * are the whole declaration: a repeated name is a duplicate field, and a name declared both required and
 * optional is an inconsistent declaration. Both fail closed.
 */
class CapabilityPayloadContractTest {

    @Test
    void theContractSeparatesRequiredFromOptionalFields() {
        CapabilityPayloadContract contract = new CapabilityPayloadContract(
                List.of(field("referenceCode"), field("variables")),
                List.of(field("quantity")));

        assertThat(contract.requiredNames()).containsExactly("referenceCode", "variables");
        assertThat(contract.optionalNames()).containsExactly("quantity");
        assertThat(contract.fields()).extracting(CapabilityFieldSchema::fieldName)
                .containsExactly("quantity", "referenceCode", "variables");
        assertThat(contract.declares("referenceCode")).isTrue();
        assertThat(contract.declares("quantity")).isTrue();
        assertThat(contract.declares("unknown")).isFalse();
    }

    @Test
    void anEmptyContractIsRepresentable() {
        CapabilityPayloadContract contract = new CapabilityPayloadContract(List.of(), List.of());

        assertThat(contract.requiredNames()).isEmpty();
        assertThat(contract.optionalNames()).isEmpty();
        assertThat(contract.fields()).isEmpty();
    }

    @Test
    void aRepeatedFieldNameInOneListFailsClosed() {
        assertInvalid(() -> new CapabilityPayloadContract(List.of(field("referenceCode"), field("referenceCode")),
                List.of()), "capabilityPackDuplicateField");
        assertInvalid(() -> new CapabilityPayloadContract(List.of(),
                List.of(field("quantity"), field("quantity"))), "capabilityPackDuplicateField");
    }

    @Test
    void aFieldDeclaredRequiredAndOptionalFailsClosed() {
        assertInvalid(() -> new CapabilityPayloadContract(List.of(field("referenceCode")),
                List.of(field("referenceCode"))), "capabilityPackInconsistentFieldDeclaration");
    }

    @Test
    void anUnboundedFieldCountFailsClosedAtExactlyTheBound() {
        assertThat(new CapabilityPayloadContract(List.of(), names(CapabilityPayloadContract.MAX_FIELDS)).fields())
                .hasSize(CapabilityPayloadContract.MAX_FIELDS);

        assertInvalid(() -> new CapabilityPayloadContract(List.of(),
                names(CapabilityPayloadContract.MAX_FIELDS + 1)), "capabilityPackFieldCount");
    }

    @Test
    void aNullListOrANullFieldFailsClosed() {
        assertInvalid(() -> new CapabilityPayloadContract(null, List.of()), "capabilityPackRequiredFields");
        assertInvalid(() -> new CapabilityPayloadContract(List.of(), null), "capabilityPackOptionalFields");

        List<CapabilityFieldSchema> withNull = new ArrayList<>();
        withNull.add(field("quantity"));
        withNull.add(null);
        assertInvalid(() -> new CapabilityPayloadContract(List.of(), withNull), "capabilityPackOptionalFields");
    }

    @Test
    void bothFieldListsAreCanonicalizedToOneDeterministicOrder() {
        CapabilityPayloadContract contract = new CapabilityPayloadContract(
                List.of(field("variables"), field("referenceCode")),
                List.of(field("quantity"), field("anchor")));

        assertThat(contract.requiredNames()).containsExactly("referenceCode", "variables");
        assertThat(contract.optionalNames()).containsExactly("anchor", "quantity");
        assertThat(contract).isEqualTo(new CapabilityPayloadContract(
                List.of(field("referenceCode"), field("variables")),
                List.of(field("anchor"), field("quantity"))))
                .hasSameHashCodeAs(contract);
    }

    @Test
    void theContractFreezesEveryFieldListItCarries() {
        List<CapabilityFieldSchema> required = new ArrayList<>(List.of(field("referenceCode")));
        CapabilityPayloadContract contract = new CapabilityPayloadContract(required, List.of());

        required.clear();

        assertThat(contract.requiredNames()).containsExactly("referenceCode");
        assertThatThrownBy(() -> contract.requiredFields().add(field("quantity")))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> contract.fields().add(field("quantity")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void theDiagnosticsReportCountsOnly() {
        CapabilityPayloadContract contract = new CapabilityPayloadContract(
                List.of(field("referenceCode")), List.of(field("quantity")));

        assertThat(contract.toString()).contains("required=1", "optional=1").doesNotContain("referenceCode");
    }

    private static CapabilityFieldSchema field(String name) {
        return new CapabilityFieldSchema(name, new CapabilityFieldConstraint.Text(64));
    }

    private static List<CapabilityFieldSchema> names(int size) {
        List<CapabilityFieldSchema> fields = new ArrayList<>(size);
        for (int index = 0; index < size; index++) {
            fields.add(field("field" + index));
        }
        return fields;
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
}