package com.company.campaign.builder.segment.testsupport;

import com.company.campaign.builder.segment.DevelopmentAiToSqlRequest;
import com.company.campaign.builder.segment.DevelopmentAiToSqlService;
import com.company.campaign.builder.segment.SegmentIntentModelPort;
import com.company.campaign.builder.segment.SegmentModelRequest;
import com.company.campaign.metadata.domain.AggregateEmptySet;
import com.company.campaign.metadata.domain.AggregateFunction;
import com.company.campaign.metadata.domain.AttributeDefinition;
import com.company.campaign.metadata.domain.ComparisonOperator;
import com.company.campaign.metadata.domain.CurrencySemantics;
import com.company.campaign.metadata.domain.MetadataSnapshot;
import com.company.campaign.metadata.domain.MetricDefinition;
import com.company.campaign.metadata.domain.NullSemantics;
import com.company.campaign.metadata.domain.SemanticDataType;
import com.company.campaign.metadata.domain.StringCaseSensitivity;
import com.company.campaign.metadata.domain.StringNormalization;
import com.company.campaign.metadata.domain.StringSemantics;
import com.company.campaign.metadata.domain.SubjectKind;
import com.company.campaign.metadata.domain.TimeWindowKind;
import com.company.campaign.metadata.testsupport.MetadataFixtures;
import com.company.campaign.query.sql.SqlCompilePolicy;
import com.company.campaign.query.sql.SqlTestSupport;
import com.company.campaign.query.sql.StorageCatalog;
import com.company.campaign.security.context.TrustedExecutionContext;
import com.company.campaign.segment.intent.RegisteredDefaultSource;
import com.company.campaign.segment.intent.testsupport.IntentFixtures;
import com.company.campaign.segment.resolution.ResolutionPolicy;
import com.company.campaign.segment.validation.SegmentValidationPolicy;
import com.company.campaign.segment.validation.testsupport.SegmentValidationFixtures;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Deterministic T11A fixtures built only from accepted T02/T04/T05/T07/T08/T09B/T10 public contracts and test
 * support. No clock, network, database, random identifier, corpus asset or provider SDK is touched.
 */
public final class SliceTestFixtures {

    public static final String POLICY_VERSION = "slice-resolution-policy/1";

    /** The standard concept-to-definition mapping used by the deterministic slice tests. */
    public static final Map<String, String> CONCEPT_MAPPINGS = Map.of(
            "MEMBER_TIER", "member.tier",
            "SPEND", "order.net_paid_amount",
            "ORDER_COUNT", "order.count",
            "MEMBER_CODE", "member.code",
            "MEMBER_ORDERS", "member.orders");

    private SliceTestFixtures() {
    }

    // ------------------------------------------------------------------ snapshot and definitions

    public static MetadataSnapshot snapshot() {
        return MetadataFixtures.snapshot(
                List.of(MetadataFixtures.tierAttribute(), memberCodeAttribute()),
                List.of(netPaidAmountMetric(), orderCountMetric()),
                List.of(MetadataFixtures.ordersRelation()));
    }

    public static MetricDefinition netPaidAmountMetric() {
        return new MetricDefinition(
                "order.net_paid_amount",
                MetadataFixtures.memberVersion(),
                SubjectKind.MEMBER,
                "Net Paid Amount",
                SemanticDataType.MONEY,
                null,
                List.of(),
                List.of(ComparisonOperator.EQUAL, ComparisonOperator.NOT_EQUAL,
                        ComparisonOperator.GREATER_THAN, ComparisonOperator.LESS_THAN,
                        ComparisonOperator.IS_NULL, ComparisonOperator.IS_NOT_NULL),
                AggregateFunction.SUM,
                "member.orders",
                "order.paid_at",
                com.company.campaign.metadata.domain.EventTimeSemantics.EVENT_TIME,
                com.company.campaign.metadata.domain.MetadataFilter.empty(),
                com.company.campaign.metadata.domain.RefundTreatment.IGNORE,
                CurrencySemantics.CONVERT_TO_REPORTING_CURRENCY,
                List.of(TimeWindowKind.ROLLING, TimeWindowKind.LIFETIME, TimeWindowKind.FIXED),
                com.company.campaign.metadata.domain.WindowBoundarySemantics.INCLUSIVE_START_EXCLUSIVE_END,
                NullSemantics.EXCLUDED,
                AggregateEmptySet.zero(SemanticDataType.MONEY),
                List.of(),
                List.of(),
                null);
    }

