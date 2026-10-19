package com.company.campaign.segment.dsl;

import com.company.campaign.contracts.SchemaVersion;
import com.company.campaign.metadata.MetadataSchemaVersions;
import com.company.campaign.metadata.domain.ComparisonOperator;
import com.company.campaign.metadata.domain.SubjectKind;
import com.company.campaign.segment.dsl.codec.SegmentDslCodec;
import com.company.campaign.segment.dsl.contract.MetadataBinding;
import com.company.campaign.segment.dsl.contract.SegmentDsl;
import com.company.campaign.segment.dsl.contract.SegmentDslErrorCodes;
import com.company.campaign.segment.dsl.contract.SegmentDslSchemaVersions;
import com.company.campaign.segment.dsl.predicate.AttributePredicate;
import com.company.campaign.segment.dsl.predicate.ConditionValue;
import com.company.campaign.segment.dsl.predicate.ExistsPredicate;
import com.company.campaign.segment.dsl.predicate.GroupPredicate;
import com.company.campaign.segment.dsl.predicate.MetricPredicate;
import com.company.campaign.segment.dsl.predicate.NotPredicate;
import com.company.campaign.segment.dsl.predicate.Predicate;
import com.company.campaign.segment.dsl.predicate.PredicateType;
import com.company.campaign.segment.dsl.testsupport.SegmentDslFixtures;
import com.company.campaign.segment.dsl.value.BooleanValue;
import com.company.campaign.segment.dsl.value.DateValue;
import com.company.campaign.segment.dsl.value.DateTimeValue;
import com.company.campaign.segment.dsl.value.DecimalValue;
import com.company.campaign.segment.dsl.value.EnumValue;
import com.company.campaign.segment.dsl.value.IntegerValue;
import com.company.campaign.segment.dsl.value.ListValue;
import com.company.campaign.segment.dsl.value.MoneyValue;
import com.company.campaign.segment.dsl.value.RangeValue;
import com.company.campaign.segment.dsl.value.StringValue;
import com.company.campaign.segment.dsl.value.TypedValue;
import com.company.campaign.segment.dsl.window.FixedWindow;
import com.company.campaign.segment.dsl.window.LifetimeWindow;
import com.company.campaign.segment.dsl.window.RollingWindow;
import com.company.campaign.segment.dsl.window.TimeWindow;
import com.company.campaign.segment.dsl.window.WindowUnit;
import com.company.campaign.shared.canonical.ContentHash;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.stream.Stream;

import static com.company.campaign.testsupport.ContractAssertions.assertFailsWithCode;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Field-complete coverage of the {@code editableDslHash} mutation surface.
 *
 * <p>Every editable component is exercised by a structurally valid pair of documents that differ in exactly
 * one place, so a constructor rejection can never be mistaken for hash coverage. The two published schema
 * versions are constants that cannot produce an alternate valid document; their participation is asserted
 * through the wire projection instead.
 */
class SegmentDslEditableHashCoverageTest {

    private static final SegmentDslCodec CODEC = new SegmentDslCodec();

    private static AttributePredicate attr(String nodeId, String attributeId, ComparisonOperator operator,
                                           TypedValue value) {
        return new AttributePredicate(nodeId, attributeId, operator, ConditionValue.of(value));
    }

    private static AttributePredicate nullAttr(String nodeId, ComparisonOperator operator) {
        return new AttributePredicate(nodeId, "member.tier", operator, ConditionValue.none());
    }

    private static MetricPredicate metric(String nodeId, String metricId, ComparisonOperator operator,
                                          TypedValue value, TimeWindow window) {
        return new MetricPredicate(nodeId, metricId, window, operator, ConditionValue.of(value));
    }

    private static MetricPredicate metric(String nodeId, ComparisonOperator operator, TypedValue value,
                                          TimeWindow window) {
        return metric(nodeId, "order.count", operator, value, window);
    }

    private static GroupPredicate group(String nodeId, PredicateType type, Predicate... children) {
        return new GroupPredicate(nodeId, type, List.of(children));
    }

