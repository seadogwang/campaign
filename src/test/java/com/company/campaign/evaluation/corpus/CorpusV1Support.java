package com.company.campaign.evaluation.corpus;

import com.company.campaign.contracts.LocaleTag;
import com.company.campaign.contracts.SchemaVersion;
import com.company.campaign.contracts.SourceSpan;
import com.company.campaign.contracts.TrustedExecutionContextRef;
import com.company.campaign.evaluation.segment.contract.EvaluationVersions;
import com.company.campaign.evaluation.segment.contract.ExpectedIntentSlot;
import com.company.campaign.evaluation.segment.contract.FixtureDescriptor;
import com.company.campaign.evaluation.segment.contract.FixtureRole;
import com.company.campaign.evaluation.segment.contract.MemberIdSet;
import com.company.campaign.metadata.MetadataSchemaVersions;
import com.company.campaign.metadata.domain.AggregateEmptySet;
import com.company.campaign.metadata.domain.AggregateFunction;
import com.company.campaign.metadata.domain.AttributeDefinition;
import com.company.campaign.metadata.domain.ComparisonOperator;
import com.company.campaign.metadata.domain.CurrencySemantics;
import com.company.campaign.metadata.domain.EntityVersionRef;
import com.company.campaign.metadata.domain.EnumValueDefinition;
import com.company.campaign.metadata.domain.EventTimeSemantics;
import com.company.campaign.metadata.domain.MetadataFilter;
import com.company.campaign.metadata.domain.MetadataFilterEntry;
import com.company.campaign.metadata.domain.MetadataSnapshot;
import com.company.campaign.metadata.domain.MetricDefinition;
import com.company.campaign.metadata.domain.NullSemantics;
import com.company.campaign.metadata.domain.RefundTreatment;
import com.company.campaign.metadata.domain.RelationCardinality;
import com.company.campaign.metadata.domain.RelationDefinition;
import com.company.campaign.metadata.domain.RelationEventTimeCapability;
import com.company.campaign.metadata.domain.RelationJoinKind;
import com.company.campaign.metadata.domain.RelationScope;
import com.company.campaign.metadata.domain.SemanticDataType;
import com.company.campaign.metadata.domain.StringCaseSensitivity;
import com.company.campaign.metadata.domain.StringNormalization;
import com.company.campaign.metadata.domain.StringSemantics;
import com.company.campaign.metadata.domain.SubjectKind;
import com.company.campaign.metadata.domain.TimeWindowKind;
import com.company.campaign.metadata.domain.WindowBoundarySemantics;
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
import com.company.campaign.segment.dsl.value.DateTimeValue;
import com.company.campaign.segment.dsl.value.DateValue;
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

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import static com.company.campaign.metadata.domain.ComparisonOperator.BETWEEN;
import static com.company.campaign.metadata.domain.ComparisonOperator.CONTAINS;
import static com.company.campaign.metadata.domain.ComparisonOperator.ENDS_WITH;
import static com.company.campaign.metadata.domain.ComparisonOperator.EQUAL;
import static com.company.campaign.metadata.domain.ComparisonOperator.GREATER_OR_EQUAL;
import static com.company.campaign.metadata.domain.ComparisonOperator.GREATER_THAN;
import static com.company.campaign.metadata.domain.ComparisonOperator.IN;
import static com.company.campaign.metadata.domain.ComparisonOperator.IS_NOT_NULL;
import static com.company.campaign.metadata.domain.ComparisonOperator.IS_NULL;
import static com.company.campaign.metadata.domain.ComparisonOperator.LESS_OR_EQUAL;
import static com.company.campaign.metadata.domain.ComparisonOperator.LESS_THAN;
import static com.company.campaign.metadata.domain.ComparisonOperator.NOT_CONTAINS;
import static com.company.campaign.metadata.domain.ComparisonOperator.NOT_EQUAL;
import static com.company.campaign.metadata.domain.ComparisonOperator.NOT_IN;
import static com.company.campaign.metadata.domain.ComparisonOperator.STARTS_WITH;

/**
 * Shared constants and typed builders for the candidate v1 corpus.
 *
 * <p>Everything here is deterministic: no clock, network, random source or default locale is read. The
 * Metadata snapshot is published by T04, the DSL documents by T05, and every hash is computed by those
 * contracts rather than written by hand.
 */
final class CorpusV1Support {

    static final String CORPUS_ROOT = "evaluation/segment/corpus-v1";
    static final SchemaVersion METADATA_SCHEMA = MetadataSchemaVersions.SUPPORTED;

    static final String SNAPSHOT_ID = "metadata-corpus-v1";
    static final String SNAPSHOT_VERSION = "1";
    static final String TENANT = "tenant-001";
    static final String PROGRAM = "program-001";

    static final Instant AS_OF = Instant.parse("2026-10-01T02:00:00Z");
    static final ZoneId TIMEZONE = ZoneId.of("Asia/Shanghai");
    static final Instant WATERMARK = Instant.parse("2026-10-01T01:55:00Z");

