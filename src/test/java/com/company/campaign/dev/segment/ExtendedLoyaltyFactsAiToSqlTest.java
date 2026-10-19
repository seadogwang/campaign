package com.company.campaign.dev.segment;

import com.company.campaign.builder.segment.DevelopmentAiToSqlService;
import com.company.campaign.builder.segment.SegmentIntentModelPort;
import com.company.campaign.segment.validation.testsupport.SegmentValidationFixtures;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class ExtendedLoyaltyFactsAiToSqlTest {

    private static final Instant AS_OF = Instant.parse("2026-10-04T12:00:00Z");

    @Test
    void returnPaidAmountUsesReturnDirectionInsteadOfPositiveOrders() {
        String utterance = "找近 180 天有过退单、且退单实付金额合计 ≥ 500 的顾客。";
        String source = "退单实付金额合计 ≥ 500";
        String intent = root(
                metricClause(
                        "returns", "RETURN_PAID_AMOUNT", "GREATER_OR_EQUAL", "MONEY", "500",
                        "{\"kind\":\"ROLLING\",\"amount\":180,\"unit\":\"DAY\"}",
                        "SUM", source, utterance, "order.return_paid_amount_sum"),
                "{\"type\":\"CLAUSE\",\"clauseId\":\"returns\"}");

        var result = invoke(utterance, intent);

        assertThat(result.isCompiled())
                .as("%s / %s / %s", result.disposition(), result.failureCode(), result.clarification())
                .isTrue();
        assertThat(result.sql())
                .contains("\"public\".\"seg_order\"")
                .contains("\"paid_amount\"")
                .contains("\"order_type\" IN (?)")
                .doesNotContain("POSITIVE")
                .doesNotContain("退单");
        assertThat(result.parameters())
                .filteredOn(parameter -> !parameter.redacted())
                .extracting(parameter -> parameter.canonicalValue())
                .contains("RETURN", "500", "CNY", "0");
    }

    @Test
    void successfulCouponAndNeverRedeemedUseIndependentFacts() {
        String utterance = "找成功发过券、但从来没有兑过礼的顾客";
        String coupon = metricClause(
                "coupon", "SUCCESSFUL_COUPON_SEND_COUNT", "GREATER_THAN", "INTEGER", "0",
                "{\"kind\":\"LIFETIME\"}", "COUNT", "成功发过券", utterance,
                "coupon.successful_send_count");
        String redemption = metricClause(
                "redemption", "GIFT_REDEMPTION_COUNT", "GREATER_THAN", "INTEGER", "0",
                "{\"kind\":\"LIFETIME\"}", "COUNT", "从来没有兑过礼", utterance,
                "redemption.count").replace("\"negated\":false", "\"negated\":true");
        String intent = root(
                coupon + "," + redemption,
                "{\"type\":\"ALL\",\"children\":["
                        + "{\"type\":\"CLAUSE\",\"clauseId\":\"coupon\"},"
                        + "{\"type\":\"CLAUSE\",\"clauseId\":\"redemption\"}]}");

        var result = invoke(utterance, intent);

        assertThat(result.isCompiled())
                .as("%s / %s / %s", result.disposition(), result.failureCode(), result.clarification())
                .isTrue();
        assertThat(result.sql())
                .contains("\"public\".\"seg_coupon_send\"")
                .contains("\"public\".\"seg_redemption\"")
                .contains("\"send_status\" IN (?)")
                .contains("\"record_type\" IN (?)")
                .doesNotContain("发过券")
                .doesNotContain("兑过礼");
        assertThat(result.parameters())
                .filteredOn(parameter -> !parameter.redacted())
                .extracting(parameter -> parameter.canonicalValue())
                .contains("SUCCESS", "NORMAL")
                .doesNotContain("0");
    }

    @Test
    void preferredStoreOrLatestPositiveOrderServiceBaPreservesOrGrouping() {
        String utterance = "找偏好门店名称是门店 A，或最近一笔订单的服务BA编码是 B001 的顾客。";
        String preferredStore = attributeClause(
                "store", "PREFERRED_STORE_NAME", "门店 A", "偏好门店名称是门店 A",
                utterance, "member.preferred_store_name");
        String serviceBa = attributeClause(
                "ba", "LATEST_POSITIVE_ORDER_SERVICE_BA_CODE", "B001",
                "最近一笔订单的服务BA编码是 B001", utterance,
                "member.latest_positive_order_service_ba_code");
        String intent = root(
                preferredStore + "," + serviceBa,
                "{\"type\":\"ANY\",\"children\":["
                        + "{\"type\":\"CLAUSE\",\"clauseId\":\"store\"},"
                        + "{\"type\":\"CLAUSE\",\"clauseId\":\"ba\"}]}");

        var result = invoke(utterance, intent);

        assertThat(result.isCompiled())
                .as("%s / %s / %s", result.disposition(), result.failureCode(), result.clarification())
                .isTrue();
        assertThat(result.sql())
                .contains("\"public\".\"seg_member\"")
                .contains("\"preferred_store_name\" = ?")
                .contains("\"latest_positive_order_employee_code\" = ?")
                .contains(" OR ")
                .doesNotContain("门店 A")
                .doesNotContain("B001");
        assertThat(result.parameters())
                .filteredOn(parameter -> !parameter.redacted())
                .extracting(parameter -> parameter.canonicalValue())
                .contains("门店 A", "B001");
    }

    @Test
    void managementStoreAndLatestPositiveOrderStoreUseIndependentMemberAttributes() {
        String utterance = "找管理门店是 A、但最近下单门店不是 A 的顾客。";
        String managementStore = attributeClause(
                "management-store", "MANAGEMENT_STORE_CODE", "A", "管理门店是 A",
                utterance, "member.management_store_code");
        String latestOrderStore = attributeClause(
                "latest-order-store", "LATEST_POSITIVE_ORDER_STORE_CODE", "A",
                "最近下单门店不是 A", utterance,
                "member.latest_positive_order_store_code")
                .replace("\"operator\":\"EQUAL\"", "\"operator\":\"NOT_EQUAL\"");
        String intent = root(
                managementStore + "," + latestOrderStore,
                "{\"type\":\"ALL\",\"children\":["
                        + "{\"type\":\"CLAUSE\",\"clauseId\":\"management-store\"},"
                        + "{\"type\":\"CLAUSE\",\"clauseId\":\"latest-order-store\"}]}");

        var result = invoke(utterance, intent);

        assertThat(result.isCompiled())
                .as("%s / %s / %s", result.disposition(), result.failureCode(), result.clarification())
                .isTrue();
        assertThat(result.sql())
                .contains("\"management_store_code\" = ?")
                .contains("\"latest_positive_order_store_code\" <> ?")
                .contains(" AND ")
                .doesNotContain("order.store_code")
                .doesNotContain("门店是 A");
        assertThat(result.parameters())
                .filteredOn(parameter -> !parameter.redacted())
                .extracting(parameter -> parameter.canonicalValue())
                .containsExactlyInAnyOrder("A", "A");
    }

    @Test
    void upcomingBirthdayUsesDerivedNextBirthdayDate() {
        String utterance = "找生日在未来 7 天内的顾客。";
        String source = "生日在未来 7 天内";
        int start = utterance.indexOf(source);
        String clause = "{\"clauseId\":\"birthday\","
                + "\"concept\":\"NEXT_BIRTHDAY_ON\",\"definitionKind\":\"ATTRIBUTE\","
                + "\"negated\":false,\"operator\":\"BETWEEN\",\"value\":{\"kind\":\"RANGE\","
                + "\"lower\":{\"kind\":\"DATE\",\"raw\":\"2026-10-04\"},"
                + "\"upper\":{\"kind\":\"DATE\",\"raw\":\"2026-10-11\"},"
                + "\"includeLower\":true,\"includeUpper\":false},"
                + "\"provenance\":{\"type\":\"SOURCE_SPAN\",\"text\":\"" + source
                + "\",\"start\":" + start + ",\"end\":" + (start + source.length()) + "},"
                + "\"candidateHints\":[\"member.next_birthday_on\"],\"assumptions\":[]}";

        var result = invoke(utterance, root(
                clause, "{\"type\":\"CLAUSE\",\"clauseId\":\"birthday\"}"));

        assertThat(result.isCompiled())
                .as("%s / %s / %s", result.disposition(), result.failureCode(), result.clarification())
                .isTrue();
        assertThat(result.sql())
                .contains("\"next_birthday_on\" >= ?")
                .contains("\"next_birthday_on\" < ?")
                .doesNotContain("birth_date")
                .doesNotContain("birth_month");
        assertThat(result.parameters())
                .filteredOn(parameter -> !parameter.redacted())
                .extracting(parameter -> parameter.canonicalValue())
                .containsExactlyInAnyOrder("2026-10-04", "2026-10-11");
    }

    @Test
    void recentTierDowngradeUsesDerivedChangeDirection() {
        String utterance = "近 90 天发生降级";
        String directionSource = "降级";
        int directionStart = utterance.indexOf(directionSource);
        String clause = "{\"clauseId\":\"tier-change\",\"concept\":\"TIER_CHANGE_COUNT\","
                + "\"definitionKind\":\"METRIC\",\"negated\":false,"
                + "\"operator\":\"GREATER_THAN\",\"value\":{\"kind\":\"INTEGER\",\"raw\":\"0\"},"
                + "\"window\":{\"kind\":\"ROLLING\",\"amount\":90,\"unit\":\"DAY\"},"
                + "\"aggregation\":\"COUNT\",\"scopeFilters\":[{"
                + "\"filterId\":\"direction\",\"concept\":\"TIER_CHANGE_DIRECTION\","
                + "\"domain\":\"UNSPECIFIED\",\"identifierKind\":\"UNSPECIFIED\","
                + "\"operator\":\"EQUAL\",\"value\":{\"kind\":\"ENUM\",\"raw\":\"降级\"},"
                + "\"provenance\":{\"type\":\"SOURCE_SPAN\",\"text\":\"" + directionSource
                + "\",\"start\":" + directionStart + ",\"end\":"
                + (directionStart + directionSource.length()) + "},"
                + "\"candidateHints\":[\"tier_history.change_direction\"],\"assumptions\":[]}],"
                + "\"provenance\":{\"type\":\"SOURCE_SPAN\",\"text\":\"" + utterance
                + "\",\"start\":0,\"end\":" + utterance.length() + "},"
                + "\"candidateHints\":[\"tier_history.change_count\"],\"assumptions\":[]}";

        var result = invoke(utterance, root(
                clause, "{\"type\":\"CLAUSE\",\"clauseId\":\"tier-change\"}"));

        assertThat(result.isCompiled())
                .as("%s / %s / %s", result.disposition(), result.failureCode(), result.clarification())
                .isTrue();
        assertThat(result.sql())
                .contains("\"public\".\"seg_member_tier_history\"")
                .contains("\"effective_from\" >= ?")
                .contains("\"effective_from\" < ?")
                .contains("\"change_direction\" = ?")
                .doesNotContain("category_code");
        assertThat(result.parameters())
                .filteredOn(parameter -> !parameter.redacted())
                .extracting(parameter -> parameter.canonicalValue())
                .contains("DOWNGRADE");
    }

    @Test
    void recentRegistrationAndNeverPositiveOrderKeepBothTimeBoundsAndLifetimeZero() {
        String utterance = "找注册时间在近90天、但从未下过正单的顾客。";
        String registrationSource = "注册时间在近90天";
        int registrationStart = utterance.indexOf(registrationSource);
        String registration = "{\"clauseId\":\"registration\","
                + "\"concept\":\"REGISTRATION_TIME\",\"definitionKind\":\"ATTRIBUTE\","
                + "\"negated\":false,\"operator\":\"BETWEEN\",\"value\":{\"kind\":\"RANGE\","
                + "\"lower\":{\"kind\":\"TIMESTAMP\",\"raw\":\"2026-07-06T12:00:00Z\"},"
                + "\"upper\":{\"kind\":\"TIMESTAMP\",\"raw\":\"2026-10-04T12:00:00Z\"},"
                + "\"includeLower\":true,\"includeUpper\":false},"
                + "\"provenance\":{\"type\":\"SOURCE_SPAN\",\"text\":\"" + registrationSource
                + "\",\"start\":" + registrationStart + ",\"end\":"
                + (registrationStart + registrationSource.length()) + "},"
                + "\"candidateHints\":[\"member.registered_at\"],\"assumptions\":[]}";
        String positiveOrders = metricClause(
                "positive-orders", "POSITIVE_ORDER_COUNT", "EQUAL", "INTEGER", "0",
                "{\"kind\":\"LIFETIME\"}", "COUNT", "从未下过正单", utterance,
                "order.positive_count");
        String intent = root(
                registration + "," + positiveOrders,
                "{\"type\":\"ALL\",\"children\":["
                        + "{\"type\":\"CLAUSE\",\"clauseId\":\"registration\"},"
                        + "{\"type\":\"CLAUSE\",\"clauseId\":\"positive-orders\"}]}");

        var result = invoke(utterance, intent);

        assertThat(result.isCompiled())
                .as("%s / %s / %s", result.disposition(), result.failureCode(), result.clarification())
                .isTrue();
        assertThat(result.sql())
                .contains("\"registered_at\" >= ? AND \"m0\".\"registered_at\" < ?")
                .contains("\"public\".\"seg_order\"")
                .contains("NOT EXISTS (SELECT")
                .contains("\"order_type\" IN (?)")
                .doesNotContain("COUNT(*)")
                .doesNotContain("注册时间")
                .doesNotContain("正单");
        assertThat(result.parameters())
                .filteredOn(parameter -> !parameter.redacted())
                .extracting(parameter -> parameter.canonicalValue())
                .contains(
                        "2026-07-06T12:00:00Z",
                        "2026-10-04T12:00:00Z",
                        "POSITIVE");
    }

    @Test
    void futureTierEndDateUsesAHalfOpenTemporalRange() {
        String utterance = "找当前等级结束日期在未来 30 天内的顾客。";
        var result = invoke(utterance, tierEndIntent(utterance, false));

        assertThat(result.isCompiled())
                .as("%s / %s / %s", result.disposition(), result.failureCode(), result.clarification())
                .isTrue();
        assertThat(result.sql())
                .contains("\"current_tier_until\" >= ?")
                .contains("\"current_tier_until\" < ?")
                .doesNotContain("\"current_tier_until\" BETWEEN ? AND ?")
                .doesNotContain("\"current_tier_until\" <= ?");
        assertThat(result.parameters())
                .filteredOn(parameter -> !parameter.redacted())
                .extracting(parameter -> parameter.canonicalValue())
                .containsExactly("2026-10-04T12:00:00Z", "2026-11-03T12:00:00Z");
    }

    @Test
    void aModelGeneratedClosedTemporalUpperBoundFailsBeforeSqlCompilation() {
        String utterance = "找当前等级结束日期在未来 30 天内的顾客。";
        var result = invoke(utterance, tierEndIntent(utterance, true));

        assertThat(result.isCompiled()).isFalse();
        assertThat(result.failureCode()).isEqualTo(
                com.company.campaign.builder.segment.SegmentDslCompilationErrorCodes
                        .TEMPORAL_RANGE_NOT_HALF_OPEN);
        assertThat(result.sql()).isNull();
    }

    @Test
    void historicalPositiveOrderAndNoRecentPositiveOrderResolveWithoutClarification() {
        String utterance = "找历史上有过正单、但近 180 天没有任何正单的顾客。";
        String historical = metricClause(
                "historical", "POSITIVE_ORDER_COUNT", "GREATER_THAN", "INTEGER", "0",
                "{\"kind\":\"LIFETIME\"}", "COUNT", "历史上有过正单", utterance,
                "order.positive_count");
        String recent = metricClause(
                "recent", "POSITIVE_ORDER_COUNT", "EQUAL", "INTEGER", "0",
                "{\"kind\":\"ROLLING\",\"amount\":180,\"unit\":\"DAY\"}",
                "COUNT", "近 180 天没有任何正单", utterance, "order.positive_count");
        String intent = root(
                historical + "," + recent,
                "{\"type\":\"ALL\",\"children\":["
                        + "{\"type\":\"CLAUSE\",\"clauseId\":\"historical\"},"
                        + "{\"type\":\"CLAUSE\",\"clauseId\":\"recent\"}]}");

        var result = invoke(utterance, intent);

        assertThat(result.isCompiled())
                .as("%s / %s / %s", result.disposition(), result.failureCode(), result.clarification())
                .isTrue();
        assertThat(result.clarification()).isNull();
        assertThat(result.sql())
                .contains("EXISTS (SELECT")
                .contains("NOT EXISTS (SELECT")
                .contains("\"paid_at\" >= ?")
                .contains("\"paid_at\" < ?")
                .doesNotContain("COUNT(*)");
        assertThat(result.parameters())
                .filteredOn(parameter -> !parameter.redacted())
                .extracting(parameter -> parameter.canonicalValue())
                .contains("2026-04-07T12:00:00Z", "2026-10-04T12:00:00Z", "POSITIVE")
                .doesNotContain("0");
    }

    @Test
    void modelHintAndConceptConsensusAvoidRedundantPaidAmountAndOrderClarifications() {
        String utterance = "找历史累计实付 ≥ 5000、但近 180 天没有下单的顾客。";
        String paidAmount = metricClause(
                "paid-amount", "PAID_PURCHASE_AMOUNT", "GREATER_OR_EQUAL", "MONEY", "5000",
                "{\"kind\":\"LIFETIME\"}", "SUM", "历史累计实付 ≥ 5000", utterance,
                "order.paid_amount_sum");
        String noRecentOrder = metricClause(
                "recent-orders", "POSITIVE_ORDER_COUNT", "EQUAL", "INTEGER", "0",
                "{\"kind\":\"ROLLING\",\"amount\":180,\"unit\":\"DAY\"}",
                "COUNT", "近 180 天没有下单", utterance, "order.positive_count");
        String intent = root(
                paidAmount + "," + noRecentOrder,
                "{\"type\":\"ALL\",\"children\":["
                        + "{\"type\":\"CLAUSE\",\"clauseId\":\"paid-amount\"},"
                        + "{\"type\":\"CLAUSE\",\"clauseId\":\"recent-orders\"}]}");

        var result = invoke(utterance, intent);

        assertThat(result.isCompiled())
                .as("%s / %s / %s", result.disposition(), result.failureCode(), result.clarification())
                .isTrue();
        assertThat(result.clarification()).isNull();
        assertThat(result.sql())
                .contains("NOT EXISTS (SELECT")
                .contains("SUM(\"r3\".\"paid_amount\")")
                .doesNotContain("请选择");
        assertThat(result.parameters())
                .filteredOn(parameter -> !parameter.redacted())
                .extracting(parameter -> parameter.canonicalValue())
                .contains("2026-04-07T12:00:00Z", "2026-10-04T12:00:00Z", "POSITIVE", "CNY", "5000");
    }

    @Test
    void positiveOrdersInTwoChannelsCompileAsIndependentExistsPredicates() {
        String utterance = "找既在天猫下过单、又在线下门店下过单的顾客。";
        String tmall = channelOrderClause(
                "tmall", "天猫下过单", "tmall-channel", "天猫", "TMALL", utterance);
        String offline = channelOrderClause(
                "offline", "线下门店下过单", "offline-channel", "线下门店", "OFFLINE", utterance);
        String intent = root(
                tmall + "," + offline,
                "{\"type\":\"ALL\",\"children\":["
                        + "{\"type\":\"CLAUSE\",\"clauseId\":\"tmall\"},"
                        + "{\"type\":\"CLAUSE\",\"clauseId\":\"offline\"}]}");

        var result = invoke(utterance, intent);

        assertThat(result.isCompiled())
                .as("%s / %s / %s", result.disposition(), result.failureCode(), result.clarification())
                .isTrue();
        assertThat(result.clarification()).isNull();
        assertThat(result.sql().split("FROM \\\"public\\\"\\.\\\"seg_order\\\"", -1).length - 1)
                .isEqualTo(2);
        assertThat(result.sql())
                .contains("\"source_code\" = ?")
                .contains("\"order_type\" IN (?)")
                .contains(" AND ")
                .doesNotContain("COUNT(*)");
        assertThat(result.parameters())
                .filteredOn(parameter -> !parameter.redacted())
                .extracting(parameter -> parameter.canonicalValue())
                .contains("TMALL", "OFFLINE", "POSITIVE")
                .doesNotContain("0");
    }

    @Test
    void everyCompletedCalendarMonthCompilesAsOneExistsPerFixedBucket() {
        String utterance = "找近 3 个自然月、每个月都有正单的顾客。";
        String source = "近 3 个自然月、每个月都有正单";
        String everyMonth = metricClause(
                "calendar-months", "POSITIVE_ORDER_COUNT", "GREATER_THAN", "INTEGER", "0",
                "{\"kind\":\"CALENDAR_MONTHS\",\"count\":3,\"includeCurrent\":false,"
                        + "\"bucketRequirement\":\"EVERY_BUCKET\"}",
                "COUNT", source, utterance, "order.positive_count");
        String intent = root(
                everyMonth,
                "{\"type\":\"CLAUSE\",\"clauseId\":\"calendar-months\"}");

        var result = invoke(utterance, intent);

        assertThat(result.isCompiled())
                .as("%s / %s / %s", result.disposition(), result.failureCode(), result.clarification())
                .isTrue();
        assertThat(result.sql().split("FROM \\\"public\\\"\\.\\\"seg_order\\\"", -1).length - 1)
                .isEqualTo(3);
        assertThat(result.sql())
                .contains("\"paid_at\" >= ?")
                .contains("\"paid_at\" < ?")
                .doesNotContain("COUNT(*)");
        assertThat(result.parameters())
                .filteredOn(parameter -> !parameter.redacted())
                .extracting(parameter -> parameter.canonicalValue())
                .contains(
                        "2026-06-30T16:00:00Z",
                        "2026-07-31T16:00:00Z",
                        "2026-08-31T16:00:00Z",
                        "2026-09-30T16:00:00Z",
                        "POSITIVE")
                .doesNotContain("0");
    }

    @Test
    void secondOrderAbsenceUsesAWindowAnchoredToEachMembersFirstOrder() {
        String utterance = "找首单时间在 30 到 60 天前、且首单后 30 天内没有第二单的顾客。";
        String firstOrderSource = "首单时间在 30 到 60 天前";
        int firstOrderStart = utterance.indexOf(firstOrderSource);
        String firstOrder = "{\"clauseId\":\"first-order\"," 
                + "\"concept\":\"FIRST_ORDER_AT\",\"definitionKind\":\"ATTRIBUTE\"," 
                + "\"negated\":false,\"operator\":\"BETWEEN\",\"value\":{\"kind\":\"RANGE\"," 
                + "\"lower\":{\"kind\":\"TIMESTAMP\",\"raw\":\"2026-08-05T12:00:00Z\"}," 
                + "\"upper\":{\"kind\":\"TIMESTAMP\",\"raw\":\"2026-09-04T12:00:00Z\"}," 
                + "\"includeLower\":true,\"includeUpper\":false}," 
                + "\"provenance\":{\"type\":\"SOURCE_SPAN\",\"text\":\"" + firstOrderSource
                + "\",\"start\":" + firstOrderStart + ",\"end\":"
                + (firstOrderStart + firstOrderSource.length()) + "},"
                + "\"candidateHints\":[\"member.first_order_at\"],\"assumptions\":[]}";
        String noSecondOrder = metricClause(
                "no-second-order", "POSITIVE_ORDER_COUNT", "LESS_OR_EQUAL", "INTEGER", "1",
                "{\"kind\":\"RELATIVE_TO_ATTRIBUTE\"," 
                        + "\"anchorDefinitionId\":\"member.first_order_at\"," 
                        + "\"startOffset\":0,\"endOffset\":30,\"unit\":\"DAY\"}",
                "COUNT", "首单后 30 天内没有第二单", utterance, "order.positive_count");
        String intent = root(
                firstOrder + "," + noSecondOrder,
                "{\"type\":\"ALL\",\"children\":["
                        + "{\"type\":\"CLAUSE\",\"clauseId\":\"first-order\"},"
                        + "{\"type\":\"CLAUSE\",\"clauseId\":\"no-second-order\"}]}");

        var result = invoke(utterance, intent);

        assertThat(result.isCompiled())
                .as("%s / %s / %s", result.disposition(), result.failureCode(), result.clarification())
                .isTrue();
        assertThat(result.clarification()).isNull();
        assertThat(result.sql())
                .contains("\"first_order_at\" >= ? AND \"m0\".\"first_order_at\" < ?")
                .contains("COUNT(*)")
                .contains("\"paid_at\" >= (\"m0\".\"first_order_at\" + (? * INTERVAL '1 day'))")
                .contains("\"paid_at\" < (\"m0\".\"first_order_at\" + (? * INTERVAL '1 day'))")
                .contains(") <= ?")
                .doesNotContain("2026-09-04T12:00:00Z\") AND (\"r2\".\"paid_at\" <");
        assertThat(result.parameters())
                .filteredOn(parameter -> !parameter.redacted())
                .extracting(parameter -> parameter.canonicalValue())
                .contains("2026-08-05T12:00:00Z", "2026-09-04T12:00:00Z", "0", "30", "POSITIVE", "1");
    }

    @Test
    void firstOrderItemAndLaterAbsenceUseExactEventIdentity() {
        String utterance = "找首单买过商品名称为面霜、但之后 180 天内再没买过商品名称为面霜的顾客。";
        String firstSource = "首单买过商品名称为面霜";
        String laterSource = "之后 180 天内再没买过商品名称为面霜";
        String first = itemMetricClause(
                "first-item", "GREATER_THAN", "0",
                "{\"kind\":\"SELECTED_EVENT\",\"selectorId\":\"order.first_positive\"," 
                        + "\"selectorScopeFilters\":[]}",
                firstSource, "商品名称为面霜", utterance);
        String later = itemMetricClause(
                "later-item", "EQUAL", "0",
                "{\"kind\":\"RELATIVE_TO_EVENT\",\"selectorId\":\"order.first_positive\"," 
                        + "\"selectorScopeFilters\":[],\"startOffset\":0,\"endOffset\":180," 
                        + "\"unit\":\"DAY\",\"excludeAnchorEvent\":true}",
                laterSource, "商品名称为面霜", utterance);
        String intent = root(
                first + "," + later,
                "{\"type\":\"ALL\",\"children\":["
                        + "{\"type\":\"CLAUSE\",\"clauseId\":\"first-item\"},"
                        + "{\"type\":\"CLAUSE\",\"clauseId\":\"later-item\"}]}");

        var result = invoke(utterance, intent);

        assertThat(result.isCompiled())
                .as("%s / %s / %s", result.disposition(), result.failureCode(), result.clarification())
                .isTrue();
        assertThat(result.sql())
                .contains("ORDER BY \"s")
                .contains(".\"paid_at\" ASC, \"s")
                .contains(".\"order_id\" ASC LIMIT 1")
                .contains(".\"order_id\" = (SELECT")
                .contains(".\"order_id\" <> (SELECT")
                .contains("+ (? * INTERVAL '1 day')")
                .contains("\"product_name\" = ?")
                .doesNotContain("面霜");
        assertThat(result.parameters())
                .filteredOn(parameter -> !parameter.redacted())
                .extracting(parameter -> parameter.canonicalValue())
                .contains("面霜", "POSITIVE", "0", "180");
    }

    @Test
    void bareFirstOrderItemValueStillRequiresFieldClarification() {
        String utterance = "找首单买过面霜的顾客。";
        String source = "首单买过面霜";
        int sourceStart = utterance.indexOf(source);
        int filterStart = utterance.indexOf("面霜");
        String clause = "{\"clauseId\":\"first-item\",\"concept\":\"PURCHASED_ITEM_QUANTITY\"," 
                + "\"definitionKind\":\"METRIC\",\"negated\":false,\"operator\":\"GREATER_THAN\"," 
                + "\"value\":{\"kind\":\"INTEGER\",\"raw\":\"0\"},\"window\":{" 
                + "\"kind\":\"SELECTED_EVENT\",\"selectorId\":\"order.first_positive\"," 
                + "\"selectorScopeFilters\":[]},\"aggregation\":\"SUM\",\"scopeFilters\":[{" 
                + "\"filterId\":\"item\",\"concept\":\"PRODUCT_NAME\",\"domain\":\"PRODUCT\"," 
                + "\"identifierKind\":\"NAME\",\"operator\":\"EQUAL\"," 
                + "\"value\":{\"kind\":\"STRING\",\"raw\":\"面霜\"},\"provenance\":{" 
                + "\"type\":\"SOURCE_SPAN\",\"text\":\"面霜\",\"start\":" + filterStart
                + ",\"end\":" + (filterStart + 2) + "},\"candidateHints\":[\"order_item.product_name\"],"
                + "\"assumptions\":[]}],\"provenance\":{\"type\":\"SOURCE_SPAN\",\"text\":\""
                + source + "\",\"start\":" + sourceStart + ",\"end\":" + (sourceStart + source.length())
                + "},\"candidateHints\":[\"order_item.quantity_sum\"],\"assumptions\":[]}";

        var result = invoke(utterance, root(
                clause, "{\"type\":\"CLAUSE\",\"clauseId\":\"first-item\"}"));

        assertThat(result.isCompiled()).isFalse();
        assertThat(result.disposition()).isEqualTo(com.company.campaign.contracts.Disposition.NEEDS_INPUT);
        assertThat(result.sql()).isNull();
        assertThat(result.clarification()).isNotNull();
    }

    private static com.company.campaign.builder.segment.DevelopmentAiToSqlResult invoke(
            String utterance, String intent) {
        SegmentIntentModelPort model = ignored -> intent.getBytes(StandardCharsets.UTF_8);
        return new DevelopmentAiToSqlService(model, SegmentValidationFixtures.allowAll())
                .invoke(CoreLoyaltyDevelopmentProfile.request(utterance, AS_OF));
    }

    private static String metricClause(
            String clauseId,
            String concept,
            String operator,
            String valueKind,
            String raw,
            String window,
            String aggregation,
            String source,
            String utterance,
            String candidate) {
        int start = utterance.indexOf(source);
        return "{\"clauseId\":\"" + clauseId + "\",\"concept\":\"" + concept
                + "\",\"definitionKind\":\"METRIC\",\"negated\":false,"
                + "\"operator\":\"" + operator + "\",\"value\":{\"kind\":\"" + valueKind
                + "\",\"raw\":\"" + raw + "\"},\"window\":" + window
                + ",\"aggregation\":\"" + aggregation + "\","
                + "\"provenance\":{\"type\":\"SOURCE_SPAN\",\"text\":\"" + source
                + "\",\"start\":" + start + ",\"end\":" + (start + source.length()) + "},"
                + "\"candidateHints\":[\"" + candidate + "\"],\"assumptions\":[]}";
    }

    private static String attributeClause(
            String clauseId,
            String concept,
            String raw,
            String source,
            String utterance,
            String candidate) {
        int start = utterance.indexOf(source);
        return "{\"clauseId\":\"" + clauseId + "\",\"concept\":\"" + concept
                + "\",\"definitionKind\":\"ATTRIBUTE\",\"negated\":false,"
                + "\"operator\":\"EQUAL\",\"value\":{\"kind\":\"STRING\",\"raw\":\"" + raw
                + "\"},\"provenance\":{\"type\":\"SOURCE_SPAN\",\"text\":\"" + source
                + "\",\"start\":" + start + ",\"end\":" + (start + source.length()) + "},"
                + "\"candidateHints\":[\"" + candidate + "\"],\"assumptions\":[]}";
    }

    private static String channelOrderClause(
            String clauseId,
            String source,
            String filterId,
            String filterSource,
            String channel,
            String utterance) {
        int sourceStart = utterance.indexOf(source);
        int filterStart = utterance.indexOf(filterSource);
        return "{\"clauseId\":\"" + clauseId + "\",\"concept\":\"POSITIVE_ORDER_COUNT\""
                + ",\"definitionKind\":\"METRIC\",\"negated\":false,"
                + "\"operator\":\"GREATER_THAN\",\"value\":{\"kind\":\"INTEGER\",\"raw\":\"0\"},"
                + "\"window\":{\"kind\":\"LIFETIME\"},\"aggregation\":\"COUNT\","
                + "\"scopeFilters\":[{\"filterId\":\"" + filterId + "\",\"concept\":\"ORDER_CHANNEL\""
                + ",\"domain\":\"UNSPECIFIED\",\"identifierKind\":\"UNSPECIFIED\","
                + "\"operator\":\"EQUAL\",\"value\":{\"kind\":\"ENUM\",\"raw\":\"" + channel + "\"},"
                + "\"provenance\":{\"type\":\"SOURCE_SPAN\",\"text\":\"" + filterSource
                + "\",\"start\":" + filterStart + ",\"end\":" + (filterStart + filterSource.length()) + "},"
                + "\"candidateHints\":[\"order.channel\"],\"assumptions\":[]}],"
                + "\"provenance\":{\"type\":\"SOURCE_SPAN\",\"text\":\"" + source
                + "\",\"start\":" + sourceStart + ",\"end\":" + (sourceStart + source.length()) + "},"
                + "\"candidateHints\":[\"order.positive_count\"],\"assumptions\":[]}";
    }

    private static String itemMetricClause(
            String clauseId,
            String operator,
            String raw,
            String window,
            String source,
            String filterSource,
            String utterance) {
        int sourceStart = utterance.indexOf(source);
        int filterStart = utterance.indexOf(filterSource, sourceStart);
        return "{\"clauseId\":\"" + clauseId + "\",\"concept\":\"PURCHASED_ITEM_QUANTITY\""
                + ",\"definitionKind\":\"METRIC\",\"negated\":false,"
                + "\"operator\":\"" + operator + "\",\"value\":{\"kind\":\"INTEGER\",\"raw\":\""
                + raw + "\"},\"window\":" + window + ",\"aggregation\":\"SUM\","
                + "\"scopeFilters\":[{\"filterId\":\"" + clauseId + "-product\"," 
                + "\"concept\":\"PRODUCT_NAME\",\"domain\":\"PRODUCT\",\"identifierKind\":\"NAME\"," 
                + "\"operator\":\"EQUAL\",\"value\":{\"kind\":\"STRING\",\"raw\":\"面霜\"},"
                + "\"provenance\":{\"type\":\"SOURCE_SPAN\",\"text\":\"" + filterSource
                + "\",\"start\":" + filterStart + ",\"end\":" + (filterStart + filterSource.length()) + "},"
                + "\"candidateHints\":[\"order_item.product_name\"],\"assumptions\":[]}],"
                + "\"provenance\":{\"type\":\"SOURCE_SPAN\",\"text\":\"" + source
                + "\",\"start\":" + sourceStart + ",\"end\":" + (sourceStart + source.length()) + "},"
                + "\"candidateHints\":[\"order_item.quantity_sum\"],\"assumptions\":[]}";
    }

    private static String tierEndIntent(String utterance, boolean includeUpper) {
        String source = "当前等级结束日期在未来 30 天内";
        int start = utterance.indexOf(source);
        String clause = "{\"clauseId\":\"tier-end\","
                + "\"concept\":\"CURRENT_TIER_UNTIL\",\"definitionKind\":\"ATTRIBUTE\","
                + "\"negated\":false,\"operator\":\"BETWEEN\",\"value\":{\"kind\":\"RANGE\","
                + "\"lower\":{\"kind\":\"TIMESTAMP\",\"raw\":\"2026-10-04T12:00:00Z\"},"
                + "\"upper\":{\"kind\":\"TIMESTAMP\",\"raw\":\"2026-11-03T12:00:00Z\"},"
                + "\"includeLower\":true,\"includeUpper\":" + includeUpper + "},"
                + "\"provenance\":{\"type\":\"SOURCE_SPAN\",\"text\":\"" + source
                + "\",\"start\":" + start + ",\"end\":" + (start + source.length()) + "},"
                + "\"candidateHints\":[\"member.current_tier_until\"],\"assumptions\":[]}";
        return root(clause, "{\"type\":\"CLAUSE\",\"clauseId\":\"tier-end\"}");
    }

    private static String root(String clauses, String expression) {
        return "{\"intentVersion\":\"1.3\",\"subject\":\"MEMBER\",\"clauses\":[" + clauses
                + "],\"logicalExpression\":" + expression
                + ",\"ambiguities\":[],\"unsupportedRequirements\":[]}";
    }
}