    /** A second, semantically distinct money metric used only to construct an ambiguous resolution. */
    public static MetricDefinition grossAmountMetric() {
        return new MetricDefinition(
                "order.gross_amount",
                MetadataFixtures.memberVersion(),
                SubjectKind.MEMBER,
                "Gross Amount",
                SemanticDataType.MONEY,
                null,
                List.of(),
                List.of(ComparisonOperator.EQUAL, ComparisonOperator.NOT_EQUAL,
                        ComparisonOperator.GREATER_THAN, ComparisonOperator.LESS_THAN,
                        ComparisonOperator.IS_NULL, ComparisonOperator.IS_NOT_NULL),
                AggregateFunction.SUM,
                "member.orders",
                "order.paid_at",
                com.company.campaign.metadata.domain.EventTimeSemantics.EVENT_TIME,
                com.company.campaign.metadata.domain.MetadataFilter.empty(),
                com.company.campaign.metadata.domain.RefundTreatment.IGNORE,
                CurrencySemantics.CONVERT_TO_REPORTING_CURRENCY,
                List.of(TimeWindowKind.ROLLING, TimeWindowKind.LIFETIME, TimeWindowKind.FIXED),
                com.company.campaign.metadata.domain.WindowBoundarySemantics.INCLUSIVE_START_EXCLUSIVE_END,
                NullSemantics.EXCLUDED,
                AggregateEmptySet.zero(SemanticDataType.MONEY),
                List.of(),
                List.of(),
                null);
    }

    /** A snapshot whose only attribute is {@code member.code}, so a NULL-operator clause is unambiguous. */
    public static MetadataSnapshot codeOnlySnapshot() {
        return MetadataFixtures.snapshot(
                List.of(memberCodeAttribute()),
                List.of(netPaidAmountMetric(), orderCountMetric()),
                List.of(MetadataFixtures.ordersRelation()));
    }

    /** The standard snapshot plus a second compatible money metric, so a spend clause is ambiguous. */
    public static MetadataSnapshot ambiguousSpendSnapshot() {
        return MetadataFixtures.snapshot(
                List.of(MetadataFixtures.tierAttribute(), memberCodeAttribute()),
                List.of(netPaidAmountMetric(), grossAmountMetric(), orderCountMetric()),
                List.of(MetadataFixtures.ordersRelation()));
    }

    public static AttributeDefinition memberCodeAttribute() {
        return new AttributeDefinition(
                "member.code",
                MetadataFixtures.memberVersion(),
                SubjectKind.MEMBER,
                "Member Code",
                SemanticDataType.STRING,
                StringSemantics.localeNeutral(StringCaseSensitivity.CASE_SENSITIVE, StringNormalization.NONE),
                List.of(),
                List.of(ComparisonOperator.EQUAL, ComparisonOperator.NOT_EQUAL,
                        ComparisonOperator.IS_NULL, ComparisonOperator.IS_NOT_NULL),
                List.of(),
                NullSemantics.EXCLUDED,
                CurrencySemantics.NOT_APPLICABLE,
                List.of(),
                List.of(),
                null);
    }