    static final String CONTEXT_PRIMARY = "fixture-context-primary-001";
    static final String CONTEXT_LIMITED = "fixture-context-limited-002";

    static final String PRIMARY_MEMBERS = "fixture-primary-members-001";
    static final String PRIMARY_ORDERS = "fixture-primary-orders-002";
    static final String PRIMARY_EVENTS = "fixture-primary-events-003";

    static final String COUNTER_GROUPING = "fixture-counter-grouping-001";
    static final String COUNTER_NULL = "fixture-counter-null-002";
    static final String COUNTER_BOUNDARY = "fixture-counter-boundary-003";
    static final String COUNTER_WINDOW = "fixture-counter-window-004";
    static final String COUNTER_EMPTY = "fixture-counter-empty-005";
    static final String COUNTER_CURRENCY = "fixture-counter-currency-006";
    static final String COUNTER_EXISTS_SAME_ROW = "fixture-counter-exists-same-007";
    static final String COUNTER_EXISTS_SPLIT_ROW = "fixture-counter-exists-split-008";

    static final List<String> MEMBER_UNIVERSE = List.of(
            "m-001", "m-002", "m-003", "m-004", "m-005", "m-006", "m-007", "m-008",
            "m-009", "m-010", "m-011", "m-012");

    private CorpusV1Support() {
    }

    // --- time, locale and context --------------------------------------------------------------------

    static LocaleTag zh() {
        return new LocaleTag("zh-CN");
    }

    static LocaleTag en() {
        return new LocaleTag("en-US");
    }

    static List<LocaleTag> zhOnly() {
        return List.of(zh());
    }

    static List<LocaleTag> enOnly() {
        return List.of(en());
    }

    static List<LocaleTag> mixed() {
        return List.of(en(), zh());
    }

    static TrustedExecutionContextRef context(String tenant, String program, String scope, LocaleTag locale) {
        return new TrustedExecutionContextRef(
                tenant, program, "user-001", scope, SNAPSHOT_ID, locale, TIMEZONE, "CNY", AS_OF, WATERMARK);
    }

    // --- Metadata snapshot ---------------------------------------------------------------------------

