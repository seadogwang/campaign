package com.company.campaign.builder.segment;

import com.company.campaign.builder.segment.SegmentModelRequest.EventSelectorProjection;
import com.company.campaign.builder.segment.SegmentModelRequest.MetadataProjection;
import com.company.campaign.builder.segment.testsupport.EventSelectorProjectionFixtures;
import com.company.campaign.metadata.domain.MetadataSnapshot;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The transitive Event Selector authorization projection: one selector reaches the model only when the exact
 * definition-ID set admitted by {@code metadata.discover} contains its complete five-class dependency closure —
 * relation, identity Attribute, ordering Attributes, fixed-filter Attribute keys and scope-filter Attributes.
 * Any denied, absent or stale dependency omits the whole selector without disclosing its identity, binding
 * shape or remaining permitted dependencies.
 */
class SegmentModelRequestEventSelectorAuthorizationTest {

    private final SegmentModelRequestFactory factory = new SegmentModelRequestFactory();

    @Test
    void aSelectorWhoseRelationAndReferencedAttributesAreAuthorizedIsProjected() {
        MetadataSnapshot snapshot = EventSelectorProjectionFixtures.singleSelectorSnapshot();

        SegmentModelRequest request = build(snapshot, EventSelectorProjectionFixtures.firstOrderDependencies());

        assertThat(request.eventSelectors())
                .extracting(EventSelectorProjection::stableId)
                .containsExactly(EventSelectorProjectionFixtures.FIRST_ORDER_SELECTOR);
    }

    @Test
    void denyingTheBindingRelationOmitsTheSelector() {
        MetadataSnapshot snapshot = EventSelectorProjectionFixtures.singleSelectorSnapshot();

        SegmentModelRequest request = build(
                snapshot, without(EventSelectorProjectionFixtures.firstOrderDependencies(), "member.orders"));

        assertThat(request.eventSelectors()).isEmpty();
    }

    @Test
    void denyingAnOrderingAttributeOmitsTheSelector() {
        MetadataSnapshot snapshot = EventSelectorProjectionFixtures.singleSelectorSnapshot();

        SegmentModelRequest request = build(
                snapshot,
                without(EventSelectorProjectionFixtures.firstOrderDependencies(),
                        EventSelectorProjectionFixtures.ORDER_PAID_AT));

        assertThat(request.eventSelectors()).isEmpty();
    }

    @Test
    void denyingAScopeFilterAttributeOmitsTheSelector() {
        MetadataSnapshot snapshot = EventSelectorProjectionFixtures.singleSelectorSnapshot();

        SegmentModelRequest request = build(
                snapshot,
                without(EventSelectorProjectionFixtures.firstOrderDependencies(),
                        EventSelectorProjectionFixtures.ORDER_CHANNEL));

        assertThat(request.eventSelectors()).isEmpty();
    }

    @Test
    void mixedSelectorsDiscloseOnlyTheFullyAuthorizedSelector() {
        MetadataSnapshot snapshot = EventSelectorProjectionFixtures.twoSelectorSnapshot();
        Set<String> authorized = EventSelectorProjectionFixtures.firstOrderDependencies();

        SegmentModelRequest request = build(snapshot, authorized);

        // Both selectors share the relation and the ordering Attributes, so the omission is transitive and
        // not an all-or-nothing decision over the whole catalog.
        assertThat(authorized)
                .contains("member.orders", EventSelectorProjectionFixtures.ORDER_PAID_AT, "order.id")
                .doesNotContain(EventSelectorProjectionFixtures.ORDER_STORE_CODE);
        assertThat(request.eventSelectors())
                .extracting(EventSelectorProjection::stableId)
                .containsExactly(EventSelectorProjectionFixtures.FIRST_ORDER_SELECTOR);
        assertThat(request.eventSelectors().toString())
                .doesNotContain(EventSelectorProjectionFixtures.LATEST_ORDER_SELECTOR, "Latest Order", "最近一单");
    }

    @Test
    void theCompleteFiveClassDependencyClosureIsProjected() {
        MetadataSnapshot snapshot = EventSelectorProjectionFixtures.closureSelectorSnapshot();

        SegmentModelRequest request = build(snapshot, EventSelectorProjectionFixtures.closureSelectorDependencies());

        // The closure is exactly the five dependency classes and nothing else, in deterministic natural order.
        assertThat(EventSelectorDependencyClosure.definitionIds(EventSelectorProjectionFixtures.closureSelector()))
                .containsExactly(
                        "member.orders",
                        EventSelectorProjectionFixtures.ORDER_CHANNEL,
                        EventSelectorProjectionFixtures.ORDER_IDENTITY_KEY,
                        EventSelectorProjectionFixtures.ORDER_PAID_AT,
                        EventSelectorProjectionFixtures.ORDER_STATUS);
        assertThat(request.eventSelectors())
                .extracting(EventSelectorProjection::stableId)
                .containsExactly(EventSelectorProjectionFixtures.CLOSURE_SELECTOR);
    }

