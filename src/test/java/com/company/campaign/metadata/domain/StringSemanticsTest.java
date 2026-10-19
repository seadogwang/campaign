package com.company.campaign.metadata.domain;

import com.company.campaign.contracts.LocaleTag;
import com.company.campaign.metadata.MetadataErrorCodes;
import com.company.campaign.metadata.testsupport.MetadataFixtures;
import com.company.campaign.metadata.testsupport.RecordOverride;
import com.company.campaign.shared.canonical.CanonicalJson;
import com.company.campaign.testsupport.ContractAssertions;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Finding 2: string comparison semantics are frozen, structurally explicit and required for exactly the
 * STRING definitions.
 */
class StringSemanticsTest {

    private static final CanonicalJson JSON = new CanonicalJson();

    @Test
    void normalizationPolicyIsAClosedEnum() {
        assertThat(StringNormalization.values()).containsExactly(
                StringNormalization.NONE,
                StringNormalization.NFC,
                StringNormalization.NFD,
                StringNormalization.NFKC,
                StringNormalization.NFKD);
    }

    @Test
    void localeNeutralAndLocaleSpecificModesAreStructurallyExplicit() {
        StringSemantics neutral = StringSemantics.localeNeutral(
                StringCaseSensitivity.CASE_INSENSITIVE, StringNormalization.NFKC);
        assertThat(neutral.localeMode()).isEqualTo(StringLocaleMode.LOCALE_NEUTRAL);
        assertThat(neutral.locale()).isNull();
        assertThat(neutral.isLocaleNeutral()).isTrue();

        StringSemantics specific = StringSemantics.ofLocale(
                StringCaseSensitivity.CASE_SENSITIVE, StringNormalization.NFC, new LocaleTag("zh-CN"));
        assertThat(specific.localeMode()).isEqualTo(StringLocaleMode.LOCALE_SPECIFIC);
        assertThat(specific.locale()).isEqualTo(new LocaleTag("zh-CN"));
        assertThat(specific.locale().toLocale().toLanguageTag()).isEqualTo("zh-CN");
        assertThat(specific.isLocaleNeutral()).isFalse();
    }

    @Test
    void partialOrContradictoryDeclarationsAreRejected() {
        // A locale-neutral declaration must not carry a locale tag.
        ContractAssertions.assertFailsWithCode(
                () -> new StringSemantics(StringCaseSensitivity.CASE_SENSITIVE, StringNormalization.NONE,
                        StringLocaleMode.LOCALE_NEUTRAL, new LocaleTag("zh-CN")),
                MetadataErrorCodes.METADATA_SEMANTIC_COMBINATION_INVALID);
        // A locale-specific declaration must carry one.
        ContractAssertions.assertFailsWithCode(
                () -> new StringSemantics(StringCaseSensitivity.CASE_SENSITIVE, StringNormalization.NONE,
                        StringLocaleMode.LOCALE_SPECIFIC, null),
                MetadataErrorCodes.METADATA_SEMANTIC_COMBINATION_INVALID);
        ContractAssertions.assertFailsWithCode(
                () -> new StringSemantics(null, StringNormalization.NONE,
                        StringLocaleMode.LOCALE_NEUTRAL, null),
                MetadataErrorCodes.METADATA_SEMANTIC_COMBINATION_INVALID);
        ContractAssertions.assertFailsWithCode(
                () -> new StringSemantics(StringCaseSensitivity.CASE_SENSITIVE, null,
                        StringLocaleMode.LOCALE_NEUTRAL, null),
                MetadataErrorCodes.METADATA_SEMANTIC_COMBINATION_INVALID);
        ContractAssertions.assertFailsWithCode(
                () -> new StringSemantics(StringCaseSensitivity.CASE_SENSITIVE, StringNormalization.NONE,
                        null, null),
                MetadataErrorCodes.METADATA_SEMANTIC_COMBINATION_INVALID);
    }

