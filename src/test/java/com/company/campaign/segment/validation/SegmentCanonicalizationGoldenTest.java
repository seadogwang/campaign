package com.company.campaign.segment.validation;

import com.company.campaign.metadata.domain.ComparisonOperator;
import com.company.campaign.segment.dsl.value.IntegerValue;
import com.company.campaign.segment.dsl.value.MoneyValue;
import com.company.campaign.segment.dsl.window.WindowUnit;
import com.company.campaign.segment.validation.testsupport.SegmentValidationFixtures;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Golden test pinning the exact canonical semantic serialization and hash for one representative document.
 *
 * <p>A change to any normalization rule must either keep this byte-identical or bump the canonicalizer
 * version; it can never drift silently.
 */
class SegmentCanonicalizationGoldenTest {

    private static final String GOLDEN_BYTES =
            "{\"canonicalizerVersion\":\"segment-canonicalizer/1.2\","
                    + "\"metadata\":{\"metadataContentHash\":\"sha256:8bf69ce2f8241ab10ae64b8e4e3c87bf78d0bb1c7731d9f26b5b394399cfc6bb\","
                    + "\"metadataSchemaVersion\":\"1.0\",\"metadataSnapshotId\":\"metadata-17\","
                    + "\"metadataSnapshotVersion\":\"1\"},"
                    + "\"predicate\":{\"children\":["
                    + "{\"definition\":{\"currencySemantics\":\"NOT_APPLICABLE\",\"dataType\":\"ENUM\","
                    + "\"id\":\"member.tier\",\"kind\":\"ATTRIBUTE\",\"nullSemantics\":\"UNKNOWN_VALUE\","
                    + "\"subject\":\"MEMBER\"},\"operator\":\"EQUAL\",\"type\":\"ATTRIBUTE\","
                    + "\"value\":{\"kind\":\"ENUM\",\"value\":\"GOLD\"}},"
                    + "{\"definition\":{\"currencySemantics\":\"NOT_APPLICABLE\",\"dataType\":\"INTEGER\","
                    + "\"id\":\"member.age\",\"kind\":\"ATTRIBUTE\",\"nullSemantics\":\"UNKNOWN_VALUE\","
                    + "\"subject\":\"MEMBER\"},\"operator\":\"BETWEEN\",\"type\":\"ATTRIBUTE\","
                    + "\"value\":{\"includeLower\":true,\"includeUpper\":false,\"kind\":\"RANGE\","
                    + "\"lower\":{\"kind\":\"INTEGER\",\"value\":18},\"upper\":{\"kind\":\"INTEGER\",\"value\":65}}},"
                    + "{\"definition\":{\"aggregation\":\"SUM\",\"currencySemantics\":\"CONVERT_TO_REPORTING_CURRENCY\","
                    + "\"emptySetBehavior\":\"ZERO\",\"emptySetValue\":{\"dataType\":\"MONEY\",\"value\":\"0\"},"
                    + "\"eventTimeSemantics\":\"EVENT_TIME\",\"id\":\"order.net_paid_amount\",\"kind\":\"METRIC\","
                    + "\"nullSemantics\":\"UNKNOWN_VALUE\",\"resultType\":\"MONEY\",\"subject\":\"MEMBER\","
                    + "\"windowBoundarySemantics\":\"INCLUSIVE_START_EXCLUSIVE_END\"},"
                    + "\"operator\":\"GREATER_THAN\",\"type\":\"METRIC\","
                    + "\"value\":{\"amount\":\"1000\",\"currency\":\"CNY\",\"kind\":\"MONEY\"},"
                    + "\"window\":{\"days\":90,\"type\":\"ROLLING\"}}],"
                    + "\"type\":\"ALL\"},"
                    + "\"schemaVersion\":\"1.3\",\"subject\":\"MEMBER\"}";

    private static final String GOLDEN_HASH =
            "sha256:bf48c169dc6d4f92c82cfd130ca04ce2abe3713526054e6abc35ef86afde31da";

    private static SegmentValidationResult validateRepresentativeDocument() {
        return SegmentValidationFixtures.validate(
                SegmentValidationFixtures.publish(SegmentValidationFixtures.all("n-root", List.of(
                        SegmentValidationFixtures.tierEquals("n-tier", "GOLD"),
                        SegmentValidationFixtures.attribute("n-age", "member.age", ComparisonOperator.BETWEEN,
                                SegmentValidationFixtures.range(new IntegerValue(18), new IntegerValue(65),
                                        true, false)),
                        SegmentValidationFixtures.metric("n-spend", "order.net_paid_amount",
                                SegmentValidationFixtures.rolling(90, WindowUnit.DAY),
                                ComparisonOperator.GREATER_THAN,
                                SegmentValidationFixtures.value(new MoneyValue("1000.00", "CNY")))))));
    }

    @Test
    void theCanonicalSerializationIsByteIdenticalToTheGoldenValue() {
        SegmentValidationResult result = validateRepresentativeDocument();

        assertThat(result.valid()).isTrue();
        assertThat(SegmentValidationFixtures.canonicalBytes(result))
                .isEqualTo(GOLDEN_BYTES.getBytes(StandardCharsets.UTF_8));
        assertThat(result.canonicalAstHash().value()).isEqualTo(GOLDEN_HASH);
    }

    @Test
    void theCanonicalSerializationExcludesEditableIdentityAndDisplayText() {
        String canonical = SegmentValidationFixtures.canonicalString(validateRepresentativeDocument());

        assertThat(canonical)
                .doesNotContain("\"nodeId\"")
                .doesNotContain("editableDslHash")
                .doesNotContain("n-root")
                .doesNotContain("n-tier")
                .doesNotContain("Net Paid Amount")
                .doesNotContain("Member Tier");
    }

    @Test
    void theGoldenHashIsNotTheEditableHash() {
        SegmentValidationResult result = validateRepresentativeDocument();
        assertThat(result.canonicalAstHash().value()).isNotEqualTo(result.editableDslHash().value());
    }
}