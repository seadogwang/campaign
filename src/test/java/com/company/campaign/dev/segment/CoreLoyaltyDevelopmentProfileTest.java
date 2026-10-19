package com.company.campaign.dev.segment;

import com.company.campaign.metadata.domain.ComparisonOperator;
import com.company.campaign.metadata.domain.AggregateFunction;
import com.company.campaign.metadata.domain.EmptySetBehavior;
import com.company.campaign.metadata.domain.EnumValueDefinition;
import com.company.campaign.metadata.domain.SemanticDataType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class CoreLoyaltyDevelopmentProfileTest {

    @Test
    void publishesOneFrozenExecutableProfile() {
        var snapshot = CoreLoyaltyDevelopmentProfile.snapshot();

        assertThat(snapshot.snapshotId()).isEqualTo("core-loyalty-metadata");
        assertThat(snapshot.snapshotVersion()).isEqualTo("17");
        assertThat(CoreLoyaltyDevelopmentProfile.PROFILE_VERSION).isEqualTo("core-loyalty-development/17");
        assertThat(snapshot.attributes()).hasSize(98);
        assertThat(snapshot.metrics()).hasSize(27);
        assertThat(snapshot.relations()).hasSize(10);
        assertThat(snapshot.attributes())
                .extracting(definition -> definition.id())
                .contains(
                        "member.current_tier",
                        "member.registered_at",
                        "member.birth_month",
                        "member.available_points",
                        "member.tier_qualification_points",
                        "member.wechat_bound",
                        "member.preferred_store_code",
                        "member.preferred_store_name",
                        "member.latest_positive_order_service_ba_code",
                        "member.latest_positive_order_service_ba_name",
                        "member.latest_positive_order_store_code",
                        "member.gender",
                        "member.consent_granted",
                        "member.first_order_at",
                        "member.next_birthday_on",
                        "tier_history.tier",
                        "tier_history.change_direction",
                        "order.channel",
                        "order.store_code",
                        "order.service_ba_code",
                        "order_item.product_name",
                        "order_item.sku_code",
                        "order_item.category_name",
                        "order_item.category_code",
                        "order_item.unit_price",
                        "order_item.discount_amount",
                        "point_entry.status",
                        "coupon_send.coupon_type_id",
                        "redemption.channel",
                        "consent.status",
                        "redemption_item.item_code",
                        "product_browse.product_name",
                        "product_browse.occurred_at",
                        "product_favorite.product_name",
                        "product_favorite.occurred_at");
        assertThat(snapshot.metrics())
                .extracting(definition -> definition.id())
                .contains(
                        "order.gross_amount_sum",
                        "order.paid_amount_sum",
                        "order.avg_paid_amount",
                        "order.positive_count",
                        "order.return_count",
                        "order.return_paid_amount_sum",
                        "order_item.quantity_sum",
                        "order_item.line_paid_amount_sum",
                        "order_item.distinct_category_count",
                        "tier_history.change_count",
                        "point.entry_count",
                        "coupon.successful_send_count",
                        "redemption.count",
                        "order.discount_amount_sum",
                        "point.delta_sum",
                        "coupon.send_count",
                        "redemption.points_spent_sum",
                        "consent.granted_count",
                        "redemption_item.quantity_sum",
                        "product.browse_count",
                        "product.favorite_count");
        var averageOrderValue = snapshot.metrics().stream()
                .filter(metric -> metric.id().equals("order.avg_paid_amount"))
                .findFirst().orElseThrow();
        assertThat(averageOrderValue.aggregation()).isEqualTo(AggregateFunction.AVG);
        assertThat(averageOrderValue.emptySet().behavior()).isEqualTo(EmptySetBehavior.NULL);
        assertThat(averageOrderValue.filter().entries())
                .singleElement()
                .satisfies(filter -> {
                    assertThat(filter.key()).isEqualTo("order.order_type");
                    assertThat(filter.values()).containsExactly("POSITIVE");
                });
        assertThat(snapshot.metrics().stream()
                .filter(metric -> metric.id().equals("order.positive_count"))
                .findFirst().orElseThrow().filter().entries())
                .singleElement()
                .satisfies(filter -> {
                    assertThat(filter.key()).isEqualTo("order.order_type");
                    assertThat(filter.values()).containsExactly("POSITIVE");
                });
        var currentTier = snapshot.attributes().stream()
                .filter(attribute -> attribute.id().equals("member.current_tier"))
                .findFirst().orElseThrow();
        assertThat(currentTier.dataType()).isEqualTo(SemanticDataType.ORDERED_ENUM);
        assertThat(currentTier.enumValues())
                .extracting(value -> value.code(), value -> value.rank())
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("STANDARD", 10),
                        org.assertj.core.groups.Tuple.tuple("SILVER", 20),
                        org.assertj.core.groups.Tuple.tuple("GOLD", 30),
                        org.assertj.core.groups.Tuple.tuple("PLATINUM", 40));
        assertThat(currentTier.allowedOperators()).contains(
                ComparisonOperator.EQUAL,
                ComparisonOperator.NOT_EQUAL,
                ComparisonOperator.IN,
                ComparisonOperator.NOT_IN,
                ComparisonOperator.GREATER_THAN,
                ComparisonOperator.GREATER_OR_EQUAL,
                ComparisonOperator.LESS_THAN,
                ComparisonOperator.LESS_OR_EQUAL,
                ComparisonOperator.BETWEEN,
                ComparisonOperator.IS_NULL,
                ComparisonOperator.IS_NOT_NULL);
        assertThat(currentTier.disambiguation())
                .contains(
                        "当前等级是有序枚举 ORDERED_ENUM，不是无序 ENUM",
                        "‘当前等级金卡以上’",
                        "‘当前等级白金卡以上’",
                        "‘当前等级银卡以下’");
        assertThat(snapshot.attributes().stream()
                .filter(attribute -> attribute.id().equals("member.latest_positive_order_store_code"))
                .findFirst().orElseThrow().nullSemantics())
                .isEqualTo(com.company.campaign.metadata.domain.NullSemantics.EXCLUDED);
        assertThat(snapshot.metrics().stream()
                .filter(metric -> metric.id().equals("order_item.quantity_sum"))
                .findFirst().orElseThrow().scopeFilterDimensions())
                .anySatisfy(filter -> {
                    assertThat(filter.attributeId()).isEqualTo("order_item.product_name");
                    assertThat(filter.allowedOperators()).contains(ComparisonOperator.EQUAL);
                });
        assertThat(snapshot.metrics().stream()
                .filter(metric -> metric.id().equals("order_item.line_paid_amount_sum"))
                .findFirst().orElseThrow().scopeFilterDimensions())
                .extracting(filter -> filter.attributeId())
                .contains(
                        "order_item.product_name",
                        "order_item.sku_code",
                        "order_item.category_name",
                        "order_item.category_code");
        assertThat(snapshot.metrics().stream()
                .filter(metric -> metric.id().equals("order_item.line_paid_amount_sum"))
                .findFirst().orElseThrow().scopeFilterDimensions())
                .filteredOn(filter -> filter.attributeId().equals("order_item.unit_price"))
                .singleElement()
                .satisfies(filter -> assertThat(filter.allowedOperators()).contains(
                        ComparisonOperator.GREATER_THAN,
                        ComparisonOperator.GREATER_OR_EQUAL,
                        ComparisonOperator.LESS_THAN,
                        ComparisonOperator.LESS_OR_EQUAL,
                        ComparisonOperator.BETWEEN));
        assertThat(snapshot.metrics().stream()
                .filter(metric -> metric.id().equals("order_item.quantity_sum"))
                .findFirst().orElseThrow().scopeFilterDimensions())
                .filteredOn(filter -> filter.attributeId().equals("order_item.discount_amount"))
                .singleElement()
                .satisfies(filter -> assertThat(filter.allowedOperators()).contains(
                        ComparisonOperator.EQUAL,
                        ComparisonOperator.GREATER_THAN));
        assertThat(snapshot.contentHash()).isNotNull();
        assertThat(CoreLoyaltyDevelopmentProfile.resolutionPolicy().contentHash()).isNotNull();
        assertThat(CoreLoyaltyDevelopmentProfile.storageCatalog().contentHash()).isNotNull();
        assertThat(CoreLoyaltyDevelopmentProfile.storageCatalog().fxStrategy()).isNull();
        assertThat(CoreLoyaltyDevelopmentProfile.storageCatalog().refundStorage()).isNull();

        var storage = CoreLoyaltyDevelopmentProfile.storageCatalog();
        Set<String> storedAttributes = storage.attributes().stream()
                .map(definition -> definition.definitionId())
                .collect(Collectors.toSet());
        Set<String> storedMetrics = storage.metrics().stream()
                .map(definition -> definition.definitionId())
                .collect(Collectors.toSet());
        Set<String> storedRelations = storage.relations().stream()
                .map(definition -> definition.relationId())
                .collect(Collectors.toSet());
        assertThat(storedAttributes).containsAll(snapshot.attributes().stream()
                .map(definition -> definition.id()).toList());
        assertThat(storedMetrics).containsAll(snapshot.metrics().stream()
                .map(definition -> definition.id()).toList());
        assertThat(storedRelations).containsAll(snapshot.relations().stream()
                .map(definition -> definition.id()).toList());
        Set<String> publishedDefinitions = new java.util.HashSet<>();
        publishedDefinitions.addAll(snapshot.attributes().stream().map(definition -> definition.id()).toList());
        publishedDefinitions.addAll(snapshot.metrics().stream().map(definition -> definition.id()).toList());
        publishedDefinitions.addAll(snapshot.relations().stream().map(definition -> definition.id()).toList());
        assertThat(publishedDefinitions)
                .containsAll(CoreLoyaltyDevelopmentProfile.resolutionPolicy().conceptDefinitionIds().values());
        assertThat(CoreLoyaltyDevelopmentProfile.resolutionPolicy().policyVersion())
                .isEqualTo("core-loyalty-resolution/17");
        assertThat(storage.catalogVersion()).isEqualTo("core-loyalty-storage-16");

        var request = CoreLoyaltyDevelopmentProfile.request(
                "金卡会员", Instant.parse("2026-10-04T00:00:00Z"));
        assertThat(request.trustedContext().snapshotRef().metadataSnapshotId())
                .isEqualTo(snapshot.snapshotId());
    }

    @Test
    void orderItemOrderChannelEnumAlignsWithCanonicalOrderChannel() {
        var snapshot = CoreLoyaltyDevelopmentProfile.snapshot();

        var orderChannel = snapshot.attributes().stream()
                .filter(attr -> attr.id().equals("order.channel"))
                .findFirst().orElseThrow();
        var orderItemChannel = snapshot.attributes().stream()
                .filter(attr -> attr.id().equals("order_item.order_channel"))
                .findFirst().orElseThrow();

        Set<String> orderChannelCodes = orderChannel.enumValues().stream()
                .map(EnumValueDefinition::code)
                .collect(Collectors.toSet());
        Set<String> orderItemChannelCodes = orderItemChannel.enumValues().stream()
                .map(EnumValueDefinition::code)
                .collect(Collectors.toSet());

        // order_item.order_channel disambiguation says "从订单头 source 继承",
        // so every order.channel enum code must be present in order_item.order_channel.
        assertThat(orderItemChannelCodes)
                .as("order_item.order_channel enum must match order.channel enum")
                .containsAll(orderChannelCodes);
    }

    @Test
    void productBrowseMetadataIsLoadedAndDistinctFromOrderMetrics() {
        var snapshot = CoreLoyaltyDevelopmentProfile.snapshot();

        var browseMetric = snapshot.metrics().stream()
                .filter(metric -> metric.id().equals("product.browse_count"))
                .findFirst().orElseThrow();
        assertThat(browseMetric.aggregation()).isEqualTo(AggregateFunction.COUNT);
        assertThat(browseMetric.resultType()).isEqualTo(SemanticDataType.INTEGER);
        assertThat(browseMetric.relationId()).isEqualTo("member.product_browses");
        assertThat(browseMetric.relationId()).doesNotContain("order");
        assertThat(browseMetric.scopeFilterDimensions()).isNotEmpty();

        var favoriteMetric = snapshot.metrics().stream()
                .filter(metric -> metric.id().equals("product.favorite_count"))
                .findFirst().orElseThrow();
        assertThat(favoriteMetric.aggregation()).isEqualTo(AggregateFunction.COUNT);
        assertThat(favoriteMetric.resultType()).isEqualTo(SemanticDataType.INTEGER);
        assertThat(favoriteMetric.relationId()).isEqualTo("member.product_favorites");
        assertThat(favoriteMetric.relationId()).doesNotContain("order");
        assertThat(favoriteMetric.scopeFilterDimensions()).isNotEmpty();

        // The two are distinct.
        assertThat(browseMetric.relationId()).isNotEqualTo(favoriteMetric.relationId());
    }

    @Test
    void storageCatalogEntitiesIncludeBrowseAndFavoriteTables() {
        var storage = CoreLoyaltyDevelopmentProfile.storageCatalog();

        var browseEntity = storage.entities().stream()
                .filter(e -> "seg_product_browse".equals(e.table().table().value()))
                .findFirst();
        assertThat(browseEntity).as("seg_product_browse entity table must be in storage catalog").isPresent();
        assertThat(browseEntity.get().context().tenantColumn().value()).isEqualTo("tenant_id");
        assertThat(browseEntity.get().context().programColumn().value()).isEqualTo("program_id");

        var favoriteEntity = storage.entities().stream()
                .filter(e -> "seg_product_favorite".equals(e.table().table().value()))
                .findFirst();
        assertThat(favoriteEntity).as("seg_product_favorite entity table must be in storage catalog").isPresent();
        assertThat(favoriteEntity.get().context().tenantColumn().value()).isEqualTo("tenant_id");
        assertThat(favoriteEntity.get().context().programColumn().value()).isEqualTo("program_id");
    }

    @Test
    void resolutionPolicyBindsBrowseAndFavoriteToNoOrderMetric() {
        var policy = CoreLoyaltyDevelopmentProfile.resolutionPolicy();

        assertThat(policy.conceptDefinitionIds())
                .containsEntry("PRODUCT_BROWSE_COUNT", "product.browse_count");
        assertThat(policy.conceptDefinitionIds())
                .containsEntry("PRODUCT_FAVORITE_COUNT", "product.favorite_count");

        // Both specific concept mappings point to non-order definitions.
        assertThat(policy.conceptDefinitionIds().get("PRODUCT_BROWSE_COUNT"))
                .isEqualTo("product.browse_count")
                .isNotEqualTo("order.positive_count");
        assertThat(policy.conceptDefinitionIds().get("PRODUCT_FAVORITE_COUNT"))
                .isEqualTo("product.favorite_count")
                .isNotEqualTo("order.positive_count");
    }
}