    static MetadataSnapshot metadataSnapshot() {
        EntityVersionRef member = new EntityVersionRef(SubjectKind.MEMBER, METADATA_SCHEMA);
        EntityVersionRef order = new EntityVersionRef(SubjectKind.ORDER, METADATA_SCHEMA);
        EntityVersionRef event = new EntityVersionRef(SubjectKind.EVENT, METADATA_SCHEMA);
        EntityVersionRef account = new EntityVersionRef(SubjectKind.ACCOUNT, METADATA_SCHEMA);
        StringSemantics text = StringSemantics.localeNeutral(
                StringCaseSensitivity.CASE_INSENSITIVE, StringNormalization.NFKC);

        List<AttributeDefinition> correlated = List.of(
                new AttributeDefinition("order.status", order, SubjectKind.ORDER, "Order Status",
                        SemanticDataType.ENUM, null,
                        List.of("订单状态", "订单支付状态"),
                        List.of(EQUAL, NOT_EQUAL, IN, NOT_IN, IS_NULL, IS_NOT_NULL),
                        List.of(new EnumValueDefinition("PAID", List.of("Paid", "已支付")),
                                new EnumValueDefinition("COMPLETED", List.of("Completed", "已完成")),
                                new EnumValueDefinition("REFUNDED", List.of("Refunded", "已退款")),
                                new EnumValueDefinition("CANCELLED", List.of("Cancelled", "已取消"))),
                        NullSemantics.EXCLUDED, CurrencySemantics.NOT_APPLICABLE,
                        List.of("已支付订单"), List.of("会员状态"), null),
                new AttributeDefinition("order.amount", order, SubjectKind.ORDER, "Order Amount",
                        SemanticDataType.MONEY, null,
                        List.of("订单金额", "单笔金额"),
                        List.of(EQUAL, NOT_EQUAL, GREATER_THAN, GREATER_OR_EQUAL, LESS_THAN, LESS_OR_EQUAL,
                                BETWEEN, IN, NOT_IN, IS_NULL, IS_NOT_NULL),
                        List.of(), NullSemantics.EXCLUDED, CurrencySemantics.CONVERT_TO_REPORTING_CURRENCY,
                        List.of("单笔订单金额"), List.of("累计消费金额"), null),
                new AttributeDefinition("order.channel", order, SubjectKind.ORDER, "Order Channel",
                        SemanticDataType.ENUM, null,
                        List.of("下单渠道", "渠道"),
                        List.of(EQUAL, NOT_EQUAL, IN, NOT_IN, IS_NULL, IS_NOT_NULL),
                        List.of(new EnumValueDefinition("ONLINE", List.of("Online", "线上")),
                                new EnumValueDefinition("STORE", List.of("Store", "门店")),
                                new EnumValueDefinition("APP", List.of("App"))),
                        NullSemantics.EXCLUDED, CurrencySemantics.NOT_APPLICABLE,
                        List.of("线上渠道"), List.of("会员来源"), null),
                new AttributeDefinition("order.paid_at", order, SubjectKind.ORDER, "Order Paid At",
                        SemanticDataType.TIMESTAMP, null,
                        List.of("支付时间", "下单时间"),
                        List.of(EQUAL, NOT_EQUAL, GREATER_THAN, GREATER_OR_EQUAL, LESS_THAN, LESS_OR_EQUAL,
                                BETWEEN, IS_NULL, IS_NOT_NULL),
                        List.of(), NullSemantics.EXCLUDED, CurrencySemantics.NOT_APPLICABLE,
                        List.of("昨天支付"), List.of("入会时间"), null),
                new AttributeDefinition("event.type", event, SubjectKind.EVENT, "Event Type",
                        SemanticDataType.ENUM, null,
                        List.of("事件类型", "行为类型"),
                        List.of(EQUAL, NOT_EQUAL, IN, NOT_IN, IS_NULL, IS_NOT_NULL),
                        List.of(new EnumValueDefinition("LOGIN", List.of("Login", "登录")),
                                new EnumValueDefinition("VIEW", List.of("View", "浏览")),
                                new EnumValueDefinition("PURCHASE", List.of("Purchase", "购买"))),
                        NullSemantics.EXCLUDED, CurrencySemantics.NOT_APPLICABLE,
                        List.of("登录事件"), List.of("订单事件"), null),
                new AttributeDefinition("event.occurred_at", event, SubjectKind.EVENT, "Event Occurred At",
                        SemanticDataType.TIMESTAMP, null,
                        List.of("事件时间", "发生时间"),
                        List.of(EQUAL, NOT_EQUAL, GREATER_THAN, GREATER_OR_EQUAL, LESS_THAN, LESS_OR_EQUAL,
                                BETWEEN, IS_NULL, IS_NOT_NULL),
                        List.of(), NullSemantics.EXCLUDED, CurrencySemantics.NOT_APPLICABLE,
                        List.of("今天发生"), List.of("支付时间"), null),
                new AttributeDefinition("account.segment", account, SubjectKind.ACCOUNT, "Account Segment",
                        SemanticDataType.ENUM, null,
                        List.of("档案分层", "账户分层"),
                        List.of(EQUAL, NOT_EQUAL, IN, NOT_IN, IS_NULL, IS_NOT_NULL),
                        List.of(new EnumValueDefinition("VIP", List.of("Vip", "高价值")),
                                new EnumValueDefinition("STANDARD", List.of("Standard", "普通"))),
                        NullSemantics.UNKNOWN_VALUE, CurrencySemantics.NOT_APPLICABLE,
                        List.of("高价值档案"), List.of("会员等级"), null));

        List<AttributeDefinition> memberAttributes = List.of(
                new AttributeDefinition("member.tier", member, SubjectKind.MEMBER, "Member Tier",
                        SemanticDataType.ENUM, null,
                        List.of("会员等级", "卡级", "会员等及"),
                        List.of(EQUAL, NOT_EQUAL, IN, NOT_IN, IS_NULL, IS_NOT_NULL),
                        List.of(new EnumValueDefinition("GOLD", List.of("Gold", "金卡")),
                                new EnumValueDefinition("SILVER", List.of("Silver", "银卡")),
                                new EnumValueDefinition("PLATINUM", List.of("Platinum", "白金卡"))),
                        NullSemantics.UNKNOWN_VALUE, CurrencySemantics.NOT_APPLICABLE,
                        List.of("Gold会员"), List.of("快递会员"), null),
                new AttributeDefinition("member.status", member, SubjectKind.MEMBER, "Membership Status",
                        SemanticDataType.ENUM, null,
                        List.of("会员状态", "状态", "会元状态"),
                        List.of(EQUAL, NOT_EQUAL, IN, NOT_IN, IS_NULL, IS_NOT_NULL),
                        List.of(new EnumValueDefinition("ACTIVE", List.of("Active", "活跃")),
                                new EnumValueDefinition("DORMANT", List.of("Dormant", "沉睡")),
                                new EnumValueDefinition("CHURNED", List.of("Churned", "流失"))),
                        NullSemantics.UNKNOWN_VALUE, CurrencySemantics.NOT_APPLICABLE,
                        List.of("active members"), List.of("已注销"), null),
                new AttributeDefinition("member.city", member, SubjectKind.MEMBER, "Member City",
                        SemanticDataType.STRING, text,
                        List.of("城市", "所在城市", "city name"),
                        List.of(EQUAL, NOT_EQUAL, IN, NOT_IN, CONTAINS, NOT_CONTAINS, STARTS_WITH, ENDS_WITH,
                                IS_NULL, IS_NOT_NULL),
                        List.of(), NullSemantics.UNKNOWN_VALUE, CurrencySemantics.NOT_APPLICABLE,
                        List.of("上海会员"), List.of("上海路"), null),
                new AttributeDefinition("member.name", member, SubjectKind.MEMBER, "Member Name",
                        SemanticDataType.STRING, text,
                        List.of("姓名", "名字", "会员姓名"),
                        List.of(EQUAL, NOT_EQUAL, CONTAINS, NOT_CONTAINS, STARTS_WITH, ENDS_WITH,
                                IS_NULL, IS_NOT_NULL),
                        List.of(), NullSemantics.UNKNOWN_VALUE, CurrencySemantics.NOT_APPLICABLE,
                        List.of("张会员"), List.of("张先生"), null),
                new AttributeDefinition("member.join_date", member, SubjectKind.MEMBER, "Join Date",
                        SemanticDataType.DATE, null,
                        List.of("入会日期", "加入日期", "注册日期"),
                        List.of(EQUAL, NOT_EQUAL, GREATER_THAN, GREATER_OR_EQUAL, LESS_THAN, LESS_OR_EQUAL,
                                BETWEEN, IS_NULL, IS_NOT_NULL),
                        List.of(), NullSemantics.UNKNOWN_VALUE, CurrencySemantics.NOT_APPLICABLE,
                        List.of("2026年入会"), List.of("最近下单"), null),
                new AttributeDefinition("member.order_count", member, SubjectKind.MEMBER, "Lifetime Order Count",
                        SemanticDataType.INTEGER, null,
                        List.of("订单数", "订单数量", "order count"),
                        List.of(EQUAL, NOT_EQUAL, GREATER_THAN, GREATER_OR_EQUAL, LESS_THAN, LESS_OR_EQUAL,
                                BETWEEN, IN, NOT_IN, IS_NULL, IS_NOT_NULL),
                        List.of(), NullSemantics.UNKNOWN_VALUE, CurrencySemantics.NOT_APPLICABLE,
                        List.of("下过三单"), List.of("最近一单"), null),
                new AttributeDefinition("member.last_seen", member, SubjectKind.MEMBER, "Last Seen At",
                        SemanticDataType.TIMESTAMP, null,
                        List.of("最近活跃时间", "最后访问", "last seen"),
                        List.of(EQUAL, NOT_EQUAL, GREATER_THAN, GREATER_OR_EQUAL, LESS_THAN, LESS_OR_EQUAL,
                                BETWEEN, IS_NULL, IS_NOT_NULL),
                        List.of(), NullSemantics.UNKNOWN_VALUE, CurrencySemantics.NOT_APPLICABLE,
                        List.of("昨天来过"), List.of("从未登录"), null),
                new AttributeDefinition("member.email_opt_in", member, SubjectKind.MEMBER, "Email Opt In",
                        SemanticDataType.BOOLEAN, null,
                        List.of("邮件订阅", "订阅邮件"),
                        List.of(EQUAL, NOT_EQUAL, IS_NULL, IS_NOT_NULL),
                        List.of(), NullSemantics.UNKNOWN_VALUE, CurrencySemantics.NOT_APPLICABLE,
                        List.of("订阅了邮件"), List.of("短信订阅"), null),
                new AttributeDefinition("member.loyalty_points", member, SubjectKind.MEMBER, "Loyalty Points",
                        SemanticDataType.INTEGER, null,
                        List.of("积分", "会员积分"),
                        List.of(EQUAL, NOT_EQUAL, GREATER_THAN, GREATER_OR_EQUAL, LESS_THAN, LESS_OR_EQUAL,
                                BETWEEN, IN, NOT_IN, IS_NULL, IS_NOT_NULL),
                        List.of(), NullSemantics.UNKNOWN_VALUE, CurrencySemantics.NOT_APPLICABLE,
                        List.of("积分超过一千"), List.of("消费金额"), null),
                new AttributeDefinition("member.phone_masked", member, SubjectKind.MEMBER, "Masked Phone",
                        SemanticDataType.STRING, text,
                        List.of("手机号", "手机号码", "phone"),
                        List.of(EQUAL, NOT_EQUAL, IS_NULL, IS_NOT_NULL),
                        List.of(), NullSemantics.UNKNOWN_VALUE, CurrencySemantics.NOT_APPLICABLE,
                        List.of("手机号已脱敏"), List.of("公开手机号"),
                        "Restricted: only visible inside an authorized DataScope and never returned in full."),
                new AttributeDefinition("member.tenant_scope", member, SubjectKind.MEMBER, "Tenant Scope",
                        SemanticDataType.STRING, text,
                        List.of("租户范围", "租户标识"),
                        List.of(EQUAL, NOT_EQUAL, IS_NULL, IS_NOT_NULL),
                        List.of(), NullSemantics.UNKNOWN_VALUE, CurrencySemantics.NOT_APPLICABLE,
                        List.of("本租户"), List.of("其他租户"),
                        "Ownership marker; a cross-tenant request is denied rather than filtered."),
                new AttributeDefinition("member.nickname", member, SubjectKind.MEMBER, "Nickname",
                        SemanticDataType.STRING, text,
                        List.of("昵称", "花名"),
                        List.of(EQUAL, NOT_EQUAL, CONTAINS, NOT_CONTAINS, STARTS_WITH, ENDS_WITH,
                                IS_NULL, IS_NOT_NULL),
                        List.of(), NullSemantics.MATCHES_NULL, CurrencySemantics.NOT_APPLICABLE,
                        List.of("昵称可以匹配空值"), List.of("昵称必填"), null));

        List<AttributeDefinition> attributes = new ArrayList<>(memberAttributes);
        attributes.addAll(correlated);

        List<RelationDefinition> relations = List.of(
                new RelationDefinition("member.orders", "Member Orders", List.of("订单", "orders"),
                        SubjectKind.MEMBER, member, SubjectKind.ORDER, order,
                        RelationCardinality.ONE_TO_MANY,
                        new RelationScope(RelationJoinKind.CORRELATED_BY_SOURCE_KEY, "member.id", "order.member_id"),
                        new RelationEventTimeCapability(EventTimeSemantics.EVENT_TIME, "order.paid_at"),
                        NullSemantics.EXCLUDED, com.company.campaign.metadata.domain.EmptySetBehavior.NULL,
                        List.of("会员订单"), List.of("订单明细"), "Atomic relation; composition belongs to T09."),
                new RelationDefinition("member.events", "Member Events", List.of("事件", "events"),
                        SubjectKind.MEMBER, member, SubjectKind.EVENT, event,
                        RelationCardinality.ONE_TO_MANY,
                        new RelationScope(RelationJoinKind.CORRELATED_BY_SOURCE_KEY, "member.id", "event.member_id"),
                        new RelationEventTimeCapability(EventTimeSemantics.EVENT_TIME, "event.occurred_at"),
                        NullSemantics.EXCLUDED, com.company.campaign.metadata.domain.EmptySetBehavior.NULL,
                        List.of("会员事件"), List.of("页面浏览明细"), "Atomic relation; composition belongs to T09."),
                new RelationDefinition("member.profile", "Member Profile", List.of("档案", "profile"),
                        SubjectKind.MEMBER, member, SubjectKind.ACCOUNT, account,
                        RelationCardinality.ONE_TO_ONE,
                        new RelationScope(RelationJoinKind.CORRELATED_BY_SOURCE_KEY, "member.id", "account.member_id"),
                        null, NullSemantics.EXCLUDED,
                        com.company.campaign.metadata.domain.EmptySetBehavior.NULL,
                        List.of("会员档案"), List.of("订单档案"), "One-to-one profile relation."));

        List<MetricDefinition> metrics = List.of(
                moneyMetric("order.net_paid_amount", "Net Paid Amount", AggregateFunction.SUM,
                        AggregateEmptySet.zero(SemanticDataType.MONEY), RefundTreatment.SUBTRACT,
                        CurrencySemantics.CONVERT_TO_REPORTING_CURRENCY,
                        new MetadataFilter(List.of(
                                new MetadataFilterEntry("status", List.of("PAID", "COMPLETED")),
                                new MetadataFilterEntry("orderType", List.of("PURCHASE")))),
                        List.of("净消费金额", "消费"), List.of("总订单金额", "订单金额"),
                        "Net of refunds over the filtered order set; not the gross or single-order amount."),
                moneyMetric("order.gross_amount", "Gross Order Amount", AggregateFunction.SUM,
                        AggregateEmptySet.zero(SemanticDataType.MONEY), RefundTreatment.INCLUDE,
                        CurrencySemantics.CONVERT_TO_REPORTING_CURRENCY,
                        MetadataFilter.empty(),
                        List.of("总订单金额", "订单金额"), List.of("净消费金额", "退款金额"),
                        "Includes refunds; a conflicting candidate for net spend."),
                moneyMetric("order.avg_paid_amount", "Average Paid Amount", AggregateFunction.AVG,
                        AggregateEmptySet.nullValue(), RefundTreatment.SUBTRACT,
                        CurrencySemantics.CONVERT_TO_REPORTING_CURRENCY, MetadataFilter.empty(),
                        List.of("平均客单价", "客单价"), List.of("订单总额", "单笔金额"),
                        "Average across paid orders; NULL when there are no rows."),
                moneyMetric("order.min_paid_amount", "Minimum Paid Amount", AggregateFunction.MIN,
                        AggregateEmptySet.nullValue(), RefundTreatment.SUBTRACT,
                        CurrencySemantics.CONVERT_TO_REPORTING_CURRENCY, MetadataFilter.empty(),
                        List.of("最小订单金额", "最低消费"), List.of("最大订单金额", "订单总额"),
                        "Smallest paid order; NULL when there are no rows."),
                moneyMetric("order.max_paid_amount", "Maximum Paid Amount", AggregateFunction.MAX,
                        AggregateEmptySet.nullValue(), RefundTreatment.SUBTRACT,
                        CurrencySemantics.CONVERT_TO_REPORTING_CURRENCY, MetadataFilter.empty(),
                        List.of("最大订单金额", "最高消费"), List.of("最小订单金额", "平均消费"),
                        "Largest paid order; NULL when there are no rows."),
                moneyMetric("order.latest_paid_amount", "Latest Paid Amount", AggregateFunction.MAX,
                        AggregateEmptySet.nullValue(), RefundTreatment.SUBTRACT,
                        CurrencySemantics.CONVERT_TO_REPORTING_CURRENCY, MetadataFilter.empty(),
                        List.of("最近一单金额", "上一单金额"), List.of("累计消费", "平均消费"),
                        "Single-event metric: the amount of the latest paid order, not an aggregate over orders."),
                moneyMetric("order.single_currency_amount", "Single Currency Amount", AggregateFunction.SUM,
                        AggregateEmptySet.zero(SemanticDataType.MONEY), RefundTreatment.SUBTRACT,
                        CurrencySemantics.SINGLE_CURRENCY_ONLY, MetadataFilter.empty(),
                        List.of("单币种消费"), List.of("混合币种消费", "折算消费"),
                        "Single-currency only: a mixed-currency order set is rejected rather than converted."),
                countMetric("order.count", "Order Count", SubjectKind.MEMBER, "member.orders",
                        "order.paid_at", List.of("订单数", "订单数量"), List.of("事件数", "消费金额")),
                countMetric("event.count", "Event Count", SubjectKind.MEMBER, "member.events",
                        "event.occurred_at", List.of("事件数", "行为次数"), List.of("订单数", "会话数")));

        return MetadataSnapshot.publish(
                METADATA_SCHEMA, SNAPSHOT_ID, SNAPSHOT_VERSION, TENANT, PROGRAM, attributes, metrics, relations);
    }

