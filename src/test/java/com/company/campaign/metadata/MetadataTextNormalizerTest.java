package com.company.campaign.metadata;

import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * The metadata normalizer must be locale-independent, Unicode-aware and deterministic, because alias
 * collision detection and search ordering are built on it.
 */
class MetadataTextNormalizerTest {

    @Test
    void trimsAndCollapsesUnicodeWhitespaceAndCaseFolds() {
        assertThat(MetadataTextNormalizer.normalize("  Gold   Member ")).isEqualTo("gold member");
        assertThat(MetadataTextNormalizer.normalize("净  消费\n金额")).isEqualTo("净 消费 金额");
        assertThat(MetadataTextNormalizer.normalize("A\t\tB C")).isEqualTo("a b c");
    }

    @Test
    void appliesUnicodeNfkcBeforeCaseFolding() {
        // Full-width latin letters are compatibility-equivalent to ASCII and must collide after normalization.
        assertThat(MetadataTextNormalizer.normalize("Ｇｏｌｄ")).isEqualTo("gold");
        assertThat(MetadataTextNormalizer.normalize("①")).isEqualTo("1");
        assertThat(MetadataTextNormalizer.normalize("ﬁ")).isEqualTo("fi");
    }

    @Test
    void isIndependentOfTheProcessDefaultLocale() {
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(new Locale("tr", "TR"));
            // Uppercase I must still fold to ASCII 'i', not the Turkish dotless form.
            assertThat(MetadataTextNormalizer.normalize("I")).isEqualTo("i");
            assertThat(MetadataTextNormalizer.normalize("IRON")).isEqualTo("iron");
        } finally {
            Locale.setDefault(original);
        }
    }

    @Test
    void rejectsNullOrBlankTextWithTheStableTextCode() {
        for (String blank : new String[] {null, "", "   ", "\t\n"}) {
            Throwable thrown = catchThrowable(() -> MetadataTextNormalizer.requireNormalized(blank, "alias"));
            assertThat(thrown).isInstanceOf(MetadataException.class);
            assertThat(((MetadataException) thrown).code()).isEqualTo(MetadataErrorCodes.METADATA_TEXT_INVALID);
        }
    }

    @Test
    void requireNormalizedReturnsTheNormalizedFormForAcceptedText() {
        assertThat(MetadataTextNormalizer.requireNormalized("  Gold  ", "alias")).isEqualTo("gold");
    }
}