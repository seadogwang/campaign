package com.company.campaign.builder.capability.pack;

import com.company.campaign.builder.compiler.CompilerErrorCodes;
import com.company.campaign.builder.compiler.CompilerException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The capability pack value-kind vocabulary is a closed, capability-agnostic set. A pack may declare a field
 * of exactly one of the nine known kinds, and any other token fails closed rather than being coerced.
 */
class CapabilityValueKindTest {

    @Test
    void theVocabularyIsExactlyTheNineDeclaredKinds() {
        assertThat(CapabilityValueKind.values()).containsExactly(
                CapabilityValueKind.STRING,
                CapabilityValueKind.INTEGER,
                CapabilityValueKind.DECIMAL,
                CapabilityValueKind.BOOLEAN,
                CapabilityValueKind.DATE,
                CapabilityValueKind.DATETIME,
                CapabilityValueKind.DURATION,
                CapabilityValueKind.ORDERED_ENUM,
                CapabilityValueKind.REFERENCE);
    }

    @Test
    void everyKindParsesFromItsOwnCanonicalName() {
        for (CapabilityValueKind kind : CapabilityValueKind.values()) {
            assertThat(CapabilityValueKind.parse(kind.name())).isEqualTo(kind);
        }
    }

    @Test
    void anUnknownOrBlankKindFailsClosedWithoutEchoingIt() {
        for (String token : List.of("ENUM", "MONEY", "TIMESTAMP", "string", "STRING ", "", " ", "a".repeat(65))) {
            assertUnknown(token);
        }
        assertUnknown(null);
    }

    @Test
    void orderedKindsAreExactlyTheKindsWithATotalOrder() {
        assertThat(CapabilityValueKind.ORDERED_ENUM.isOrdered()).isTrue();
        assertThat(CapabilityValueKind.INTEGER.isOrdered()).isTrue();
        assertThat(CapabilityValueKind.DECIMAL.isOrdered()).isTrue();
        assertThat(CapabilityValueKind.DATE.isOrdered()).isTrue();
        assertThat(CapabilityValueKind.DATETIME.isOrdered()).isTrue();
        assertThat(CapabilityValueKind.DURATION.isOrdered()).isTrue();
        assertThat(CapabilityValueKind.STRING.isOrdered()).isFalse();
        assertThat(CapabilityValueKind.BOOLEAN.isOrdered()).isFalse();
        assertThat(CapabilityValueKind.REFERENCE.isOrdered()).isFalse();
    }

    @Test
    void onlyTheReferenceKindIsAReference() {
        for (CapabilityValueKind kind : CapabilityValueKind.values()) {
            assertThat(kind.isReference()).isEqualTo(kind == CapabilityValueKind.REFERENCE);
        }
    }

    private static void assertUnknown(String token) {
        assertThatThrownBy(() -> CapabilityValueKind.parse(token))
                .isInstanceOf(CompilerException.class)
                .satisfies(throwable -> {
                    CompilerException failure = (CompilerException) throwable;
                    assertThat(failure.code()).isEqualTo(CompilerErrorCodes.COMPILER_FROZEN_INPUT_INVALID);
                    assertThat(failure.details()).containsEntry("invariant", "capabilityPackValueKind");
                    if (token != null && token.length() >= 4) {
                        assertThat(failure.getMessage()).doesNotContain(token);
                    }
                });
    }
}