    private static MetricDefinition moneyMetric(
            String id, String displayName, AggregateFunction aggregation, AggregateEmptySet emptySet,
            RefundTreatment refunds, CurrencySemantics currency, MetadataFilter filter,
            List<String> aliases, List<String> negativeAliases, String disambiguation) {

        EntityVersionRef member = new EntityVersionRef(SubjectKind.MEMBER, METADATA_SCHEMA);
        return new MetricDefinition(id, member, SubjectKind.MEMBER, displayName,
                SemanticDataType.MONEY, null, aliases,
                List.of(EQUAL, NOT_EQUAL, IN, NOT_IN, GREATER_THAN, GREATER_OR_EQUAL, LESS_THAN,
                        LESS_OR_EQUAL, BETWEEN, IS_NULL, IS_NOT_NULL),
                aggregation, "member.orders", "order.paid_at", EventTimeSemantics.EVENT_TIME,
                filter, refunds, currency, List.of(TimeWindowKind.ROLLING, TimeWindowKind.LIFETIME,
                        TimeWindowKind.FIXED),
                WindowBoundarySemantics.INCLUSIVE_START_EXCLUSIVE_END, NullSemantics.EXCLUDED, emptySet,
                List.of(displayName), negativeAliases, disambiguation);
    }

    private static MetricDefinition countMetric(
            String id, String displayName, SubjectKind subject, String relationId, String eventTimeField,
            List<String> aliases, List<String> negativeAliases) {

        EntityVersionRef member = new EntityVersionRef(subject, METADATA_SCHEMA);
        return new MetricDefinition(id, member, subject, displayName,
                SemanticDataType.INTEGER, null, aliases,
                List.of(EQUAL, NOT_EQUAL, GREATER_THAN, GREATER_OR_EQUAL, LESS_THAN, LESS_OR_EQUAL,
                        BETWEEN, IN, NOT_IN, IS_NULL, IS_NOT_NULL),
                AggregateFunction.COUNT, relationId, eventTimeField, EventTimeSemantics.EVENT_TIME,
                MetadataFilter.empty(), RefundTreatment.IGNORE, CurrencySemantics.NOT_APPLICABLE,
                List.of(TimeWindowKind.ROLLING, TimeWindowKind.LIFETIME, TimeWindowKind.FIXED),
                WindowBoundarySemantics.INCLUSIVE_START_EXCLUSIVE_END, NullSemantics.EXCLUDED,
                AggregateEmptySet.zero(SemanticDataType.INTEGER),
                List.of(displayName), negativeAliases, "Zero when there are no rows, never NULL.");
    }

