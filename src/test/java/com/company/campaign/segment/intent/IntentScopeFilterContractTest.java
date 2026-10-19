package com.company.campaign.segment.intent;

import com.company.campaign.contracts.ContractVersions;
import com.company.campaign.contracts.SourceSpan;
import com.company.campaign.metadata.MetadataKind;
import com.company.campaign.metadata.domain.AggregateFunction;
import com.company.campaign.metadata.domain.ComparisonOperator;
import com.company.campaign.metadata.domain.FilterDomain;
import com.company.campaign.metadata.domain.FilterIdentifierKind;
import com.company.campaign.metadata.domain.SubjectKind;
import com.company.campaign.segment.intent.testsupport.IntentFixtures;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.company.campaign.testsupport.ContractAssertions.assertFailsWithCode;
import static org.assertj.core.api.Assertions.assertThat;

/** The strict JSON and hash boundary for user-supplied filters scoped inside one Metric aggregate. */
class IntentScopeFilterContractTest {

    private static final String UTTERANCE = "今年五月累计购买商品面膜的数量大于5件";
    private final SegmentIntentCodec codec = new SegmentIntentCodec();

    @Test
    void scopedMetricFilterRoundTripsAndIsGrounded() {
        SegmentIntent parsed = codec.parse(validJson(), UTTERANCE, IntentFixtures.emptyRegistry());

        IntentClause clause = parsed.clauses().getFirst();
        assertThat(clause.scopeFilters()).hasSize(1);
        IntentScopeFilter filter = clause.scopeFilters().getFirst();
        assertThat(filter.filterId()).isEqualTo("f1");
        assertThat(filter.concept()).isEqualTo("PRODUCT_NAME");
        assertThat(filter.domain()).isEqualTo(FilterDomain.PRODUCT);
        assertThat(filter.identifierKind()).isEqualTo(FilterIdentifierKind.NAME);
        assertThat(filter.operator()).isEqualTo(ComparisonOperator.EQUAL);
        assertThat(filter.value()).isEqualTo(new IntentValue.StringValue("面膜"));
        assertThat(filter.candidateHints()).containsExactly("order_item.product_name");
        assertThat(((IntentProvenance.FromSourceSpan) filter.provenance()).span().text()).isEqualTo("面膜");

        byte[] canonical = codec.write(parsed);
        assertThat(codec.parse(canonical, UTTERANCE, IntentFixtures.emptyRegistry())).isEqualTo(parsed);
        assertThat(codec.writeString(parsed)).contains("\"scopeFilters\":[{");
    }

    @Test
    void scopeFilterChangesCanonicalHash() {
        SegmentIntent mask = intentWithFilterValue("面膜");
        SegmentIntent lipstick = intentWithFilterValue("口红");

        assertThat(SegmentIntentHasher.hash(mask)).isNotEqualTo(SegmentIntentHasher.hash(lipstick));
        assertThat(codec.write(mask)).isNotEqualTo(codec.write(lipstick));
    }

    @Test
    void legacyClauseOmitsEmptyScopeFiltersFromCanonicalWire() {
        assertThat(codec.writeString(IntentFixtures.standardIntent())).doesNotContain("scopeFilters");
    }

    @Test
    void aNonMetricClauseCannotCarryScopeFilters() {
        String json = validJson().replace("\"definitionKind\":\"METRIC\"", "\"definitionKind\":\"ATTRIBUTE\"");
        assertFailsWithCode(() -> parse(json), IntentErrorCodes.SCOPE_FILTER_INVALID);
    }

    @Test
    void duplicateFilterIdsFailClosed() {
        String filter = filterJson("f1", "面膜");
        String json = validJson().replace("\"scopeFilters\":[" + filter + "]",
                "\"scopeFilters\":[" + filter + "," + filter + "]");
        assertFailsWithCode(() -> parse(json), IntentErrorCodes.DUPLICATE_SCOPE_FILTER_ID);
    }

    @Test
    void invalidFilterConceptFromModelFailsClosed() {
        String json = validJson().replace("\"concept\":\"PRODUCT_NAME\"", "\"concept\":\"order_item.product_name\"");
        assertFailsWithCode(() -> parse(json), IntentErrorCodes.MACHINE_CODE_INVALID);
    }