    public static MetricDefinition orderCountMetric() {
        return new MetricDefinition(
                "order.count",
                MetadataFixtures.memberVersion(),
                SubjectKind.MEMBER,
                "Order Count",
                SemanticDataType.INTEGER,
                null,
                List.of(),
                List.of(ComparisonOperator.EQUAL, ComparisonOperator.NOT_EQUAL,
                        ComparisonOperator.GREATER_THAN, ComparisonOperator.LESS_THAN,
                        ComparisonOperator.BETWEEN,
                        ComparisonOperator.IS_NULL, ComparisonOperator.IS_NOT_NULL),
                AggregateFunction.COUNT,
                "member.orders",
                null,
                null,
                com.company.campaign.metadata.domain.MetadataFilter.empty(),
                com.company.campaign.metadata.domain.RefundTreatment.IGNORE,
                CurrencySemantics.NOT_APPLICABLE,
                List.of(TimeWindowKind.LIFETIME),
                com.company.campaign.metadata.domain.WindowBoundarySemantics.NOT_APPLICABLE,
                NullSemantics.EXCLUDED,
                AggregateEmptySet.zero(SemanticDataType.INTEGER),
                List.of(),
                List.of(),
                null);
    }

    // ------------------------------------------------------------------ host-supplied components

    public static StorageCatalog catalog() {
        return SqlTestSupport.standardCatalog();
    }

    public static StorageCatalog catalogWithoutFx() {
        return SqlTestSupport.catalogWithoutFx();
    }

    public static com.company.campaign.query.sql.FxRateBinding fx() {
        return SqlTestSupport.cnyFx();
    }

    public static TrustedExecutionContext context() {
        return MetadataFixtures.context(MetadataFixtures.allPermissions());
    }

    public static TrustedExecutionContext contextPinning(String snapshotId) {
        return com.company.campaign.query.testsupport.QueryPlanFixtures.contextPinning(
                snapshotId, MetadataFixtures.TENANT, MetadataFixtures.PROGRAM, "scope-008");
    }

    public static ResolutionPolicy policy() {
        return ResolutionPolicy.publish(POLICY_VERSION, null, CONCEPT_MAPPINGS);
    }

    public static ResolutionPolicy policyWithCurrencyDefault() {
        return ResolutionPolicy.publish(POLICY_VERSION, "reporting.currency", CONCEPT_MAPPINGS);
    }

    public static ResolutionPolicy otherPolicy() {
        return ResolutionPolicy.publish("slice-resolution-policy/2", null, CONCEPT_MAPPINGS);
    }

    public static RegisteredDefaultSource registry() {
        return IntentFixtures.currencyRegistry();
    }

    public static RegisteredDefaultSource emptyRegistry() {
        return IntentFixtures.emptyRegistry();
    }

    public static SegmentValidationPolicy validationPolicy() {
        return SegmentValidationPolicy.standard();
    }

    public static SqlCompilePolicy sqlCompilePolicy() {
        return SqlCompilePolicy.standard();
    }

    // ------------------------------------------------------------------ intent and resolution

    public static com.company.campaign.segment.intent.SegmentIntent intent(String utterance, String json) {
        return new com.company.campaign.segment.intent.SegmentIntentCodec().parse(
                json.getBytes(StandardCharsets.UTF_8), utterance, registry());
    }

    public static com.company.campaign.segment.resolution.ResolutionResult resolve(
            com.company.campaign.segment.intent.SegmentIntent intent,
            MetadataSnapshot snapshot,
            ResolutionPolicy policy) {
        return new com.company.campaign.segment.resolution.IntentResolver(snapshot, policy, registry())
                .resolve(intent);
    }

    /** The registered reporting-currency revision hash used by the registered-default money clause. */
    public static String registeredCurrencyHash() {
        return IntentFixtures.reportingCurrencyRevision().contentHash().value();
    }

    public static String registeredCurrencySpendJson() {
        return singleClauseJson("过去90天消费超过1000元", "SPEND", "METRIC", "\"operator\":\"GREATER_THAN\",",
                "\"value\":{\"kind\":\"MONEY\",\"raw\":\"1000\"},"
                        + "\"window\":{\"kind\":\"ROLLING\",\"amount\":90,\"unit\":\"DAY\"},"
                        + "\"currency\":{\"mode\":\"REGISTERED_DEFAULT\",\"defaultId\":\"reporting.currency\","
                        + "\"version\":\"1\",\"contentHash\":\"" + registeredCurrencyHash() + "\"},"
                        + "\"aggregation\":\"SUM\",",
                "过去90天消费超过1000元", 0);
    }

    // ------------------------------------------------------------------ request and service