    // --- Fixture catalog -----------------------------------------------------------------------------

    static List<FixtureDescriptor> fixtureDescriptors() {
        List<FixtureDescriptor> fixtures = new ArrayList<>();
        fixtures.add(executionContext(CONTEXT_PRIMARY, CONTEXT_PAYLOAD_PRIMARY));
        fixtures.add(executionContext(CONTEXT_LIMITED, CONTEXT_PAYLOAD_LIMITED));
        fixtures.add(memberFixture(PRIMARY_MEMBERS, FixtureRole.PRIMARY));
        fixtures.add(memberFixture(PRIMARY_ORDERS, FixtureRole.PRIMARY));
        fixtures.add(memberFixture(PRIMARY_EVENTS, FixtureRole.PRIMARY));
        fixtures.add(memberFixture(COUNTER_GROUPING, FixtureRole.COUNTEREXAMPLE));
        fixtures.add(memberFixture(COUNTER_NULL, FixtureRole.COUNTEREXAMPLE));
        fixtures.add(memberFixture(COUNTER_BOUNDARY, FixtureRole.COUNTEREXAMPLE));
        fixtures.add(memberFixture(COUNTER_WINDOW, FixtureRole.COUNTEREXAMPLE));
        fixtures.add(memberFixture(COUNTER_EMPTY, FixtureRole.COUNTEREXAMPLE));
        fixtures.add(memberFixture(COUNTER_CURRENCY, FixtureRole.COUNTEREXAMPLE));
        fixtures.add(memberFixture(COUNTER_EXISTS_SAME_ROW, FixtureRole.COUNTEREXAMPLE));
        fixtures.add(memberFixture(COUNTER_EXISTS_SPLIT_ROW, FixtureRole.COUNTEREXAMPLE));
        return fixtures;
    }

