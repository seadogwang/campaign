package com.company.campaign.segment.dsl.testsupport;

import com.company.campaign.contracts.SchemaVersion;
import com.company.campaign.metadata.MetadataSchemaVersions;
import com.company.campaign.metadata.domain.ComparisonOperator;
import com.company.campaign.metadata.domain.MetadataSnapshot;
import com.company.campaign.metadata.domain.SubjectKind;
import com.company.campaign.metadata.testsupport.MetadataFixtures;
import com.company.campaign.segment.dsl.contract.MetadataBinding;
import com.company.campaign.segment.dsl.contract.SegmentDsl;
import com.company.campaign.segment.dsl.contract.SegmentDslSchemaVersions;
import com.company.campaign.segment.dsl.predicate.AttributePredicate;
import com.company.campaign.segment.dsl.predicate.ConditionValue;
import com.company.campaign.segment.dsl.predicate.ExistsPredicate;
import com.company.campaign.segment.dsl.predicate.GroupPredicate;
import com.company.campaign.segment.dsl.predicate.MetricPredicate;
import com.company.campaign.segment.dsl.predicate.NotPredicate;
import com.company.campaign.segment.dsl.predicate.Predicate;
import com.company.campaign.segment.dsl.predicate.PredicateType;
import com.company.campaign.segment.dsl.value.BooleanValue;
import com.company.campaign.segment.dsl.value.DateValue;
import com.company.campaign.segment.dsl.value.DateTimeValue;
import com.company.campaign.segment.dsl.value.DecimalValue;
import com.company.campaign.segment.dsl.value.EnumValue;
import com.company.campaign.segment.dsl.value.IntegerValue;
import com.company.campaign.segment.dsl.value.ListValue;
import com.company.campaign.segment.dsl.value.MoneyValue;
import com.company.campaign.segment.dsl.value.RangeValue;
import com.company.campaign.segment.dsl.value.ScalarValue;
import com.company.campaign.segment.dsl.value.StringValue;
import com.company.campaign.segment.dsl.value.TypedValue;
import com.company.campaign.segment.dsl.window.FixedWindow;
import com.company.campaign.segment.dsl.window.LifetimeWindow;
import com.company.campaign.segment.dsl.window.RollingWindow;
import com.company.campaign.segment.dsl.window.TimeWindow;
import com.company.campaign.segment.dsl.window.WindowUnit;
import com.company.campaign.shared.canonical.ContentHash;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Deterministic T05 fixtures. No clock, network, database or random identifier is used. */
public final class SegmentDslFixtures {

    public static final SchemaVersion DSL_VERSION = SegmentDslSchemaVersions.SUPPORTED;

    /** A well-formed but intentionally wrong declared hash, used to reach non-hash failures first. */
    public static final String PLACEHOLDER_HASH = "sha256:" + "0".repeat(64);

    private SegmentDslFixtures() {
    }

    public static MetadataSnapshot snapshot() {
        return MetadataFixtures.standardSnapshot();
    }

    public static MetadataBinding binding() {
        return MetadataBinding.fromSnapshot(snapshot());
    }

    public static MetadataBinding binding(String snapshotId) {
        return new MetadataBinding(
                snapshotId, "1", MetadataSchemaVersions.SUPPORTED, snapshot().contentHash());
    }

    public static MetadataBinding binding(String snapshotId, String snapshotVersion) {
        return binding(snapshotId, snapshotVersion, snapshot().contentHash());
    }

    public static MetadataBinding binding(String snapshotId, String snapshotVersion, ContentHash contentHash) {
        return new MetadataBinding(snapshotId, snapshotVersion, MetadataSchemaVersions.SUPPORTED, contentHash);
    }

    /** A deterministic, valid content hash that differs from the standard snapshot's. */
    public static ContentHash alternateMetadataContentHash() {
        return ContentHash.sha256("segment-dsl-fixture-alternate".getBytes(StandardCharsets.UTF_8));
    }

    public static AttributePredicate tierEqualsGold(String nodeId) {
        return new AttributePredicate(
                nodeId, "member.tier", ComparisonOperator.EQUAL, ConditionValue.of(new EnumValue("GOLD")));
    }