    public static DevelopmentAiToSqlRequest request(String utterance, SegmentIntentModelPort port) {
        return new DevelopmentAiToSqlRequest(
                utterance, "zh-CN", snapshot(), policy(), registry(), context(),
                validationPolicy(), catalog(), sqlCompilePolicy(), fx());
    }

    public static DevelopmentAiToSqlService service(SegmentIntentModelPort port) {
        return new DevelopmentAiToSqlService(port, SegmentValidationFixtures.allowAll());
    }

    /** A request against the standard snapshot but with an overridden policy/defaults. */
    public static DevelopmentAiToSqlRequest request(
            String utterance,
            com.company.campaign.metadata.domain.MetadataSnapshot snapshot,
            ResolutionPolicy policy,
            RegisteredDefaultSource defaults) {
        return new DevelopmentAiToSqlRequest(
                utterance, "zh-CN", snapshot, policy, defaults, context(),
                validationPolicy(), catalog(), sqlCompilePolicy(), fx());
    }

    /** A request whose trusted context pins a different Metadata snapshot id. */
    public static DevelopmentAiToSqlRequest requestWithContextPin(String snapshotId) {
        return new DevelopmentAiToSqlRequest(
                "Gold会员", "zh-CN", snapshot(), policy(), registry(), contextPinning(snapshotId),
                validationPolicy(), catalog(), sqlCompilePolicy(), fx());
    }

    /** A request against an explicit storage catalog. */
    public static DevelopmentAiToSqlRequest requestWithCatalog(String utterance, StorageCatalog catalog) {
        return new DevelopmentAiToSqlRequest(
                utterance, "zh-CN", snapshot(), policy(), registry(), context(),
                validationPolicy(), catalog, sqlCompilePolicy(), fx());
    }

    /** A request against an explicit FX binding. */
    public static DevelopmentAiToSqlRequest requestWithFx(
            String utterance, com.company.campaign.query.sql.FxRateBinding fx) {
        return new DevelopmentAiToSqlRequest(
                utterance, "zh-CN", snapshot(), policy(), registry(), context(),
                validationPolicy(), catalog(), sqlCompilePolicy(), fx);
    }

    // ------------------------------------------------------------------ stub model port

    /** A deterministic stub model port returning one fixed raw response and recording the invocation count. */
    public static final class StubModelPort implements SegmentIntentModelPort {

        private final byte[] response;
        private final RuntimeException failure;
        private final boolean returnNull;
        private final AtomicInteger invocations = new AtomicInteger();
        private volatile SegmentModelRequest lastRequest;

        private StubModelPort(byte[] response, RuntimeException failure, boolean returnNull) {
            this.response = response;
            this.failure = failure;
            this.returnNull = returnNull;
        }

        public static StubModelPort returning(String json) {
            return new StubModelPort(json.getBytes(StandardCharsets.UTF_8), null, false);
        }

        public static StubModelPort returning(byte[] bytes) {
            return new StubModelPort(bytes, null, false);
        }

        public static StubModelPort returningNull() {
            return new StubModelPort(null, null, true);
        }

        public static StubModelPort throwing() {
            return new StubModelPort(null, new IllegalStateException("provider failure with secret-like text"), false);
        }

        @Override
        public byte[] invoke(SegmentModelRequest request) {
            invocations.incrementAndGet();
            lastRequest = request;
            if (failure != null) {
                throw failure;
            }
            return returnNull ? null : response;
        }

        public int invocations() {
            return invocations.get();
        }

        public SegmentModelRequest lastRequest() {
            return lastRequest;
        }
    }

    // ------------------------------------------------------------------ intent JSON

    public static String singleClauseJson(String utterance, String concept, String kind, String operatorJson,
            String slotsJson, String spanText, int start) {
        return intentJson(
                List.of(clause("c1", concept, kind, false, operatorJson, slotsJson, spanText, start)),
                "{\"type\":\"CLAUSE\",\"clauseId\":\"c1\"}",
                "[]");
    }

