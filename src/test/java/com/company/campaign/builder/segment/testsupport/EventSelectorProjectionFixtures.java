package com.company.campaign.builder.segment.testsupport;

import com.company.campaign.metadata.domain.AttributeDefinition;
import com.company.campaign.metadata.domain.ComparisonOperator;
import com.company.campaign.metadata.domain.CurrencySemantics;
import com.company.campaign.metadata.domain.EventSelectorBinding;
import com.company.campaign.metadata.domain.EventSelectorDefinition;
import com.company.campaign.metadata.domain.EventSelectorKind;
import com.company.campaign.metadata.domain.EventSelectorOrder;
import com.company.campaign.metadata.domain.MetadataFilter;
import com.company.campaign.metadata.domain.MetadataFilterEntry;
import com.company.campaign.metadata.domain.MetadataSnapshot;
import com.company.campaign.metadata.domain.MetricFilterDimension;
import com.company.campaign.metadata.domain.NullSemantics;
import com.company.campaign.metadata.domain.SemanticDataType;
import com.company.campaign.metadata.domain.SortDirection;
import com.company.campaign.metadata.domain.SubjectKind;
import com.company.campaign.metadata.testsupport.MetadataFixtures;

import java.util.List;
import java.util.Set;

/**
 * Deterministic fixtures for the transitive Event Selector authorization projection.
 *
 * <p>Two governed selectors share one relation and are distinguishable only by one referenced Attribute, so a
 * test can prove the whole selector is omitted as soon as any of its relation, ordering or scope-filter
 * dependencies is not admitted by discovery. A third selector isolates the two dependency classes that are not
 * shared with any other class: its identity Attribute is deliberately not an ordering Attribute, and its
 * non-empty fixed filter references an Attribute that appears nowhere else in the snapshot. The snapshot is
 * published through the accepted T04 contract, so its canonical pin and hashes are the real ones and never
 * hand-written.
 */
public final class EventSelectorProjectionFixtures {

    /** The first-order selector and its exact dependency definition IDs. */
    public static final String FIRST_ORDER_SELECTOR = "order.first_positive";

    /** The second selector, whose only distinct dependency is the {@code order.store_code} Attribute. */
    public static final String LATEST_ORDER_SELECTOR = "order.latest_positive";

    /** The Attribute ID that is a scope-filter dependency of the first selector only. */
    public static final String ORDER_CHANNEL = "order.channel";

    /** The Attribute ID that is a scope-filter dependency of the second selector only. */
    public static final String ORDER_STORE_CODE = "order.store_code";

    /** The ordering Attribute ID shared by both selectors. */
    public static final String ORDER_PAID_AT = "order.paid_at";

    /** The selector whose identity and fixed-filter dependencies are independent of every other class. */
    public static final String CLOSURE_SELECTOR = "order.completed_positive";

    /** The identity Attribute ID of {@link #CLOSURE_SELECTOR}, deliberately absent from its ordering. */
    public static final String ORDER_IDENTITY_KEY = "order.identity_key";

    /** The Attribute ID referenced only by the non-empty fixed filter of {@link #CLOSURE_SELECTOR}. */
    public static final String ORDER_STATUS = "order.status";

    private EventSelectorProjectionFixtures() {
    }

    /** The exact definition IDs {@link #firstOrderSelector()} needs to be described and executed. */
    public static Set<String> firstOrderDependencies() {
        return Set.of(MetadataFixtures.ordersRelation().id(), ORDER_PAID_AT, "order.id", ORDER_CHANNEL);
    }

    /** The exact definition IDs {@link #latestOrderSelector()} needs to be described and executed. */
    public static Set<String> latestOrderDependencies() {
        return Set.of(MetadataFixtures.ordersRelation().id(), ORDER_PAID_AT, "order.id", ORDER_STORE_CODE);
    }

    /**
     * The complete five-class dependency closure of {@link #closureSelector()}: the binding relation, the
     * identity Attribute, the ordering Attribute, the fixed-filter key and the scope-filter Attribute.
     */
    public static Set<String> closureSelectorDependencies() {
        return Set.of(MetadataFixtures.ordersRelation().id(), ORDER_IDENTITY_KEY, ORDER_PAID_AT, ORDER_STATUS,
                ORDER_CHANNEL);
    }

    /** A snapshot holding exactly one governed Event Selector. */
    public static MetadataSnapshot singleSelectorSnapshot() {
        return snapshot(List.of(firstOrderSelector()));
    }

    /** A snapshot holding two governed Event Selectors with an identical relation and ordering shape. */
    public static MetadataSnapshot twoSelectorSnapshot() {
        return snapshot(List.of(firstOrderSelector(), latestOrderSelector()));
    }

    /** A snapshot holding exactly the selector whose identity and fixed-filter dependencies are independent. */
    public static MetadataSnapshot closureSelectorSnapshot() {
        return snapshot(List.of(closureSelector()));
    }

