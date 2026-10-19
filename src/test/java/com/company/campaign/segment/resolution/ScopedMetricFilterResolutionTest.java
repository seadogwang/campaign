package com.company.campaign.segment.resolution;

import com.company.campaign.contracts.Disposition;
import com.company.campaign.metadata.MetadataKind;
import com.company.campaign.metadata.domain.AggregateFunction;
import com.company.campaign.metadata.domain.ComparisonOperator;
import com.company.campaign.segment.intent.IntentClause;
import com.company.campaign.segment.intent.IntentCurrency;
import com.company.campaign.segment.intent.IntentProvenance;
import com.company.campaign.segment.intent.IntentScopeFilter;
import com.company.campaign.segment.intent.IntentValue;
import com.company.campaign.segment.intent.IntentWindow;
import com.company.campaign.segment.intent.testsupport.IntentFixtures;
import com.company.campaign.segment.resolution.testsupport.ResolutionFixtures;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Scoped Metric filters remain fail-closed when the selected Metric does not allowlist the dimension. */
class ScopedMetricFilterResolutionTest {

    @Test
    void aValidatedScopedFilterCannotBeIgnoredOrFlattenedByTheResolver() {
        IntentScopeFilter filter = new IntentScopeFilter(
                "f1", "PRODUCT_NAME", ComparisonOperator.EQUAL, new IntentValue.StringValue("面膜"),
                IntentFixtures.span("过去90天消费超过1000元", 7, 21),
                List.of("order_item.product_name"), List.of());
        IntentClause clause = new IntentClause(
                "c2", "PURCHASED_ITEM_QUANTITY", MetadataKind.METRIC, false,
                ComparisonOperator.GREATER_THAN, new IntentValue.MoneyValue("5"),
                new IntentWindow.Fixed("2026-04-30T16:00:00Z", "2026-05-31T16:00:00Z"),
                new IntentCurrency.Explicit("CNY"), AggregateFunction.SUM, null, List.of(filter),
                IntentFixtures.span("过去90天消费超过1000元", 7, 21),
                List.of("order.net_paid_amount"), List.of());

        ResolutionResult result = new IntentResolver(
                ResolutionFixtures.standardSnapshot(), ResolutionFixtures.policy(), ResolutionFixtures.registry())
                .resolve(IntentFixtures.intent(clause));

        assertThat(result.disposition()).isEqualTo(Disposition.UNSUPPORTED);
        assertThat(result.clarification()).isNull();
        assertThat(result.clause("c2").disposition()).isEqualTo(Disposition.UNSUPPORTED);
        assertThat(result.clause("c2").accepted()).isNull();
        assertThat(result.clause("c2").candidates())
                .extracting(CandidateEvidence::definitionId)
                .containsExactly("order.net_paid_amount");
        assertThat(result.clause("c2").rejected()).isEmpty();
        assertThat(result.clause("c2").scopeFilters()).singleElement().satisfies(resolution -> {
            assertThat(resolution.disposition()).isEqualTo(Disposition.UNSUPPORTED);
            assertThat(resolution.acceptedAttributeId()).isNull();
        });
    }
}