    /** One clause fragment, with an explicit clause id and optional clause-level negation. */
    public static String clause(String id, String concept, String kind, boolean negated, String operatorJson,
            String slotsJson, String spanText, int start) {
        return "{\"clauseId\":\"" + id + "\",\"concept\":\"" + concept + "\",\"definitionKind\":\"" + kind
                + "\",\"negated\":" + negated + "," + operatorJson + slotsJson
                + "\"provenance\":{\"type\":\"SOURCE_SPAN\",\"text\":\"" + spanText + "\",\"start\":" + start
                + ",\"end\":" + (start + spanText.length()) + "},\"candidateHints\":[],\"assumptions\":[]}";
    }

    public static String intentJson(List<String> clauses, String expressionJson, String ambiguitiesJson) {
        return "{\"intentVersion\":\"1.3\",\"subject\":\"MEMBER\",\"clauses\":["
                + String.join(",", clauses) + "],\"logicalExpression\":" + expressionJson
                + ",\"ambiguities\":" + ambiguitiesJson + ",\"unsupportedRequirements\":[]}";
    }

    public static String enumTierJson() {
        return singleClauseJson("Gold会员", "MEMBER_TIER", "ATTRIBUTE", "\"operator\":\"EQUAL\",",
                "\"value\":{\"kind\":\"ENUM\",\"raw\":\"Gold\"},", "Gold会员", 0);
    }

    public static String integerCountJson() {
        return singleClauseJson("订单数超过3", "ORDER_COUNT", "METRIC", "\"operator\":\"GREATER_THAN\",",
                "\"value\":{\"kind\":\"INTEGER\",\"raw\":\"3\"},"
                        + "\"window\":{\"kind\":\"LIFETIME\"},\"aggregation\":\"COUNT\",",
                "订单数超过3", 0);
    }

    public static String moneySpendJson() {
        return singleClauseJson("过去90天消费超过1000元", "SPEND", "METRIC", "\"operator\":\"GREATER_THAN\",",
                "\"value\":{\"kind\":\"MONEY\",\"raw\":\"1000\"},"
                        + "\"window\":{\"kind\":\"ROLLING\",\"amount\":90,\"unit\":\"DAY\"},"
                        + "\"currency\":{\"mode\":\"EXPLICIT\",\"code\":\"CNY\"},\"aggregation\":\"SUM\",",
                "过去90天消费超过1000元", 0);
    }

    public static String relationJson() {
        return singleClauseJson("订单", "MEMBER_ORDERS", "RELATION", "", "", "订单", 0);
    }

    public static String stringCodeJson(String raw) {
        return singleClauseJson("编码等于x", "MEMBER_CODE", "ATTRIBUTE", "\"operator\":\"EQUAL\",",
                "\"value\":{\"kind\":\"STRING\",\"raw\":\"" + raw + "\"},", "编码等于x", 0);
    }

    public static final String NESTED_UTTERANCE = "Gold会员订单数超过3编码等于ABC";

    /** ALL(tier, ANY(count, NOT(code))) over one utterance, spanning three grounded clauses. */
    public static String nestedGroupingJson() {
        return intentJson(
                List.of(
                        clause("c1", "MEMBER_TIER", "ATTRIBUTE", false, "\"operator\":\"EQUAL\",",
                                "\"value\":{\"kind\":\"ENUM\",\"raw\":\"Gold\"},", "Gold会员", 0),
                        clause("c2", "ORDER_COUNT", "METRIC", false, "\"operator\":\"GREATER_THAN\",",
                                "\"value\":{\"kind\":\"INTEGER\",\"raw\":\"3\"},"
                                        + "\"window\":{\"kind\":\"LIFETIME\"},\"aggregation\":\"COUNT\",",
                                "订单数超过3", 6),
                        clause("c3", "MEMBER_CODE", "ATTRIBUTE", false, "\"operator\":\"EQUAL\",",
                                "\"value\":{\"kind\":\"STRING\",\"raw\":\"ABC\"},", "编码等于ABC", 12)),
                "{\"type\":\"ALL\",\"children\":[{\"type\":\"CLAUSE\",\"clauseId\":\"c1\"},"
                        + "{\"type\":\"ANY\",\"children\":[{\"type\":\"CLAUSE\",\"clauseId\":\"c2\"},"
                        + "{\"type\":\"NOT\",\"child\":{\"type\":\"CLAUSE\",\"clauseId\":\"c3\"}}]}]}",
                "[]");
    }