    /** The first-order selector: every dependency class it declares is published (its fixed filter is empty). */
    public static EventSelectorDefinition firstOrderSelector() {
        return sharedShapeSelector(FIRST_ORDER_SELECTOR, "First Order", "首单", ORDER_CHANNEL);
    }

    /** The second selector, scoped by a different Attribute so only its own admission can keep it. */
    public static EventSelectorDefinition latestOrderSelector() {
        return sharedShapeSelector(LATEST_ORDER_SELECTOR, "Latest Order", "最近一单", ORDER_STORE_CODE);
    }

    /**
     * A selector whose identity Attribute ({@code order.identity_key}) is not one of its ordering Attributes and
     * whose non-empty fixed filter references {@code order.status}, an Attribute used by no other class. Denying
     * either of those two dependencies can therefore only be detected by the complete five-class closure.
     */
    public static EventSelectorDefinition closureSelector() {
        return selector(
                CLOSURE_SELECTOR,
                "Completed Order",
                "已完成订单",
                ORDER_IDENTITY_KEY,
                List.of(new EventSelectorOrder(ORDER_PAID_AT, SortDirection.ASC)),
                new MetadataFilter(List.of(new MetadataFilterEntry(ORDER_STATUS, List.of("COMPLETED")))),
                ORDER_CHANNEL);
    }

    private static EventSelectorDefinition sharedShapeSelector(
            String id, String displayName, String alias, String scopeFilterAttributeId) {
        return selector(
                id,
                displayName,
                alias,
                "order.id",
                List.of(
                        new EventSelectorOrder(ORDER_PAID_AT, SortDirection.ASC),
                        new EventSelectorOrder("order.id", SortDirection.ASC)),
                MetadataFilter.empty(),
                scopeFilterAttributeId);
    }

    private static EventSelectorDefinition selector(
            String id,
            String displayName,
            String alias,
            String identityAttributeId,
            List<EventSelectorOrder> orderBy,
            MetadataFilter fixedFilter,
            String scopeFilterAttributeId) {
        return new EventSelectorDefinition(
                id,
                displayName,
                List.of(alias),
                SubjectKind.MEMBER,
                EventSelectorKind.FIRST,
                true,
                List.of(new EventSelectorBinding(
                        MetadataFixtures.ordersRelation().id(),
                        identityAttributeId,
                        orderBy,
                        fixedFilter,
                        List.of(new MetricFilterDimension(
                                scopeFilterAttributeId,
                                List.of(ComparisonOperator.EQUAL, ComparisonOperator.IN))))),
                null);
    }

    private static MetadataSnapshot snapshot(List<EventSelectorDefinition> selectors) {
        return MetadataSnapshot.publish(
                MetadataFixtures.schemaVersion(),
                "metadata-17",
                "1",
                MetadataFixtures.TENANT,
                MetadataFixtures.PROGRAM,
                List.of(
                        MetadataFixtures.tierAttribute(),
                        SliceTestFixtures.memberCodeAttribute(),
                        orderPaidAtAttribute(),
                        orderAttribute("order.id", "Order Id", SemanticDataType.STRING),
                        orderAttribute(ORDER_CHANNEL, "Order Channel", SemanticDataType.STRING),
                        orderAttribute(ORDER_STORE_CODE, "Order Store Code", SemanticDataType.STRING),
                        orderAttribute(ORDER_IDENTITY_KEY, "Order Identity Key", SemanticDataType.STRING),
                        orderAttribute(ORDER_STATUS, "Order Status", SemanticDataType.STRING)),
                List.of(SliceTestFixtures.netPaidAmountMetric(), SliceTestFixtures.orderCountMetric()),
                List.of(MetadataFixtures.ordersRelation()),
                selectors);
    }

    private static AttributeDefinition orderPaidAtAttribute() {
        return orderAttribute(ORDER_PAID_AT, "Order Paid At", SemanticDataType.TIMESTAMP);
    }

    private static AttributeDefinition orderAttribute(String id, String displayName, SemanticDataType dataType) {
        return new AttributeDefinition(
                id,
                MetadataFixtures.orderVersion(),
                SubjectKind.ORDER,
                displayName,
                dataType,
                dataType == SemanticDataType.STRING ? MetadataFixtures.stringSemantics() : null,
                List.of(),
                List.of(ComparisonOperator.EQUAL, ComparisonOperator.NOT_EQUAL,
                        ComparisonOperator.IN, ComparisonOperator.NOT_IN,
                        ComparisonOperator.IS_NULL, ComparisonOperator.IS_NOT_NULL),
                List.of(),
                NullSemantics.UNKNOWN_VALUE,
                CurrencySemantics.NOT_APPLICABLE,
                List.of(),
                List.of(),
                null);
    }
}