    @Test
    void equalDeclarationsAreEqualAndHashTheSame() {
        StringSemantics first = StringSemantics.ofLocale(
                StringCaseSensitivity.CASE_INSENSITIVE, StringNormalization.NFKC, new LocaleTag("ZH-cn"));
        StringSemantics second = StringSemantics.ofLocale(
                StringCaseSensitivity.CASE_INSENSITIVE, StringNormalization.NFKC, new LocaleTag("zh-CN"));
        assertThat(first).isEqualTo(second);
        assertThat(first.hashCode()).isEqualTo(second.hashCode());
    }

    @Test
    void everyStringDefinitionMustDeclareSemantics() {
        ContractAssertions.assertFailsWithCode(
                () -> RecordOverride.with(
                        MetadataFixtures.snapshotAttribute("member.city", "Member City"),
                        "stringSemantics", null),
                MetadataErrorCodes.METADATA_SEMANTIC_COMBINATION_INVALID);

        // Every non-string type carries no string semantics, and one is rejected if declared.
        for (SemanticDataType type : List.of(SemanticDataType.BOOLEAN, SemanticDataType.INTEGER,
                SemanticDataType.DECIMAL, SemanticDataType.DATE, SemanticDataType.TIMESTAMP)) {
            Map<String, Object> absent = new LinkedHashMap<>();
            absent.put("dataType", type);
            absent.put("stringSemantics", null);
            AttributeDefinition attribute = RecordOverride.withAll(
                    MetadataFixtures.snapshotAttribute("member.city", "Member City"), absent);
            assertThat(attribute.dataType()).isEqualTo(type);
            assertThat(attribute.stringSemantics()).isNull();
            MetadataFixtures.snapshot(List.of(attribute), List.of(), List.of());

            Map<String, Object> present = new LinkedHashMap<>();
            present.put("dataType", type);
            present.put("stringSemantics", MetadataFixtures.stringSemantics());
            ContractAssertions.assertFailsWithCode(
                    () -> RecordOverride.withAll(
                            MetadataFixtures.snapshotAttribute("member.city", "Member City"), present),
                    MetadataErrorCodes.METADATA_SEMANTIC_COMBINATION_INVALID);
        }

        // ENUM and MONEY definitions are non-string and must not carry string semantics.
        assertThat(MetadataFixtures.tierAttribute().stringSemantics()).isNull();
        assertThat(MetadataFixtures.netPaidAmountMetric().stringSemantics()).isNull();
        ContractAssertions.assertFailsWithCode(
                () -> RecordOverride.with(
                        MetadataFixtures.netPaidAmountMetric(),
                        "stringSemantics", MetadataFixtures.stringSemantics()),
                MetadataErrorCodes.METADATA_SEMANTIC_COMBINATION_INVALID);
    }

    @Test
    void stringMetricsMustDeclareSemantics() {
        ContractAssertions.assertFailsWithCode(
                () -> RecordOverride.with(MetadataFixtures.textMinMetric(), "stringSemantics", null),
                MetadataErrorCodes.METADATA_SEMANTIC_COMBINATION_INVALID);
    }

    @Test
    void stringSemanticsRoundTripsThroughCanonicalJson() {
        AttributeDefinition localized = RecordOverride.with(
                MetadataFixtures.snapshotAttribute("member.city", "Member City"),
                "stringSemantics", StringSemantics.ofLocale(
                        StringCaseSensitivity.CASE_SENSITIVE, StringNormalization.NFC, new LocaleTag("zh-CN")));
        AttributeDefinition neutral = MetadataFixtures.snapshotAttribute("member.country", "Member Country");

        MetadataSnapshot snapshot = MetadataFixtures.snapshot(
                List.of(localized, neutral), List.of(), List.of());
        String canonical = JSON.writeString(snapshot);
        MetadataSnapshot restored = JSON.read(canonical, MetadataSnapshot.class);

        assertThat(restored).isEqualTo(snapshot);
        assertThat(JSON.writeString(restored)).isEqualTo(canonical);
        assertThat(restored.attributes().stream()
                .filter(attribute -> attribute.id().equals("member.city"))
                .findFirst().orElseThrow().stringSemantics())
                .isEqualTo(localized.stringSemantics());
    }
}