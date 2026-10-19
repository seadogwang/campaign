package com.company.campaign.segment.intent;

import com.company.campaign.metadata.domain.AggregateFunction;
import com.company.campaign.metadata.domain.ComparisonOperator;
import com.company.campaign.metadata.domain.RelationJoinKind;
import com.company.campaign.metadata.domain.RelationScope;
import com.company.campaign.segment.intent.testsupport.IntentFixtures;
import com.company.campaign.shared.canonical.ContentHash;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Requirement 1: strict JSON round-trip for every Intent, provenance, value, window and logical-expression
 * variant. Canonical bytes are stable across repeated writes and a reparse reproduces the same object.
 */
class SegmentIntentCodecRoundTripTest {

    private final SegmentIntentCodec codec = new SegmentIntentCodec();

    @Test
    void canonicalBytesAndObjectRoundTripForEveryVariant() {
        for (SegmentIntent intent : everyVariant()) {
            byte[] bytes = codec.write(intent);
            assertThat(codec.writeString(intent)).isEqualTo(new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
            assertThat(bytes).doesNotContain((byte) '\n');

            RegisteredDefaultSource registry = IntentFixtures.currencyRegistry();
            SegmentIntent reparsed = codec.parse(bytes, IntentFixtures.UTTERANCE, registry);
            assertThat(reparsed).as("round trip of %s", intent).isEqualTo(intent);
            assertThat(codec.write(reparsed)).as("canonical bytes of %s", intent).isEqualTo(bytes);
        }
    }

    @Test
    void allValueKindsRoundTrip() {
        List<IntentValue> values = List.of(
                new IntentValue.StringValue("vip"),
                new IntentValue.StringValue(""),
                new IntentValue.BooleanValue(true),
                new IntentValue.IntegerValue("42"),
                new IntentValue.DecimalValue("12.50"),
                new IntentValue.DateValue("2026-10-01"),
                new IntentValue.TimestampValue("2026-10-01T02:00:00Z"),
                new IntentValue.EnumValue("Gold"),
                new IntentValue.MoneyValue("1000"),
                new IntentValue.ListValue(List.of(new IntentValue.EnumValue("Gold"), new IntentValue.EnumValue("Silver"))),
                new IntentValue.RangeValue(
                        new IntentValue.DecimalValue("1.5"), new IntentValue.DecimalValue("9.5"), true, false));
        for (IntentValue value : values) {
            SegmentIntent intent = single(IntentFixtures.withValue(baseClause(), value));
            byte[] bytes = codec.write(intent);
            assertThat(codec.parse(bytes, IntentFixtures.UTTERANCE, IntentFixtures.currencyRegistry()))
                    .isEqualTo(intent);
        }
    }

    @Test
    void numericLiteralsAreCanonicalizedBeforeHashing() {
        SegmentIntent padded = single(IntentFixtures.withValue(baseClause(), new IntentValue.DecimalValue("007.500")));
        SegmentIntent plain = single(IntentFixtures.withValue(baseClause(), new IntentValue.DecimalValue("7.5")));
        assertThat(SegmentIntentHasher.hash(padded)).isEqualTo(SegmentIntentHasher.hash(plain));
        assertThat(codec.write(padded)).isEqualTo(codec.write(plain));
    }

    @Test
    void everyLogicalExpressionShapeRoundTrips() {
        IntentClause a = IntentFixtures.clause("c1", "A", IntentFixtures.FULL_SPAN);
        IntentClause b = IntentFixtures.clause("c2", "B", IntentFixtures.FULL_SPAN);
        IntentClause c = IntentFixtures.clause("c3", "C", IntentFixtures.FULL_SPAN);

        record Shape(LogicalExpression expression, List<IntentClause> clauses) {
        }
        List<Shape> shapes = List.of(
                new Shape(new LogicalExpression.Clause("c1"), List.of(a)),
                new Shape(new LogicalExpression.Group(LogicalOperator.ALL, List.of(
                        new LogicalExpression.Clause("c1"), new LogicalExpression.Clause("c2"))), List.of(a, b)),
                new Shape(new LogicalExpression.Group(LogicalOperator.ANY, List.of(
                        new LogicalExpression.Clause("c1"), new LogicalExpression.Clause("c2"))), List.of(a, b)),
                new Shape(new LogicalExpression.Group(LogicalOperator.ALL, List.of(
                        new LogicalExpression.Clause("c1"),
                        new LogicalExpression.Group(LogicalOperator.ANY, List.of(
                                new LogicalExpression.Clause("c2"), new LogicalExpression.Clause("c3"))))),
                        List.of(a, b, c)),
                new Shape(new LogicalExpression.Negation(new LogicalExpression.Clause("c1")), List.of(a)));
        for (Shape shape : shapes) {
            SegmentIntent intent = new SegmentIntent(
                    com.company.campaign.contracts.ContractVersions.CURRENT,
                    com.company.campaign.metadata.domain.SubjectKind.MEMBER,
                    shape.clauses(), shape.expression(), List.of(), List.of());
            byte[] bytes = codec.write(intent);
            assertThat(codec.parse(bytes, IntentFixtures.UTTERANCE, IntentFixtures.currencyRegistry()))
                    .isEqualTo(intent);
        }
    }

    @Test
    void bothProvenanceFormsRoundTrip() {
        ContentHash hash = IntentFixtures.hash("CNY");
        IntentProvenance.FromRegisteredDefault registered = new IntentProvenance.FromRegisteredDefault(
                "reporting.currency", "1", hash);
        SegmentIntent intent = single(IntentFixtures.clause("c1", "CURRENCY", registered));
        byte[] bytes = codec.write(intent);
        assertThat(codec.parse(bytes, IntentFixtures.UTTERANCE, IntentFixtures.currencyRegistry()))
                .isEqualTo(intent);
    }

    @Test
    void structuredWindowAmbiguityRoundTripsWithoutChangingLegacyShape() {
        IntentClause first = IntentFixtures.withWindow(
                IntentFixtures.clause("c1", "FIRST", IntentFixtures.FULL_SPAN),
                new IntentWindow.Rolling(90, IntentWindowUnit.DAY));
        IntentClause second = IntentFixtures.withWindow(
                IntentFixtures.clause("c2", "SECOND", IntentFixtures.FULL_SPAN),
                new IntentWindow.Lifetime());
        SegmentIntent intent = new SegmentIntent(
                com.company.campaign.contracts.ContractVersions.CURRENT,
                com.company.campaign.metadata.domain.SubjectKind.MEMBER,
                List.of(first, second),
                new LogicalExpression.Group(LogicalOperator.ALL, List.of(
                        new LogicalExpression.Clause("c1"), new LogicalExpression.Clause("c2"))),
                List.of(IntentAmbiguity.AMBIGUOUS_WINDOW),
                List.of(),
                List.of(new IntentWindowAmbiguity("c2", List.of(
                        new IntentWindowAlternative.InheritWindow("c1"),
                        new IntentWindowAlternative.ExplicitWindow(new IntentWindow.Lifetime())))));

        String json = codec.writeString(intent);

        assertThat(json)
                .contains("\"code\":\"AMBIGUOUS_WINDOW\"")
                .contains("\"target\":\"c2.window\"")
                .contains("\"kind\":\"INHERIT_WINDOW\"")
                .contains("\"kind\":\"LIFETIME\"");
        assertThat(codec.parse(json.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                IntentFixtures.UTTERANCE, IntentFixtures.currencyRegistry())).isEqualTo(intent);
    }

    @Test
    void structuredFilterScopeAmbiguityRoundTrips() {
        IntentScopeFilter filter = new IntentScopeFilter(
                "f1", "PRODUCT", ComparisonOperator.EQUAL,
                new IntentValue.StringValue("Gold"),
                new IntentProvenance.FromSourceSpan(IntentFixtures.FULL_SPAN), List.of(), List.of());
        IntentClause first = new IntentClause(
                "c1", "FIRST", com.company.campaign.metadata.MetadataKind.METRIC, false,
                ComparisonOperator.GREATER_THAN, new IntentValue.IntegerValue("0"),
                new IntentWindow.Lifetime(), null, AggregateFunction.SUM, null,
                List.of(filter), new IntentProvenance.FromSourceSpan(IntentFixtures.FULL_SPAN),
                List.of(), List.of());
        IntentClause second = new IntentClause(
                "c2", "SECOND", com.company.campaign.metadata.MetadataKind.METRIC, false,
                ComparisonOperator.GREATER_THAN, new IntentValue.IntegerValue("0"),
                new IntentWindow.Lifetime(), null, AggregateFunction.SUM, null,
                new IntentProvenance.FromSourceSpan(IntentFixtures.FULL_SPAN), List.of(), List.of());
        SegmentIntent intent = new SegmentIntent(
                com.company.campaign.contracts.ContractVersions.CURRENT,
                com.company.campaign.metadata.domain.SubjectKind.MEMBER,
                List.of(first, second),
                new LogicalExpression.Group(LogicalOperator.ALL, List.of(
                        new LogicalExpression.Clause("c1"), new LogicalExpression.Clause("c2"))),
                List.of(IntentAmbiguity.AMBIGUOUS_FILTER_SCOPE),
                List.of(),
                List.of(),
                List.of(new IntentFilterScopeAmbiguity("c2", List.of(
                        new IntentFilterScopeAlternative.InheritScopeFilters("c1"),
                        new IntentFilterScopeAlternative.Independent()))));

        String json = codec.writeString(intent);

        assertThat(json)
                .contains("\"code\":\"AMBIGUOUS_FILTER_SCOPE\"")
                .contains("\"target\":\"c2.scopeFilters\"")
                .contains("\"kind\":\"INHERIT_SCOPE_FILTERS\"")
                .contains("\"kind\":\"INDEPENDENT\"");
        assertThat(codec.parse(json.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                IntentFixtures.UTTERANCE, IntentFixtures.currencyRegistry())).isEqualTo(intent);
    }

    private static IntentClause baseClause() {
        return IntentFixtures.clause("c1", "VALUE", IntentFixtures.FULL_SPAN);
    }

    private static SegmentIntent single(IntentClause clause) {
        return IntentFixtures.intent(clause);
    }

    private static List<SegmentIntent> everyVariant() {
        List<SegmentIntent> intents = new ArrayList<>();
        intents.add(IntentFixtures.standardIntent());
        intents.add(single(IntentFixtures.withWindow(baseClause(),
                new IntentWindow.Lifetime())));
        intents.add(single(IntentFixtures.withWindow(baseClause(),
                new IntentWindow.Fixed("2026-10-01T00:00:00Z", "2026-11-01T00:00:00Z"))));
        intents.add(single(IntentFixtures.withCurrency(baseClause(),
                new IntentCurrency.Explicit("CNY"))));
        intents.add(single(IntentFixtures.withCurrency(baseClause(),
                new IntentCurrency.RegisteredDefault("reporting.currency", "1", IntentFixtures.hash("CNY")))));
        intents.add(single(IntentFixtures.withAggregation(baseClause(), AggregateFunction.SUM)));
        intents.add(single(IntentFixtures.withRelationScope(baseClause(),
                new RelationScope(RelationJoinKind.CORRELATED_BY_SOURCE_KEY, "member.id", "order.member_id"))));
        intents.add(single(IntentFixtures.withOperator(baseClause(), ComparisonOperator.EQUAL)));
        intents.add(single(IntentFixtures.clause("c1", "CURRENCY",
                new IntentProvenance.FromRegisteredDefault("reporting.currency", "1", IntentFixtures.hash("CNY")))));
        return intents;
    }
}