    @Test
    void denyingTheIdentityAttributeOmitsTheSelectorEvenWhenItIsNotAnOrderingAttribute() {
        MetadataSnapshot snapshot = EventSelectorProjectionFixtures.closureSelectorSnapshot();
        Set<String> authorized = without(
                EventSelectorProjectionFixtures.closureSelectorDependencies(),
                EventSelectorProjectionFixtures.ORDER_IDENTITY_KEY);

        // The ordering Attribute is still admitted, so the omission can only come from the identity dependency.
        assertThat(authorized)
                .contains("member.orders", EventSelectorProjectionFixtures.ORDER_PAID_AT,
                        EventSelectorProjectionFixtures.ORDER_STATUS, EventSelectorProjectionFixtures.ORDER_CHANNEL);

        SegmentModelRequest request = build(snapshot, authorized);

        assertThat(request.eventSelectors()).isEmpty();
    }

    @Test
    void denyingAFixedFilterAttributeUsedNowhereElseOmitsTheSelector() {
        MetadataSnapshot snapshot = EventSelectorProjectionFixtures.closureSelectorSnapshot();
        Set<String> authorized = without(
                EventSelectorProjectionFixtures.closureSelectorDependencies(),
                EventSelectorProjectionFixtures.ORDER_STATUS);

        // order.status appears in no ordering, scope-filter or identity position, so only the fixed-filter key
        // check can deny this selector.
        assertThat(authorized)
                .contains("member.orders", EventSelectorProjectionFixtures.ORDER_IDENTITY_KEY,
                        EventSelectorProjectionFixtures.ORDER_PAID_AT, EventSelectorProjectionFixtures.ORDER_CHANNEL);

        SegmentModelRequest request = build(snapshot, authorized);

        assertThat(request.eventSelectors()).isEmpty();
        assertThat(request.eventSelectors().toString())
                .doesNotContain(EventSelectorProjectionFixtures.CLOSURE_SELECTOR, "Completed Order", "已完成订单");
    }

    @Test
    void filteringIsDeterministicAndKeepsTheSnapshotPinAndHash() {
        MetadataSnapshot snapshot = EventSelectorProjectionFixtures.twoSelectorSnapshot();

        SegmentModelRequest first = build(snapshot, EventSelectorProjectionFixtures.firstOrderDependencies());
        SegmentModelRequest second = build(snapshot, EventSelectorProjectionFixtures.firstOrderDependencies());
        SegmentModelRequest wholeSnapshot = factory.build(
                "首单", "zh-CN", snapshot, null, null, null, null, null);

        assertThat(second.eventSelectors()).isEqualTo(first.eventSelectors());
        assertThat(first.metadataSnapshot()).isEqualTo(second.metadataSnapshot());
        assertThat(first.metadataSnapshot().contentHash()).isEqualTo(snapshot.contentHash());
        assertThat(first.metadataSnapshot().snapshotId()).isEqualTo(snapshot.snapshotId());
        assertThat(first.metadataSnapshot().snapshotVersion()).isEqualTo(snapshot.snapshotVersion());
        // The diagnostic whole-snapshot behaviour is untouched and still publishes every selector, which proves
        // the authorized-ID filtering never rewrites the pinned snapshot itself.
        assertThat(wholeSnapshot.eventSelectors())
                .extracting(EventSelectorProjection::stableId)
                .containsExactlyInAnyOrder(EventSelectorProjectionFixtures.FIRST_ORDER_SELECTOR,
                        EventSelectorProjectionFixtures.LATEST_ORDER_SELECTOR);
        assertThat(wholeSnapshot.metadataSnapshot().contentHash()).isEqualTo(snapshot.contentHash());
    }

    @Test
    void anAbsentAuthorizedSetKeepsTheDiagnosticWholeSnapshotBehaviour() {
        MetadataSnapshot snapshot = EventSelectorProjectionFixtures.singleSelectorSnapshot();

        SegmentModelRequest request = factory.build("首单", "zh-CN", snapshot, null, null, null, null, null);

        assertThat(request.eventSelectors())
                .extracting(EventSelectorProjection::stableId)
                .containsExactly(EventSelectorProjectionFixtures.FIRST_ORDER_SELECTOR);
    }

    private SegmentModelRequest build(MetadataSnapshot snapshot, Set<String> authorizedDefinitionIds) {
        return factory.build("首单", "zh-CN", snapshot, null, null, null, null, authorizedDefinitionIds);
    }

    private static Set<String> without(Set<String> authorizedDefinitionIds, String deniedDefinitionId) {
        Set<String> remaining = new LinkedHashSet<>(authorizedDefinitionIds);
        remaining.remove(deniedDefinitionId);
        return remaining;
    }

    /** Guards the bounded projection: the omitted selector is never replaced by a synthetic projection. */
    @Test
    void anAuthorizedProjectionStillCarriesThePermittedDefinitions() {
        MetadataSnapshot snapshot = EventSelectorProjectionFixtures.singleSelectorSnapshot();

        SegmentModelRequest request = build(snapshot, EventSelectorProjectionFixtures.firstOrderDependencies());

        assertThat(request.permittedDefinitions())
                .extracting(MetadataProjection::stableId)
                .contains("member.orders", EventSelectorProjectionFixtures.ORDER_PAID_AT);
        assertThat(request.eventSelectors()).hasSize(1);
    }
}