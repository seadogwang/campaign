package com.company.campaign.query;

import com.company.campaign.metadata.MetadataKind;
import com.company.campaign.metadata.domain.ComparisonOperator;
import com.company.campaign.query.plan.CurrencyBinding;
import com.company.campaign.query.plan.SemanticQueryPlan;
import com.company.campaign.query.plan.SemanticQueryPlanCodec;
import com.company.campaign.query.testsupport.QueryPlanFixtures;
import com.company.campaign.segment.validation.testsupport.SegmentValidationFixtures;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Finding 2: currency policy is preserved per referenced definition.
 *
 * <p>A segment that references a {@code CONVERT_TO_REPORTING_CURRENCY} definition and a
 * {@code SINGLE_CURRENCY_ONLY} definition at the same time keeps both bindings distinctly, so a later
 * executor can apply the correct policy to the correct comparison instead of one collapsed plan-level action.
 */
class CurrencyBindingTest {

    private static final SemanticQueryPlanCodec CODEC = new SemanticQueryPlanCodec();

    private static CurrencyBinding binding(SemanticQueryPlan plan, String definitionId) {
        return plan.currencyBindings().stream()
                .filter(binding -> binding.definitionId().equals(definitionId))
                .findFirst()
                .orElseThrow();
    }

    private static SemanticQueryPlan mixedPlan(
            com.company.campaign.metadata.domain.MetadataSnapshot snapshot,
            com.company.campaign.security.context.TrustedExecutionContext context,
            List<com.company.campaign.segment.dsl.predicate.Predicate> predicate) {
        var document = QueryPlanFixtures.document(
                snapshot, SegmentValidationFixtures.all("n-root", predicate));
        return QueryPlanFixtures.plan(document, snapshot, context);
    }

    private static com.company.campaign.segment.dsl.predicate.Predicate balanceGreaterThan(String id, String attributeId) {
        return SegmentValidationFixtures.attribute(id, attributeId, ComparisonOperator.GREATER_THAN,
                SegmentValidationFixtures.value(SegmentValidationFixtures.moneyValue("1", "CNY")));
    }

    @Test
    void mixedCurrencyPoliciesSurviveAsDistinctOrderedBindings() {
        var snapshot = QueryPlanFixtures.mixedCurrencySnapshot();
        SemanticQueryPlan plan = mixedPlan(snapshot, SegmentValidationFixtures.context(), List.of(
                SegmentValidationFixtures.tierEquals("n-tier", "GOLD"),
                balanceGreaterThan("n-balance", "member.balance"),
                balanceGreaterThan("n-single", "member.single_balance")));

        assertThat(plan.currencyBindings()).extracting(CurrencyBinding::definitionId)
                .containsExactly("member.balance", "member.single_balance", "member.tier");

        CurrencyBinding convert = binding(plan, "member.balance");
        assertThat(convert.kind()).isEqualTo(MetadataKind.ATTRIBUTE);
        assertThat(convert.action()).isEqualTo(CurrencyBinding.Action.CONVERT_TO_REPORTING_CURRENCY);
        assertThat(convert.targetCurrency()).isEqualTo("CNY");
        assertThat(convert.rateResolution()).isEqualTo(CurrencyBinding.RateResolution.DEFERRED);

        CurrencyBinding single = binding(plan, "member.single_balance");
        assertThat(single.action()).isEqualTo(CurrencyBinding.Action.SINGLE_CURRENCY_ONLY);
        assertThat(single.targetCurrency()).isNull();
        assertThat(single.rateResolution()).isEqualTo(CurrencyBinding.RateResolution.NOT_APPLICABLE);

        CurrencyBinding none = binding(plan, "member.tier");
        assertThat(none.action()).isEqualTo(CurrencyBinding.Action.NO_CONVERSION);
        assertThat(none.targetCurrency()).isNull();
        assertThat(none.rateResolution()).isEqualTo(CurrencyBinding.RateResolution.NOT_APPLICABLE);
    }