    /** Context payloads are host-shaped T01 references, one authorized and one limited/cross-tenant. */
    static final TrustedExecutionContextRef CONTEXT_PAYLOAD_PRIMARY =
            context(TENANT, PROGRAM, "scope-008", zh());
    static final TrustedExecutionContextRef CONTEXT_PAYLOAD_LIMITED =
            context("tenant-002", "program-002", "scope-limited-002", en());

    private static FixtureDescriptor executionContext(String fixtureId, TrustedExecutionContextRef payload) {
        byte[] bytes = new com.company.campaign.shared.canonical.CanonicalJson().write(payload);
        return new FixtureDescriptor(fixtureId, "1", FixtureRole.EXECUTION_CONTEXT,
                CORPUS_ROOT + "/fixtures/payloads/" + fixtureId + ".json",
                ContentHash.sha256(bytes), AS_OF, TIMEZONE, WATERMARK, new MemberIdSet(List.of()));
    }

    private static FixtureDescriptor memberFixture(String fixtureId, FixtureRole role) {
        byte[] bytes = fixturePayload(fixtureId, role, datasetName(fixtureId));
        return new FixtureDescriptor(fixtureId, "1", role,
                CORPUS_ROOT + "/fixtures/payloads/" + fixtureId + ".json",
                ContentHash.sha256(bytes), AS_OF, TIMEZONE, WATERMARK, new MemberIdSet(MEMBER_UNIVERSE));
    }