    public static final String NULL_UTTERANCE = "编码为空或编码不等于ABC";

    /** ALL(code IS_NULL, code NOT_EQUAL ABC), proving NULL is not silently included. */
    public static String nullSemanticsJson() {
        return intentJson(
                List.of(
                        clause("c1", "MEMBER_CODE", "ATTRIBUTE", false, "\"operator\":\"IS_NULL\",", "", "编码为空", 0),
                        clause("c2", "MEMBER_CODE", "ATTRIBUTE", false, "\"operator\":\"NOT_EQUAL\",",
                                "\"value\":{\"kind\":\"STRING\",\"raw\":\"ABC\"},", "编码不等于ABC", 5)),
                "{\"type\":\"ALL\",\"children\":[{\"type\":\"CLAUSE\",\"clauseId\":\"c1\"},"
                        + "{\"type\":\"CLAUSE\",\"clauseId\":\"c2\"}]}",
                "[]");
    }

    public static final String INJECTION_UTTERANCE = "编码等于x";

    /** An adversarial string bound as a typed parameter; it must never reach the SQL text. */
    public static String injectionJson(String adversarial) {
        return intentJson(
                List.of(clause("c1", "MEMBER_CODE", "ATTRIBUTE", false, "\"operator\":\"EQUAL\",",
                        "\"value\":{\"kind\":\"STRING\",\"raw\":\"" + adversarial + "\"},", "编码等于x", 0)),
                "{\"type\":\"CLAUSE\",\"clauseId\":\"c1\"}",
                "[]");
    }

    /** A spend clause that is compatible with two permitted money metrics, so resolution is ambiguous. */
    public static String ambiguousSpendJson() {
        return singleClauseJson("过去90天消费超过1000元", "SPEND", "METRIC", "\"operator\":\"EQUAL\",",
                "\"value\":{\"kind\":\"MONEY\",\"raw\":\"1000\"},"
                        + "\"window\":{\"kind\":\"ROLLING\",\"amount\":90,\"unit\":\"DAY\"},"
                        + "\"currency\":{\"mode\":\"EXPLICIT\",\"code\":\"CNY\"},\"aggregation\":\"SUM\",",
                "过去90天消费超过1000元", 0);
    }

    /** A spend clause with no currency anywhere, so resolution requires input. */
    public static String missingCurrencyJson() {
        return singleClauseJson("过去90天消费超过1000元", "SPEND", "METRIC", "\"operator\":\"GREATER_THAN\",",
                "\"value\":{\"kind\":\"MONEY\",\"raw\":\"1000\"},"
                        + "\"window\":{\"kind\":\"ROLLING\",\"amount\":90,\"unit\":\"DAY\"},\"aggregation\":\"SUM\",",
                "过去90天消费超过1000元", 0);
    }

    /** A NOT_EQUAL tier clause whose NULL inclusion is ambiguous. */
    public static String ambiguousNullJson() {
        return singleClauseJson("Gold会员", "MEMBER_TIER", "ATTRIBUTE", "\"operator\":\"NOT_EQUAL\",",
                "\"value\":{\"kind\":\"ENUM\",\"raw\":\"Gold\"},", "Gold会员", 0);
    }

    /** The standard enum clause with a recorded global grouping ambiguity. */
    public static String ambiguousGroupingJson() {
        return intentJson(
                List.of(clause("c1", "MEMBER_TIER", "ATTRIBUTE", false, "\"operator\":\"EQUAL\",",
                        "\"value\":{\"kind\":\"ENUM\",\"raw\":\"Gold\"},", "Gold会员", 0)),
                "{\"type\":\"CLAUSE\",\"clauseId\":\"c1\"}",
                "[\"AMBIGUOUS_GROUPING\"]");
    }

