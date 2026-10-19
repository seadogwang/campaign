package com.company.campaign.dev.segment;

import com.company.campaign.builder.segment.DevelopmentAiToSqlService;
import com.company.campaign.builder.segment.ResolvedIntentDslCompiler;
import com.company.campaign.builder.segment.SegmentIntentModelPort;
import com.company.campaign.query.plan.SegmentQueryPlanner;
import com.company.campaign.query.sql.SqlCompiler;
import com.company.campaign.segment.intent.SegmentIntentCodec;
import com.company.campaign.segment.resolution.IntentResolver;
import com.company.campaign.segment.validation.SegmentValidator;
import com.company.campaign.segment.validation.testsupport.SegmentValidationFixtures;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import static org.assertj.core.api.Assertions.assertThat;

class FormalIntentAiToSqlTest {

    private static final String UTTERANCE =
            "金卡、过去7天实付金额大于1000元，并且过去3周购买过产品名称水杯的会员";

    @Test
    void formalIntentCompilesThroughTheStrictPipelineWithoutDatabaseAccess() {
        var intent = new SegmentIntentCodec().parse(
                intentJson(), UTTERANCE, CoreLoyaltyDevelopmentProfile.registeredDefaults());
        var resolution = new IntentResolver(
                CoreLoyaltyDevelopmentProfile.snapshot(),
                CoreLoyaltyDevelopmentProfile.resolutionPolicy(),
                CoreLoyaltyDevelopmentProfile.registeredDefaults()).resolve(intent);
        assertThat(resolution.isResolved()).isTrue();
        assertThat(resolution.clarification()).isNull();

        var request = CoreLoyaltyDevelopmentProfile.request(
                UTTERANCE, Instant.parse("2026-10-04T00:00:00Z"));
        var compilation = new ResolvedIntentDslCompiler(request.resolutionPolicy())
                .compile(intent, resolution, request.permittedSnapshot(), request.registeredDefaults());
        assertThat(compilation.isResolved())
                .as("%s / %s", compilation.disposition(), compilation.failureCode())
                .isTrue();
        var authorization = SegmentValidationFixtures.allowAll();
        var validation = new SegmentValidator(authorization).validate(
                compilation.document(), request.permittedSnapshot(), request.trustedContext(),
                request.validationPolicy());
        assertThat(validation.valid()).as("%s", validation.issues()).isTrue();
        var plan = new SegmentQueryPlanner(authorization).plan(
                validation, request.permittedSnapshot(), request.trustedContext());
        try {
            new SqlCompiler().compile(
                    plan, request.storageCatalog(), request.sqlCompilePolicy(), request.fxRateBinding());
        } catch (com.company.campaign.query.sql.SqlException failure) {
            throw new AssertionError(failure.code() + " " + failure.details(), failure);
        }

        SegmentIntentModelPort model = ignored -> intentJson().getBytes(StandardCharsets.UTF_8);
        var service = new DevelopmentAiToSqlService(model, SegmentValidationFixtures.allowAll());
        var result = service.invoke(request);

        assertThat(result.isCompiled())
                .as("%s / %s", result.disposition(), result.failureCode())
                .isTrue();
        assertThat(result.clarification()).isNull();
        assertThat(result.sql())
                .contains("\"public\".\"seg_member\"")
                .contains("\"public\".\"seg_order\"")
                .contains("\"public\".\"seg_order_item\"")
                .contains("\"current_tier_code\"")
                .contains("\"paid_amount\"")
                .contains("\"currency_code\"")
                .contains("\"product_name\"")
                .doesNotContain("fx_rates")
                .doesNotContain("金卡")
                .doesNotContain("水杯");
        assertThat(result.parameters())
                .filteredOn(parameter -> !parameter.redacted())
                .extracting(parameter -> parameter.canonicalValue())
                .contains("GOLD", "1000", "CNY", "水杯", "0");
    }

    private static String intentJson() {
        return "{\"intentVersion\":\"1.3\",\"subject\":\"MEMBER\",\"clauses\":["
                + clause(
                        "tier", "CURRENT_TIER", "ATTRIBUTE", "EQUAL",
                        "{\"kind\":\"ENUM\",\"raw\":\"金卡\"}", null, null,
                        null, "金卡", "member.current_tier") + ","
                + clause(
                        "spend", "PAID_PURCHASE_AMOUNT", "METRIC", "GREATER_THAN",
                        "{\"kind\":\"MONEY\",\"raw\":\"1000\"}",
                        "{\"kind\":\"ROLLING\",\"amount\":7,\"unit\":\"DAY\"}",
                        "{\"mode\":\"EXPLICIT\",\"code\":\"CNY\"}",
                        "\"aggregation\":\"SUM\",", "过去7天实付金额大于1000元",
                        "order.paid_amount_sum") + ","
                + clause(
                        "quantity", "PURCHASED_ITEM_QUANTITY", "METRIC", "GREATER_THAN",
                        "{\"kind\":\"INTEGER\",\"raw\":\"0\"}",
                        "{\"kind\":\"ROLLING\",\"amount\":3,\"unit\":\"WEEK\"}", null,
                        "\"aggregation\":\"SUM\",\"scopeFilters\":[{"
                                + "\"filterId\":\"product\",\"concept\":\"PRODUCT_NAME\","
                                + "\"domain\":\"PRODUCT\",\"identifierKind\":\"NAME\","
                                + "\"operator\":\"EQUAL\",\"value\":{\"kind\":\"STRING\",\"raw\":\"水杯\"},"
                                + "\"provenance\":" + span("产品名称水杯") + ","
                                + "\"candidateHints\":[\"order_item.product_name\"],\"assumptions\":[]}],",
                        "过去3周购买过产品名称水杯", "order_item.quantity_sum")
                + "],\"logicalExpression\":{\"type\":\"ALL\",\"children\":["
                + "{\"type\":\"CLAUSE\",\"clauseId\":\"tier\"},"
                + "{\"type\":\"CLAUSE\",\"clauseId\":\"spend\"},"
                + "{\"type\":\"CLAUSE\",\"clauseId\":\"quantity\"}]},"
                + "\"ambiguities\":[],\"unsupportedRequirements\":[]}";
    }

    private static String clause(
            String id,
            String concept,
            String kind,
            String operator,
            String value,
            String window,
            String currency,
            String additional,
            String source,
            String candidate) {
        StringBuilder json = new StringBuilder("{\"clauseId\":\"").append(id)
                .append("\",\"concept\":\"").append(concept)
                .append("\",\"definitionKind\":\"").append(kind)
                .append("\",\"negated\":false,\"operator\":\"").append(operator)
                .append("\",\"value\":").append(value).append(',');
        if (window != null) {
            json.append("\"window\":").append(window).append(',');
        }
        if (currency != null) {
            json.append("\"currency\":").append(currency).append(',');
        }
        if (additional != null) {
            json.append(additional);
        }
        return json.append("\"provenance\":").append(span(source)).append(',')
                .append("\"candidateHints\":[\"").append(candidate)
                .append("\"],\"assumptions\":[]}").toString();
    }

    private static String span(String source) {
        int start = UTTERANCE.indexOf(source);
        return "{\"type\":\"SOURCE_SPAN\",\"text\":\"" + source + "\",\"start\":" + start
                + ",\"end\":" + (start + source.length()) + "}";
    }
}