    @Test
    void anUnknownOrExecutableFilterFieldFailsClosed() {
        String json = validJson().replace("\"filterId\":\"f1\"", "\"filterId\":\"f1\",\"sql\":\"DROP\"");
        assertFailsWithCode(() -> parse(json), IntentErrorCodes.RESERVED_FIELD);
    }

    @Test
    void listOperatorRequiresAListValue() {
        String json = validJson().replace("\"operator\":\"EQUAL\",\"value\":{\"kind\":\"STRING\"",
                "\"operator\":\"IN\",\"value\":{\"kind\":\"STRING\"");
        assertFailsWithCode(() -> parse(json), IntentErrorCodes.SCOPE_FILTER_INVALID);
    }

    @Test
    void scopeFilterSourceSpanMustMatchTheExactUtterance() {
        String json = validJson().replace("\"text\":\"面膜\",\"start\":10,\"end\":12",
                "\"text\":\"口红\",\"start\":10,\"end\":12");
        assertFailsWithCode(() -> parse(json), IntentErrorCodes.SPAN_TEXT_MISMATCH);
    }

    private SegmentIntent parse(String json) {
        return codec.parse(json, UTTERANCE, IntentFixtures.emptyRegistry());
    }

    private static SegmentIntent intentWithFilterValue(String raw) {
        IntentScopeFilter filter = new IntentScopeFilter(
                "f1", "PRODUCT_NAME", FilterDomain.PRODUCT, FilterIdentifierKind.NAME,
                ComparisonOperator.EQUAL, new IntentValue.StringValue(raw),
                new IntentProvenance.FromSourceSpan(new SourceSpan("面膜", 10, 12)),
                List.of("order_item.product_name"), List.of());
        IntentClause clause = new IntentClause(
                "c1", "PURCHASED_ITEM_QUANTITY", MetadataKind.METRIC, false,
                ComparisonOperator.GREATER_THAN, new IntentValue.IntegerValue("5"),
                new IntentWindow.Fixed("2026-04-30T16:00:00Z", "2026-05-31T16:00:00Z"),
                null, AggregateFunction.SUM, null, List.of(filter),
                new IntentProvenance.FromSourceSpan(new SourceSpan(UTTERANCE, 0, UTTERANCE.length())),
                List.of("order_item.quantity_sum"), List.of());
        return new SegmentIntent(
                ContractVersions.CURRENT, SubjectKind.MEMBER, List.of(clause),
                new LogicalExpression.Clause("c1"), List.of(), List.of());
    }

    private static String validJson() {
        return "{\"intentVersion\":\"1.3\",\"subject\":\"MEMBER\",\"clauses\":[{"
                + "\"clauseId\":\"c1\",\"concept\":\"PURCHASED_ITEM_QUANTITY\","
                + "\"definitionKind\":\"METRIC\",\"negated\":false,\"operator\":\"GREATER_THAN\","
                + "\"value\":{\"kind\":\"INTEGER\",\"raw\":\"5\"},"
                + "\"window\":{\"kind\":\"FIXED\",\"start\":\"2026-04-30T16:00:00Z\","
                + "\"end\":\"2026-05-31T16:00:00Z\"},\"aggregation\":\"SUM\","
                + "\"scopeFilters\":[" + filterJson("f1", "面膜") + "],"
                + "\"provenance\":{\"type\":\"SOURCE_SPAN\",\"text\":\"" + UTTERANCE
                + "\",\"start\":0,\"end\":" + UTTERANCE.length() + "},"
                + "\"candidateHints\":[\"order_item.quantity_sum\"],\"assumptions\":[]}],"
                + "\"logicalExpression\":{\"type\":\"CLAUSE\",\"clauseId\":\"c1\"},"
                + "\"ambiguities\":[],\"unsupportedRequirements\":[]}";
    }

    private static String filterJson(String id, String raw) {
        return "{\"filterId\":\"" + id + "\",\"concept\":\"PRODUCT_NAME\","
                + "\"domain\":\"PRODUCT\",\"identifierKind\":\"NAME\","
                + "\"operator\":\"EQUAL\",\"value\":{\"kind\":\"STRING\",\"raw\":\"" + raw + "\"},"
                + "\"provenance\":{\"type\":\"SOURCE_SPAN\",\"text\":\"面膜\",\"start\":10,\"end\":12},"
                + "\"candidateHints\":[\"order_item.product_name\"],\"assumptions\":[]}";
    }
}