    /** A single clause-level-negated enum tier clause. */
    public static String negatedTierJson() {
        return intentJson(
                List.of(clause("c1", "MEMBER_TIER", "ATTRIBUTE", true, "\"operator\":\"EQUAL\",",
                        "\"value\":{\"kind\":\"ENUM\",\"raw\":\"Gold\"},", "Gold会员", 0)),
                "{\"type\":\"CLAUSE\",\"clauseId\":\"c1\"}",
                "[]");
    }

    /** An expression-level NOT around a clause-level-negated clause: both negations must survive. */
    public static String doubleNegationJson() {
        return intentJson(
                List.of(clause("c1", "MEMBER_TIER", "ATTRIBUTE", true, "\"operator\":\"EQUAL\",",
                        "\"value\":{\"kind\":\"ENUM\",\"raw\":\"Gold\"},", "Gold会员", 0)),
                "{\"type\":\"NOT\",\"child\":{\"type\":\"CLAUSE\",\"clauseId\":\"c1\"}}",
                "[]");
    }

    /** An ENUM attribute clause carrying an IN list of two labels. */
    public static String tierListJson() {
        return singleClauseJson("Gold会员", "MEMBER_TIER", "ATTRIBUTE", "\"operator\":\"IN\",",
                "\"value\":{\"kind\":\"LIST\",\"items\":[{\"kind\":\"ENUM\",\"raw\":\"Gold\"},"
                        + "{\"kind\":\"ENUM\",\"raw\":\"Silver\"}]},",
                "Gold会员", 0);
    }

    /** An INTEGER metric clause carrying a BETWEEN range. */
    public static String countRangeJson() {
        return singleClauseJson("订单数介于1和9", "ORDER_COUNT", "METRIC", "\"operator\":\"BETWEEN\",",
                "\"value\":{\"kind\":\"RANGE\",\"lower\":{\"kind\":\"INTEGER\",\"raw\":\"1\"},"
                        + "\"upper\":{\"kind\":\"INTEGER\",\"raw\":\"9\"},\"includeLower\":true,\"includeUpper\":false},"
                        + "\"window\":{\"kind\":\"LIFETIME\"},\"aggregation\":\"COUNT\",",
                "订单数介于1和9", 0);
    }

    /** An enum clause whose raw label is the exact code {@code GOLD}. */
    public static String enumGoldCodeJson() {
        return singleClauseJson("GOLD会员", "MEMBER_TIER", "ATTRIBUTE", "\"operator\":\"EQUAL\",",
                "\"value\":{\"kind\":\"ENUM\",\"raw\":\"GOLD\"},", "GOLD会员", 0);
    }

    /** An ENUM label that matches no permitted code. */
    public static String unknownEnumJson() {
        return singleClauseJson("Bronze会员", "MEMBER_TIER", "ATTRIBUTE", "\"operator\":\"EQUAL\",",
                "\"value\":{\"kind\":\"ENUM\",\"raw\":\"Bronze\"},", "Bronze会员", 0);
    }

    /** A snapshot whose tier enum makes the raw label {@code GOLD} match two codes. */
    public static MetadataSnapshot ambiguousEnumSnapshot() {
        AttributeDefinition tier = new AttributeDefinition(
                "member.tier",
                MetadataFixtures.memberVersion(),
                SubjectKind.MEMBER,
                "Member Tier",
                SemanticDataType.ENUM,
                null,
                List.of(),
                List.of(ComparisonOperator.EQUAL, ComparisonOperator.NOT_EQUAL,
                        ComparisonOperator.IS_NULL, ComparisonOperator.IS_NOT_NULL),
                List.of(new com.company.campaign.metadata.domain.EnumValueDefinition("GOLD", List.of()),
                        new com.company.campaign.metadata.domain.EnumValueDefinition("PREMIUM", List.of("GOLD"))),
                NullSemantics.EXCLUDED,
                CurrencySemantics.NOT_APPLICABLE,
                List.of(),
                List.of(),
                null);
        return MetadataFixtures.snapshot(
                List.of(tier),
                List.of(netPaidAmountMetric(), orderCountMetric()),
                List.of(MetadataFixtures.ordersRelation()));
    }
}