    private static ExistsPredicate exists(String nodeId, String relationId, TimeWindow window, Predicate nested) {
        return new ExistsPredicate(nodeId, relationId, window, nested);
    }

    private static final TypedValue GOLD = new EnumValue("GOLD");
    private static final TimeWindow ROLLING_30_DAYS = new RollingWindow(30, WindowUnit.DAY);

    /** One entry per editable predicate/value/window component; the pair differs in exactly that component. */
    static Stream<Arguments> editablePredicateMutations() {
        AttributePredicate gold = attr("n-a", "member.tier", ComparisonOperator.EQUAL, GOLD);
        AttributePredicate silver = attr("n-a", "member.tier", ComparisonOperator.EQUAL, new EnumValue("SILVER"));
        AttributePredicate silverB = attr("n-b", "member.tier", ComparisonOperator.EQUAL, new EnumValue("SILVER"));

        return Stream.of(
                // Predicate discriminator and every predicate field.
                Arguments.of("ALL/ANY discriminator",
                        group("n-root", PredicateType.ALL, gold), group("n-root", PredicateType.ANY, gold)),
                Arguments.of("group nodeId",
                        group("n-root", PredicateType.ALL, gold), group("n-root-2", PredicateType.ALL, gold)),
                Arguments.of("group child order",
                        group("n-root", PredicateType.ALL, gold, silverB), group("n-root", PredicateType.ALL, silverB, gold)),
                Arguments.of("group child count",
                        group("n-root", PredicateType.ALL, gold), group("n-root", PredicateType.ALL, gold, silverB)),
                Arguments.of("NOT nodeId",
                        new NotPredicate("n-not", gold), new NotPredicate("n-not-2", gold)),
                Arguments.of("NOT child",
                        new NotPredicate("n-not", gold), new NotPredicate("n-not", silver)),
                Arguments.of("ATTRIBUTE nodeId",
                        attr("n-a", "member.tier", ComparisonOperator.EQUAL, GOLD),
                        attr("n-a-2", "member.tier", ComparisonOperator.EQUAL, GOLD)),
                Arguments.of("attributeId",
                        attr("n-a", "member.tier", ComparisonOperator.EQUAL, GOLD),
                        attr("n-a", "member.tier_x", ComparisonOperator.EQUAL, GOLD)),
                Arguments.of("ATTRIBUTE operator",
                        attr("n-a", "member.tier", ComparisonOperator.EQUAL, GOLD),
                        attr("n-a", "member.tier", ComparisonOperator.NOT_EQUAL, GOLD)),
                Arguments.of("ATTRIBUTE null operator",
                        nullAttr("n-a", ComparisonOperator.IS_NULL),
                        nullAttr("n-a", ComparisonOperator.IS_NOT_NULL)),
                Arguments.of("METRIC nodeId",
                        metric("n-m", ComparisonOperator.GREATER_THAN, new IntegerValue(1L), ROLLING_30_DAYS),
                        metric("n-m-2", ComparisonOperator.GREATER_THAN, new IntegerValue(1L), ROLLING_30_DAYS)),
                Arguments.of("metricId",
                        metric("n-m", "order.count", ComparisonOperator.GREATER_THAN, new IntegerValue(1L), ROLLING_30_DAYS),
                        metric("n-m", "order.count_x", ComparisonOperator.GREATER_THAN, new IntegerValue(1L), ROLLING_30_DAYS)),
                Arguments.of("METRIC operator",
                        metric("n-m", ComparisonOperator.GREATER_THAN, new IntegerValue(1L), ROLLING_30_DAYS),
                        metric("n-m", ComparisonOperator.LESS_THAN, new IntegerValue(1L), ROLLING_30_DAYS)),
                Arguments.of("EXISTS nodeId",
                        exists("n-e", "member.orders", ROLLING_30_DAYS, gold), exists("n-e-2", "member.orders", ROLLING_30_DAYS, gold)),
                Arguments.of("relationId",
                        exists("n-e", "member.orders", ROLLING_30_DAYS, gold), exists("n-e", "member.orders_x", ROLLING_30_DAYS, gold)),
                Arguments.of("EXISTS window",
                        exists("n-e", "member.orders", ROLLING_30_DAYS, gold),
                        exists("n-e", "member.orders", new LifetimeWindow(), gold)),
                Arguments.of("EXISTS nested predicate nodeId",
                        exists("n-e", "member.orders", ROLLING_30_DAYS, attr("n-x", "order.channel", ComparisonOperator.EQUAL, GOLD)),
                        exists("n-e", "member.orders", ROLLING_30_DAYS, attr("n-y", "order.channel", ComparisonOperator.EQUAL, GOLD))),
                Arguments.of("EXISTS nested predicate type",
                        exists("n-e", "member.orders", ROLLING_30_DAYS, attr("n-x", "order.channel", ComparisonOperator.EQUAL, GOLD)),
                        exists("n-e", "member.orders", ROLLING_30_DAYS,
                                group("n-x", PredicateType.ALL, attr("n-y", "order.channel", ComparisonOperator.EQUAL, GOLD)))),

                // Every TypedValue kind and component.
                Arguments.of("STRING value",
                        attr("n-v", "member.name", ComparisonOperator.EQUAL, new StringValue("A")),
                        attr("n-v", "member.name", ComparisonOperator.EQUAL, new StringValue("B"))),
                Arguments.of("INTEGER value",
                        attr("n-v", "member.order_count", ComparisonOperator.EQUAL, new IntegerValue(1L)),
                        attr("n-v", "member.order_count", ComparisonOperator.EQUAL, new IntegerValue(2L))),
                Arguments.of("DECIMAL value",
                        attr("n-v", "member.x", ComparisonOperator.EQUAL, new DecimalValue("1000.00")),
                        attr("n-v", "member.x", ComparisonOperator.EQUAL, new DecimalValue("1000.01"))),
                Arguments.of("DECIMAL lexical scale",
                        attr("n-v", "member.x", ComparisonOperator.EQUAL, new DecimalValue("1000.00")),
                        attr("n-v", "member.x", ComparisonOperator.EQUAL, new DecimalValue("1000.0"))),
                Arguments.of("BOOLEAN value",
                        attr("n-v", "member.flag", ComparisonOperator.EQUAL, new BooleanValue(true)),
                        attr("n-v", "member.flag", ComparisonOperator.EQUAL, new BooleanValue(false))),
                Arguments.of("DATE value",
                        attr("n-v", "member.joined_on", ComparisonOperator.EQUAL, new DateValue("2026-01-01")),
                        attr("n-v", "member.joined_on", ComparisonOperator.EQUAL, new DateValue("2026-01-02"))),
                Arguments.of("DATETIME value",
                        attr("n-v", "member.last_seen", ComparisonOperator.EQUAL,
                                new DateTimeValue("2026-01-01T00:00:00Z")),
                        attr("n-v", "member.last_seen", ComparisonOperator.EQUAL,
                                new DateTimeValue("2026-01-01T00:00:01Z"))),
                Arguments.of("ENUM value",
                        attr("n-v", "member.tier", ComparisonOperator.EQUAL, new EnumValue("GOLD")),
                        attr("n-v", "member.tier", ComparisonOperator.EQUAL, new EnumValue("SILVER"))),
                Arguments.of("MONEY amount",
                        attr("n-v", "order.amount", ComparisonOperator.EQUAL, new MoneyValue("1000.00", "CNY")),
                        attr("n-v", "order.amount", ComparisonOperator.EQUAL, new MoneyValue("1001.00", "CNY"))),
                Arguments.of("MONEY currency",
                        attr("n-v", "order.amount", ComparisonOperator.EQUAL, new MoneyValue("1000.00", "CNY")),
                        attr("n-v", "order.amount", ComparisonOperator.EQUAL, new MoneyValue("1000.00", "USD"))),
                Arguments.of("TypedValue kind",
                        attr("n-v", "member.order_count", ComparisonOperator.EQUAL, new IntegerValue(1L)),
                        attr("n-v", "member.order_count", ComparisonOperator.EQUAL, new DecimalValue("1"))),
                Arguments.of("LIST item content",
                        attr("n-v", "member.tier", ComparisonOperator.IN,
                                new ListValue(List.of(new EnumValue("GOLD"), new EnumValue("SILVER")))),
                        attr("n-v", "member.tier", ComparisonOperator.IN,
                                new ListValue(List.of(new EnumValue("GOLD"), new EnumValue("BRONZE"))))),
                Arguments.of("LIST item order",
                        attr("n-v", "member.tier", ComparisonOperator.IN,
                                new ListValue(List.of(new EnumValue("GOLD"), new EnumValue("SILVER")))),
                        attr("n-v", "member.tier", ComparisonOperator.IN,
                                new ListValue(List.of(new EnumValue("SILVER"), new EnumValue("GOLD"))))),
                Arguments.of("RANGE lower",
                        attr("n-v", "member.order_count", ComparisonOperator.BETWEEN,
                                new RangeValue(new IntegerValue(1L), new IntegerValue(9L), true, false)),
                        attr("n-v", "member.order_count", ComparisonOperator.BETWEEN,
                                new RangeValue(new IntegerValue(2L), new IntegerValue(9L), true, false))),
                Arguments.of("RANGE upper",
                        attr("n-v", "member.order_count", ComparisonOperator.BETWEEN,
                                new RangeValue(new IntegerValue(1L), new IntegerValue(9L), true, false)),
                        attr("n-v", "member.order_count", ComparisonOperator.BETWEEN,
                                new RangeValue(new IntegerValue(1L), new IntegerValue(8L), true, false))),
                Arguments.of("RANGE includeLower",
                        attr("n-v", "member.order_count", ComparisonOperator.BETWEEN,
                                new RangeValue(new IntegerValue(1L), new IntegerValue(9L), true, false)),
                        attr("n-v", "member.order_count", ComparisonOperator.BETWEEN,
                                new RangeValue(new IntegerValue(1L), new IntegerValue(9L), false, false))),
                Arguments.of("RANGE includeUpper",
                        attr("n-v", "member.order_count", ComparisonOperator.BETWEEN,
                                new RangeValue(new IntegerValue(1L), new IntegerValue(9L), true, false)),
                        attr("n-v", "member.order_count", ComparisonOperator.BETWEEN,
                                new RangeValue(new IntegerValue(1L), new IntegerValue(9L), true, true))),

                // Every TimeWindow type and component.
                Arguments.of("ROLLING amount",
                        metric("n-m", ComparisonOperator.GREATER_THAN, new IntegerValue(1L), new RollingWindow(30, WindowUnit.DAY)),
                        metric("n-m", ComparisonOperator.GREATER_THAN, new IntegerValue(1L), new RollingWindow(7, WindowUnit.DAY))),
                Arguments.of("ROLLING unit",
                        metric("n-m", ComparisonOperator.GREATER_THAN, new IntegerValue(1L), new RollingWindow(30, WindowUnit.DAY)),
                        metric("n-m", ComparisonOperator.GREATER_THAN, new IntegerValue(1L), new RollingWindow(30, WindowUnit.WEEK))),
                Arguments.of("window type ROLLING/LIFETIME",
                        metric("n-m", ComparisonOperator.GREATER_THAN, new IntegerValue(1L), new RollingWindow(30, WindowUnit.DAY)),
                        metric("n-m", ComparisonOperator.GREATER_THAN, new IntegerValue(1L), new LifetimeWindow())),
                Arguments.of("FIXED start",
                        metric("n-m", ComparisonOperator.GREATER_THAN, new IntegerValue(1L),
                                new FixedWindow("2026-01-01T00:00:00Z", "2026-02-01T00:00:00Z")),
                        metric("n-m", ComparisonOperator.GREATER_THAN, new IntegerValue(1L),
                                new FixedWindow("2026-01-02T00:00:00Z", "2026-02-01T00:00:00Z"))),
                Arguments.of("FIXED end",
                        metric("n-m", ComparisonOperator.GREATER_THAN, new IntegerValue(1L),
                                new FixedWindow("2026-01-01T00:00:00Z", "2026-02-01T00:00:00Z")),
                        metric("n-m", ComparisonOperator.GREATER_THAN, new IntegerValue(1L),
                                new FixedWindow("2026-01-01T00:00:00Z", "2026-02-02T00:00:00Z"))),
                Arguments.of("window type LIFETIME/FIXED",
                        metric("n-m", ComparisonOperator.GREATER_THAN, new IntegerValue(1L), new LifetimeWindow()),
                        metric("n-m", ComparisonOperator.GREATER_THAN, new IntegerValue(1L),
                                new FixedWindow("2026-01-01T00:00:00Z", "2026-02-01T00:00:00Z"))),
                Arguments.of("EXISTS window vs METRIC window",
                        exists("n-w", "member.orders", ROLLING_30_DAYS, gold),
                        exists("n-w", "member.orders", new LifetimeWindow(), gold)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("editablePredicateMutations")
    void everyEditableComponentChangesTheHash(String label, Predicate baseline, Predicate mutated) {
        ContentHash baselineHash = SegmentDslFixtures.publish(baseline).editableDslHash();
        ContentHash mutatedHash = SegmentDslFixtures.publish(mutated).editableDslHash();
        assertThat(mutatedHash).as("hash must change for %s", label).isNotEqualTo(baselineHash);
    }

    @Test
    void everyEditableRootAndBindingPinChangesTheHash() {
        Predicate predicate = attr("n-a", "member.tier", ComparisonOperator.EQUAL, GOLD);
        MetadataBinding standard = SegmentDslFixtures.binding("metadata-17", "1");
        ContentHash baseline = SegmentDslFixtures.publish(standard, predicate).editableDslHash();

        assertThat(SegmentDslFixtures.publish(SubjectKind.ORDER, predicate).editableDslHash())
                .as("root subject").isNotEqualTo(baseline);
        assertThat(SegmentDslFixtures.publish(SegmentDslFixtures.binding("metadata-18", "1"), predicate).editableDslHash())
                .as("metadataSnapshotId pin").isNotEqualTo(baseline);
        assertThat(SegmentDslFixtures.publish(SegmentDslFixtures.binding("metadata-17", "2"), predicate).editableDslHash())
                .as("metadataSnapshotVersion pin").isNotEqualTo(baseline);
        assertThat(SegmentDslFixtures.publish(
                        SegmentDslFixtures.binding("metadata-17", "1", SegmentDslFixtures.alternateMetadataContentHash()),
                        predicate).editableDslHash())
                .as("metadataContentHash pin").isNotEqualTo(baseline);
    }

    @Test
    void schemaAndMetadataSchemaVersionsAreConstantsInsideTheHashedWireProjection() {
        Predicate predicate = attr("n-a", "member.tier", ComparisonOperator.EQUAL, GOLD);
        SegmentDsl document = SegmentDslFixtures.publish(predicate);

        assertThat(document.schemaVersion()).isEqualTo(SegmentDslSchemaVersions.SUPPORTED);
        assertThat(document.metadata().metadataSchemaVersion()).isEqualTo(MetadataSchemaVersions.SUPPORTED);

        // write() emits exactly the hash material plus editableDslHash, so a field present in the canonical
        // wire text is a field the editable hash covers.
        String wire = CODEC.writeString(document);
        assertThat(wire)
                .contains("\"schemaVersion\":\"1.3\"")
                .contains("\"metadataSchemaVersion\":\"1.0\"")
                .contains("\"editableDslHash\"");

        // No published document can substitute another version, so there is no alternate valid document to
        // hash: the constants are pinned by construction.
        assertFailsWithCode(
                () -> SegmentDsl.publish(new SchemaVersion(1, 2), SubjectKind.MEMBER,
                        SegmentDslFixtures.binding(), predicate),
                SegmentDslErrorCodes.SCHEMA_VERSION_UNSUPPORTED);
        assertFailsWithCode(
                () -> new MetadataBinding("metadata-17", "1", new SchemaVersion(2, 0),
                        SegmentDslFixtures.binding().contentHash()),
                SegmentDslErrorCodes.METADATA_BINDING_INVALID);
    }
}