    /**
     * A compact, bounded synthetic dataset. The schema is documented in the corpus README; T03's loader
     * treats it as opaque bytes, and T09 owns its query semantics.
     */
    /** Stable dataset label derived from the fixture id, so the catalog hash and the payload agree. */
    static String datasetName(String fixtureId) {
        int marker = fixtureId.lastIndexOf('-');
        return marker < 0 ? fixtureId : fixtureId.substring(0, marker).replace('-', '_');
    }

    static byte[] fixturePayload(String fixtureId, FixtureRole role, String dataset) {
        StringBuilder members = new StringBuilder();
        for (int index = 0; index < MEMBER_UNIVERSE.size(); index++) {
            if (index > 0) {
                members.append(',');
            }
            String tier = switch (index % 3) {
                case 0 -> "GOLD";
                case 1 -> "SILVER";
                default -> "PLATINUM";
            };
            members.append("{\"city\":\"City-").append((index % 4) + 1)
                    .append("\",\"joinDate\":\"2026-0").append((index % 9) + 1).append("-15")
                    .append("\",\"memberId\":\"m-")
                    .append(String.format(java.util.Locale.ROOT, "%03d", index + 1))
                    .append("\",\"status\":\"").append(index % 5 == 0 ? "DORMANT" : "ACTIVE")
                    .append("\",\"tier\":\"").append(tier).append("\"}");
        }
        String payload = "{\"asOf\":\"" + AS_OF + "\",\"currencies\":[\"CNY\",\"USD\"],"
                + "\"dataset\":\"" + dataset + "\","
                + "\"events\":[{\"eventId\":\"e-001\",\"memberId\":\"m-001\",\"occurredAt\":\"2026-09-30T10:00:00Z\","
                + "\"type\":\"LOGIN\"},{\"eventId\":\"e-002\",\"memberId\":\"m-002\","
                + "\"occurredAt\":\"2026-09-29T10:00:00Z\",\"type\":\"VIEW\"}],"
                + "\"fixtureId\":\"" + fixtureId + "\","
                + "\"members\":[" + members + "],"
                + "\"orders\":[{\"amount\":\"120.00\",\"currency\":\"CNY\",\"memberId\":\"m-001\","
                + "\"orderId\":\"o-001\",\"paidAt\":\"2026-09-30T09:00:00Z\",\"refunded\":\"0.00\","
                + "\"status\":\"PAID\"},{\"amount\":\"80.00\",\"currency\":\"CNY\",\"memberId\":\"m-002\","
                + "\"orderId\":\"o-002\",\"paidAt\":\"2026-09-28T09:00:00Z\",\"refunded\":\"20.00\","
                + "\"status\":\"COMPLETED\"}],"
                + "\"refunds\":[{\"amount\":\"20.00\",\"orderId\":\"o-002\"}],"
                + "\"role\":\"" + role.name() + "\","
                + "\"statuses\":[\"ACTIVE\",\"DORMANT\",\"CHURNED\"],"
                + "\"watermark\":\"" + WATERMARK + "\"}";
        return payload.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    // --- DSL helpers ---------------------------------------------------------------------------------

    static MetadataBinding binding(ContentHash snapshotPayloadHash) {
        return new MetadataBinding(SNAPSHOT_ID, SNAPSHOT_VERSION, METADATA_SCHEMA, snapshotPayloadHash);
    }

    static SegmentDsl publish(MetadataBinding binding, Predicate predicate) {
        return SegmentDsl.publish(SegmentDslSchemaVersions.SUPPORTED, SubjectKind.MEMBER, binding, predicate);
    }

    static AttributePredicate attr(String nodeId, String attributeId, ComparisonOperator operator,
            TypedValue value) {
        return new AttributePredicate(nodeId, attributeId, operator, ConditionValue.of(value));
    }

    static AttributePredicate attrNull(String nodeId, String attributeId, ComparisonOperator operator) {
        return new AttributePredicate(nodeId, attributeId, operator, ConditionValue.none());
    }

    static MetricPredicate metric(String nodeId, String metricId, TimeWindow window,
            ComparisonOperator operator, TypedValue value) {
        return new MetricPredicate(nodeId, metricId, window, operator, ConditionValue.of(value));
    }

    static MetricPredicate metricNull(String nodeId, String metricId, TimeWindow window,
            ComparisonOperator operator) {
        return new MetricPredicate(nodeId, metricId, window, operator, ConditionValue.none());
    }

    static ExistsPredicate exists(String nodeId, String relationId, TimeWindow window, Predicate nested) {
        return new ExistsPredicate(nodeId, relationId, window, nested);
    }

    static GroupPredicate all(String nodeId, Predicate... children) {
        return new GroupPredicate(nodeId, PredicateType.ALL, List.of(children));
    }

    static GroupPredicate any(String nodeId, Predicate... children) {
        return new GroupPredicate(nodeId, PredicateType.ANY, List.of(children));
    }

    static NotPredicate not(String nodeId, Predicate child) {
        return new NotPredicate(nodeId, child);
    }

    static ScalarValue enumValue(String code) {
        return new EnumValue(code);
    }

    static ScalarValue str(String value) {
        return new StringValue(value);
    }

    static ScalarValue integer(long value) {
        return new IntegerValue(value);
    }

    static ScalarValue decimal(String value) {
        return new DecimalValue(value);
    }

    static ScalarValue bool(boolean value) {
        return new BooleanValue(value);
    }

    static ScalarValue date(String value) {
        return new DateValue(value);
    }

    static ScalarValue dateTime(String value) {
        return new DateTimeValue(value);
    }

    static ScalarValue money(String amount, String currency) {
        return new MoneyValue(amount, currency);
    }

    static ListValue listOf(ScalarValue... items) {
        return new ListValue(List.of(items));
    }

    static RangeValue range(ScalarValue lower, ScalarValue upper, boolean includeLower, boolean includeUpper) {
        return new RangeValue(lower, upper, includeLower, includeUpper);
    }

    static TimeWindow rolling(int amount, WindowUnit unit) {
        return new RollingWindow(amount, unit);
    }

    static TimeWindow lifetime() {
        return new LifetimeWindow();
    }

    static TimeWindow fixed(String start, String end) {
        return new FixedWindow(start, end);
    }

    static WindowUnit days() {
        return WindowUnit.DAY;
    }

    static WindowUnit weeks() {
        return WindowUnit.WEEK;
    }

    /** Builds a grounded Intent slot, locating the quoted text in the utterance for its source span. */
    static ExpectedIntentSlot slot(String slotId, String concept, String operator, String utterance,
            String slotText, com.fasterxml.jackson.databind.JsonNode value) {
        int start = utterance.indexOf(slotText);
        if (start < 0) {
            throw new IllegalArgumentException(
                    "Slot text '" + slotText + "' does not occur in utterance: " + utterance);
        }
        return new ExpectedIntentSlot(slotId, concept, operator, true,
                new SourceSpan(slotText, start, start + slotText.length()), value);
    }

    /** A slot that carries no user text, for a default the resolver supplies rather than reads. */
    static ExpectedIntentSlot ungrounded(String slotId, String concept, String operator,
            com.fasterxml.jackson.databind.JsonNode value) {
        return new ExpectedIntentSlot(slotId, concept, operator, false, null, value);
    }

    static com.fasterxml.jackson.databind.JsonNode jsonValue(String text) {
        return com.fasterxml.jackson.databind.node.TextNode.valueOf(text);
    }

    static com.fasterxml.jackson.databind.JsonNode jsonNumber(long value) {
        return com.fasterxml.jackson.databind.node.LongNode.valueOf(value);
    }

    static SchemaVersion currentVersion() {
        return EvaluationVersions.CURRENT;
    }
}