    @Test
    void theMixedPlanSerializesAndHashesDeterministically() {
        var snapshot = QueryPlanFixtures.mixedCurrencySnapshot();
        SemanticQueryPlan first = mixedPlan(snapshot, SegmentValidationFixtures.context(), List.of(
                SegmentValidationFixtures.tierEquals("n-tier", "GOLD"),
                balanceGreaterThan("n-balance", "member.balance"),
                balanceGreaterThan("n-single", "member.single_balance")));
        SemanticQueryPlan again = mixedPlan(snapshot, SegmentValidationFixtures.context(), List.of(
                SegmentValidationFixtures.tierEquals("n-tier", "GOLD"),
                balanceGreaterThan("n-balance", "member.balance"),
                balanceGreaterThan("n-single", "member.single_balance")));

        assertThat(again.planHash()).isEqualTo(first.planHash());
        assertThat(CODEC.write(again)).isEqualTo(CODEC.write(first));
        assertThat(CODEC.writeString(first))
                .contains("currencyBindings")
                .contains("SINGLE_CURRENCY_ONLY")
                .contains("CONVERT_TO_REPORTING_CURRENCY");

        // Duplicate uses of one definition still produce exactly one binding.
        SemanticQueryPlan duplicated = mixedPlan(snapshot, SegmentValidationFixtures.context(), List.of(
                balanceGreaterThan("n-balance-a", "member.balance"),
                SegmentValidationFixtures.attribute("n-balance-b", "member.balance", ComparisonOperator.LESS_THAN,
                        SegmentValidationFixtures.value(SegmentValidationFixtures.moneyValue("9999", "CNY")))));
        assertThat(duplicated.currencyBindings()).hasSize(1);
        assertThat(duplicated.currencyBindings().get(0).definitionId()).isEqualTo("member.balance");
    }

    @Test
    void theConversionTargetIsTheTrustedReportingCurrencyAndChangesTheHash() {
        var snapshot = QueryPlanFixtures.mixedCurrencySnapshot();
        SemanticQueryPlan cny = mixedPlan(snapshot, SegmentValidationFixtures.context(), List.of(
                balanceGreaterThan("n-balance", "member.balance"),
                balanceGreaterThan("n-single", "member.single_balance")));
        SemanticQueryPlan usd = mixedPlan(snapshot, QueryPlanFixtures.context(
                "tenant-001", "program-001", "scope-008", "membership-1", "user-001",
                QueryPlanFixtures.AS_OF, QueryPlanFixtures.WATERMARK, java.time.ZoneId.of("Asia/Shanghai"), "USD",
                com.company.campaign.metadata.testsupport.MetadataFixtures.allPermissions()), List.of(
                balanceGreaterThan("n-balance", "member.balance"),
                balanceGreaterThan("n-single", "member.single_balance")));

        assertThat(binding(cny, "member.balance").targetCurrency()).isEqualTo("CNY");
        assertThat(binding(usd, "member.balance").targetCurrency()).isEqualTo("USD");
        // The single-currency binding carries no target under either reporting currency.
        assertThat(binding(usd, "member.single_balance").action())
                .isEqualTo(CurrencyBinding.Action.SINGLE_CURRENCY_ONLY);
        assertThat(binding(usd, "member.single_balance").targetCurrency()).isNull();
        assertThat(usd.planHash()).isNotEqualTo(cny.planHash());
    }

    @Test
    void aRelationDependencyNeverReceivesACurrencyBinding() {
        SemanticQueryPlan plan = QueryPlanFixtures.plan(SegmentValidationFixtures.exists(
                "n-e", "member.orders", SegmentValidationFixtures.lifetime(),
                SegmentValidationFixtures.attribute("n-c", "order.channel", ComparisonOperator.EQUAL,
                        SegmentValidationFixtures.value(SegmentValidationFixtures.enumValue("ONLINE")))));

        assertThat(plan.currencyBindings()).extracting(CurrencyBinding::kind)
                .doesNotContain(MetadataKind.RELATION);
        assertThat(plan.currencyBindings()).extracting(CurrencyBinding::definitionId)
                .containsExactly("order.channel");
    }

    @Test
    void oneDefinitionUsedTwiceProducesOneBindingWhosePolicyMatchesItsDefinition() {
        SemanticQueryPlan plan = QueryPlanFixtures.plan(SegmentValidationFixtures.all("n-root", List.of(
                balanceGreaterThan("n-balance-a", "member.balance"),
                SegmentValidationFixtures.attribute("n-balance-b", "member.balance",
                        ComparisonOperator.LESS_THAN,
                        SegmentValidationFixtures.value(SegmentValidationFixtures.moneyValue("9999", "CNY"))))));

        assertThat(plan.currencyBindings()).hasSize(1);
        assertThat(plan.currencyBindings().get(0).action())
                .isEqualTo(CurrencyBinding.Action.CONVERT_TO_REPORTING_CURRENCY);
    }
}