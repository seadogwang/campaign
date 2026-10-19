package com.company.campaign.segment.validation;

import com.company.campaign.contracts.Issue;
import com.company.campaign.metadata.domain.ComparisonOperator;
import com.company.campaign.segment.dsl.predicate.Predicate;
import com.company.campaign.segment.dsl.value.MoneyValue;
import com.company.campaign.segment.validation.testsupport.SegmentValidationFixtures;
import com.company.campaign.shared.canonical.CanonicalJson;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Requirements 17 and 18: repeated compilation is byte-identical and every issue is non-disclosing. */
class SegmentValidationDeterminismTest {

    private static final CanonicalJson CANONICAL_JSON = new CanonicalJson();

    @Test
    void theSameInputsCompiledOneHundredTimesProduceIdenticalResults() {
        SegmentValidator validator = new SegmentValidator(SegmentValidationFixtures.allowAll());
        var document = SegmentValidationFixtures.validDocument();
        var snapshot = SegmentValidationFixtures.snapshot();
        var context = SegmentValidationFixtures.context();
        var policy = SegmentValidationFixtures.policy();

        SegmentValidationResult first =
                validator.validate(document, snapshot, context, policy);
        byte[] firstBytes = new com.company.campaign.segment.canonical.CanonicalSegmentCodec()
                .write(first.canonicalSegment());
        String firstIssues = CANONICAL_JSON.writeString(first.issues());

        for (int run = 0; run < 100; run++) {
            SegmentValidationResult repeated =
                    validator.validate(document, snapshot, context, policy);
            assertThat(repeated).isEqualTo(first);
            assertThat(repeated.canonicalAstHash()).isEqualTo(first.canonicalAstHash());
            assertThat(repeated.editableDslHash()).isEqualTo(first.editableDslHash());
            assertThat(new com.company.campaign.segment.canonical.CanonicalSegmentCodec()
                    .write(repeated.canonicalSegment())).isEqualTo(firstBytes);
            assertThat(CANONICAL_JSON.writeString(repeated.issues())).isEqualTo(firstIssues);
        }
    }

    @Test
    void aRejectedInputRepeatsWithIdenticalIssueSerialization() {
        Predicate root = SegmentValidationFixtures.all("n-root", List.of(
                SegmentValidationFixtures.attribute("n-unknown", "member.unknown", ComparisonOperator.EQUAL,
                        SegmentValidationFixtures.value(SegmentValidationFixtures.stringValue("secret-token"))),
                SegmentValidationFixtures.metric("n-metric", "order.count",
                        SegmentValidationFixtures.rolling(30, com.company.campaign.segment.dsl.window.WindowUnit.DAY),
                        ComparisonOperator.GREATER_THAN,
                        SegmentValidationFixtures.value(new MoneyValue("1000.00", "CNY")))));
        var document = SegmentValidationFixtures.publish(root);

        String first = CANONICAL_JSON.writeString(SegmentValidationFixtures.validate(document).issues());
        for (int run = 0; run < 50; run++) {
            assertThat(CANONICAL_JSON.writeString(SegmentValidationFixtures.validate(document).issues()))
                    .isEqualTo(first);
        }
    }

    @Test
    void issueSerializationIsNonDisclosing() {
        Predicate root = SegmentValidationFixtures.all("n-root", List.of(
                SegmentValidationFixtures.attribute("n-unknown", "member.unknown", ComparisonOperator.EQUAL,
                        SegmentValidationFixtures.value(SegmentValidationFixtures.stringValue("secret-token"))),
                SegmentValidationFixtures.attribute("n-tier", "member.tier", ComparisonOperator.GREATER_THAN,
                        SegmentValidationFixtures.value(SegmentValidationFixtures.enumValue("GOLD")))));

        SegmentValidationResult result = SegmentValidationFixtures.validate(
                SegmentValidationFixtures.publish(root), SegmentValidationFixtures.snapshot(),
                SegmentValidationFixtures.context(), SegmentValidationFixtures.denyAll(),
                SegmentValidationFixtures.policy());

        List<String> serialized = new ArrayList<>();
        for (Issue issue : result.issues()) {
            serialized.add(issue.code());
            serialized.add(issue.message());
            serialized.add(String.valueOf(issue.details()));
        }
        String combined = String.join("|", serialized);

        assertThat(combined)
                .doesNotContain("secret-token")
                .doesNotContain("GOLD")
                .doesNotContain("1000.00")
                .doesNotContain("member.unknown")
                .doesNotContain("member.tier")
                .doesNotContain("tenant-001")
                .doesNotContain("program-001")
                .doesNotContain("Asia/Shanghai")
                .doesNotContain("boundary failure");

        assertThat(result.issues()).allMatch(issue -> issue.sourceSpan() == null);
    }

    @Test
    void unsafeBytesAreNeverCarriedInIssueMessages() {
        SegmentValidationResult result = SegmentValidationFixtures.validate(
                SegmentValidationFixtures.tierEquals("n", "PLATINUM"));

        assertThat(result.issues()).isNotEmpty();
        for (Issue issue : result.issues()) {
            assertThat(new String(issue.message().getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8))
                    .isEqualTo(issue.message());
            assertThat(issue.details().values()).allMatch(value ->
                    value instanceof String || value instanceof Number || value instanceof Boolean
                            || value instanceof java.util.Map<?, ?>);
        }
    }
}