    /** Every predicate, TypedValue and TimeWindow subtype appears in this one document. */
    public static Predicate richPredicate() {
        List<Predicate> children = new ArrayList<>();
        children.add(tierEqualsGold("n-tier"));
        children.add(new AttributePredicate(
                "n-tier-in", "member.tier", ComparisonOperator.IN,
                ConditionValue.of(new ListValue(List.of(new EnumValue("GOLD"), new EnumValue("SILVER"))))));
        children.add(new AttributePredicate(
                "n-tier-null", "member.tier", ComparisonOperator.IS_NULL, ConditionValue.none()));
        children.add(new AttributePredicate(
                "n-name", "member.name", ComparisonOperator.CONTAINS, ConditionValue.of(new StringValue("GO"))));
        children.add(new AttributePredicate(
                "n-empty", "member.name", ComparisonOperator.EQUAL, ConditionValue.of(new StringValue(""))));
        children.add(new MetricPredicate(
                "n-spend", "order.net_paid_amount", new RollingWindow(90, WindowUnit.DAY),
                ComparisonOperator.GREATER_THAN, ConditionValue.of(new MoneyValue("1000.00", "CNY"))));
        children.add(new MetricPredicate(
                "n-orders", "order.count", new FixedWindow(
                        "2026-01-01T00:00:00+08:00", "2026-02-01T00:00:00+08:00"),
                ComparisonOperator.GREATER_OR_EQUAL, ConditionValue.of(new IntegerValue(3L))));
        children.add(new MetricPredicate(
                "n-lifetime", "order.avg_amount", new LifetimeWindow(),
                ComparisonOperator.LESS_THAN, ConditionValue.of(new DecimalValue("500.5"))));
        children.add(new NotPredicate(
                "n-not", new AttributePredicate(
                        "n-flag", "member.flag", ComparisonOperator.NOT_EQUAL,
                        ConditionValue.of(new BooleanValue(true)))));
        children.add(new GroupPredicate(
                "n-any", PredicateType.ANY,
                List.of(
                        new AttributePredicate(
                                "n-count", "member.order_count", ComparisonOperator.BETWEEN,
                                ConditionValue.of(new RangeValue(
                                        new IntegerValue(1L), new IntegerValue(9L), true, false))),
                        new AttributePredicate(
                                "n-joined", "member.joined_on", ComparisonOperator.GREATER_THAN,
                                ConditionValue.of(new DateValue("2026-01-01"))))));
        children.add(new AttributePredicate(
                "n-when", "member.last_seen", ComparisonOperator.EQUAL,
                ConditionValue.of(new DateTimeValue("2026-01-01T00:00:00+08:00"))));
        children.add(new ExistsPredicate(
                "n-order-1", "member.orders", new RollingWindow(30, WindowUnit.DAY),
                new GroupPredicate(
                        "n-order-all", PredicateType.ALL,
                        List.of(
                                new AttributePredicate(
                                        "n-amount", "order.amount", ComparisonOperator.GREATER_THAN,
                                        ConditionValue.of(new MoneyValue("100.00", "CNY"))),
                                new AttributePredicate(
                                        "n-channel", "order.channel", ComparisonOperator.EQUAL,
                                        ConditionValue.of(new EnumValue("ONLINE")))))));
        children.add(new ExistsPredicate(
                "n-order-2", "member.orders", new LifetimeWindow(),
                new AttributePredicate(
                        "n-channel-2", "order.channel", ComparisonOperator.EQUAL,
                        ConditionValue.of(new EnumValue("STORE")))));
        return new GroupPredicate("n-root", PredicateType.ALL, children);
    }

    public static SegmentDsl document() {
        return publish(richPredicate());
    }

    public static SegmentDsl publish(Predicate predicate) {
        return SegmentDsl.publish(DSL_VERSION, SubjectKind.MEMBER, binding(), predicate);
    }

    public static SegmentDsl publish(SubjectKind subject, Predicate predicate) {
        return SegmentDsl.publish(DSL_VERSION, subject, binding(), predicate);
    }

    public static SegmentDsl publish(MetadataBinding binding, Predicate predicate) {
        return SegmentDsl.publish(DSL_VERSION, SubjectKind.MEMBER, binding, predicate);
    }

    /** Every TypedValue subtype, one instance each. */
    public static List<TypedValue> everyValue() {
        return List.of(
                new StringValue("SG"),
                new IntegerValue(3L),
                new DecimalValue("1000.00"),
                new BooleanValue(true),
                new DateValue("2026-01-01"),
                new DateTimeValue("2026-01-01T00:00:00+08:00"),
                new EnumValue("GOLD"),
                new MoneyValue("1000.00", "CNY"),
                new ListValue(List.of(new EnumValue("GOLD"), new EnumValue("SILVER"))),
                new RangeValue(new IntegerValue(1L), new IntegerValue(9L), true, false));
    }

    /** Every TimeWindow subtype, one instance each. */
    public static List<TimeWindow> everyWindow() {
        return List.of(
                new RollingWindow(30, WindowUnit.DAY),
                new LifetimeWindow(),
                new FixedWindow("2026-01-01T00:00:00+08:00", "2026-02-01T00:00:00+08:00"));
    }

    /** A document skeleton whose Metadata binding is real and whose editable hash is a placeholder. */
    public static String documentJson(String predicateJson) {
        return "{"
                + "\"schemaVersion\":\"1.3\","
                + "\"subject\":\"MEMBER\","
                + "\"metadataSnapshotId\":\"" + binding().snapshotId() + "\","
                + "\"metadataSnapshotVersion\":\"" + binding().snapshotVersion() + "\","
                + "\"metadataSchemaVersion\":\"" + binding().metadataSchemaVersion().value() + "\","
                + "\"metadataContentHash\":\"" + binding().contentHash().value() + "\","
                + "\"predicate\":" + predicateJson + ","
                + "\"editableDslHash\":\"" + PLACEHOLDER_HASH + "\""
                + "}";
    }

    public static ScalarValue scalar(String kind, Object value) {
        return switch (kind) {
            case "STRING" -> new StringValue((String) value);
            case "INTEGER" -> new IntegerValue(((Number) value).longValue());
            case "DECIMAL" -> new DecimalValue((String) value);
            case "BOOLEAN" -> new BooleanValue((Boolean) value);
            case "DATE" -> new DateValue((String) value);
            case "DATETIME" -> new DateTimeValue((String) value);
            case "ENUM" -> new EnumValue((String) value);
            case "MONEY" -> new MoneyValue((String) value, "CNY");
            default -> throw new IllegalArgumentException("unknown scalar kind " + kind);
        };
    }
}