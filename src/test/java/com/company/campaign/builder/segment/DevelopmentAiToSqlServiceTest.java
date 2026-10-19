package com.company.campaign.builder.segment;

import com.company.campaign.builder.segment.DevelopmentAiToSqlResult.ParameterBinding;
import com.company.campaign.builder.segment.testsupport.SliceTestFixtures;
import com.company.campaign.contracts.Disposition;
import com.company.campaign.evaluation.segment.contract.MemberIdSet;
import com.company.campaign.metadata.domain.SubjectKind;
import com.company.campaign.query.plan.SegmentQueryPlanner;
import com.company.campaign.query.plan.SemanticQueryPlan;
import com.company.campaign.query.sql.SqlParameterType;
import com.company.campaign.query.sql.StorageCatalog;
import com.company.campaign.query.fixture.FixtureExecutionResult;
import com.company.campaign.query.fixture.testsupport.FixtureTestSupport;
import com.company.campaign.segment.dsl.contract.MetadataBinding;
import com.company.campaign.segment.dsl.contract.SegmentDsl;
import com.company.campaign.segment.dsl.contract.SegmentDslSchemaVersions;
import com.company.campaign.segment.dsl.predicate.AttributePredicate;
import com.company.campaign.segment.dsl.predicate.ConditionValue;
import com.company.campaign.segment.dsl.predicate.GroupPredicate;
import com.company.campaign.segment.dsl.predicate.MetricPredicate;
import com.company.campaign.segment.dsl.predicate.NotPredicate;
import com.company.campaign.segment.dsl.predicate.Predicate;
import com.company.campaign.segment.dsl.predicate.PredicateType;
import com.company.campaign.segment.dsl.value.EnumValue;
import com.company.campaign.segment.dsl.value.IntegerValue;
import com.company.campaign.segment.dsl.value.MoneyValue;
import com.company.campaign.segment.dsl.value.StringValue;
import com.company.campaign.segment.dsl.window.LifetimeWindow;
import com.company.campaign.segment.dsl.window.RollingWindow;
import com.company.campaign.segment.dsl.window.WindowUnit;
import com.company.campaign.segment.validation.SegmentValidationResult;
import com.company.campaign.segment.validation.testsupport.SegmentValidationFixtures;
import com.company.campaign.metadata.domain.ComparisonOperator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The end-to-end deterministic slice: exact DSL/canonical/plan/SQL artifacts, parameterization, redaction,
 * fail-closed behavior and plan semantics via the existing fixture executor. No PostgreSQL is executed.
 */
class DevelopmentAiToSqlServiceTest {

    private static final String TIER_UTTERANCE = "Gold会员";
    private static final String MONEY_UTTERANCE = "过去90天消费超过1000元";
    private static final String ADVERSARIAL = "'; DROP TABLE members; -- /* comment */ %_";

    private static final String DATA_SCOPE_EXISTS =
            "EXISTS (SELECT \"d1\".\"member_id\" FROM \"public\".\"data_scope_members\" AS \"d1\" "
                    + "WHERE ((\"d1\".\"scope_id\" = ?) AND (\"d1\".\"tenant_id\" = ?) AND "
                    + "(\"d1\".\"program_id\" = ?) AND (\"d1\".\"member_id\" = \"m0\".\"member_id\")))";

    private static final String ENUM_SQL =
            "SELECT \"m0\".\"member_id\" FROM \"public\".\"members\" AS \"m0\" WHERE "
                    + "((\"m0\".\"tenant_id\" = ?) AND (\"m0\".\"program_id\" = ?) AND " + DATA_SCOPE_EXISTS
                    + " AND (\"m0\".\"tier\" = ?))";

    private static final String INTEGER_SQL =
            "SELECT \"m0\".\"member_id\" FROM \"public\".\"members\" AS \"m0\" WHERE "
                    + "((\"m0\".\"tenant_id\" = ?) AND (\"m0\".\"program_id\" = ?) AND " + DATA_SCOPE_EXISTS
                    + " AND ((SELECT COUNT(*) FROM \"public\".\"orders\" AS \"r2\" WHERE "
                    + "((\"r2\".\"tenant_id\" = ?) AND (\"r2\".\"program_id\" = ?) AND "
                    + "(\"r2\".\"member_id\" = \"m0\".\"member_id\"))) > ?))";

    private static final String MONEY_SQL =
            "SELECT \"m0\".\"member_id\" FROM \"public\".\"members\" AS \"m0\" WHERE "
                    + "((\"m0\".\"tenant_id\" = ?) AND (\"m0\".\"program_id\" = ?) AND " + DATA_SCOPE_EXISTS
                    + " AND ((SELECT COALESCE(SUM((\"r2\".\"amount\" * (CASE \"r2\".\"currency\" WHEN ? THEN ? "
                    + "WHEN ? THEN ? END))), ?) FROM \"public\".\"orders\" AS \"r2\" WHERE "
                    + "((\"r2\".\"tenant_id\" = ?) AND (\"r2\".\"program_id\" = ?) AND "
                    + "(\"r2\".\"member_id\" = \"m0\".\"member_id\") AND ((\"r2\".\"paid_at\" >= ?) AND "
                    + "(\"r2\".\"paid_at\" < ?)))) > (? * (CASE ? WHEN ? THEN ? WHEN ? THEN ? END))))";

    private static final String NESTED_SQL =
            "SELECT \"m0\".\"member_id\" FROM \"public\".\"members\" AS \"m0\" WHERE "
                    + "((\"m0\".\"tenant_id\" = ?) AND (\"m0\".\"program_id\" = ?) AND " + DATA_SCOPE_EXISTS
                    + " AND ((\"m0\".\"tier\" = ?) AND (((SELECT COUNT(*) FROM \"public\".\"orders\" AS \"r2\" "
                    + "WHERE ((\"r2\".\"tenant_id\" = ?) AND (\"r2\".\"program_id\" = ?) AND "
                    + "(\"r2\".\"member_id\" = \"m0\".\"member_id\"))) > ?) OR (NOT (\"m0\".\"code\" = ?)))))";

    private static final String NULL_SQL =
            "SELECT \"m0\".\"member_id\" FROM \"public\".\"members\" AS \"m0\" WHERE "
                    + "((\"m0\".\"tenant_id\" = ?) AND (\"m0\".\"program_id\" = ?) AND " + DATA_SCOPE_EXISTS
                    + " AND ((\"m0\".\"code\" IS NULL) AND (\"m0\".\"code\" <> ?)))";

    private static final String INJECTION_SQL =
            "SELECT \"m0\".\"member_id\" FROM \"public\".\"members\" AS \"m0\" WHERE "
                    + "((\"m0\".\"tenant_id\" = ?) AND (\"m0\".\"program_id\" = ?) AND " + DATA_SCOPE_EXISTS
                    + " AND (\"m0\".\"code\" = ?))";

    // ------------------------------------------------------------------ successful cases

    @Test
    void enumAttributeEqualityCompilesToTheExactAllowlistedSql() {
        DevelopmentAiToSqlResult result = invoke(TIER_UTTERANCE, SliceTestFixtures.enumTierJson());

        Predicate predicate = new AttributePredicate(
                "n-c-c1", "member.tier", ComparisonOperator.EQUAL, ConditionValue.of(new EnumValue("GOLD")));
        assertResolved(result, predicate);
        assertThat(result.sql()).isEqualTo(ENUM_SQL);
        assertThat(result.parameters()).isEqualTo(List.of(
                redacted(), redacted(), redacted(), redacted(), redacted(),
                new ParameterBinding(SqlParameterType.ENUM, "GOLD", false)));
        assertThat(result.sql()).contains("\"public\".\"members\"").contains("\"tier\"");
    }

    @Test
    void integerMetricWithExplicitLifetimeWindowCompiles() {
        DevelopmentAiToSqlResult result = invoke("订单数超过3", SliceTestFixtures.integerCountJson());

        Predicate predicate = new MetricPredicate(
                "n-c-c1", "order.count", new LifetimeWindow(), ComparisonOperator.GREATER_THAN,
                ConditionValue.of(new IntegerValue(3L)));
        assertResolved(result, predicate);
        assertThat(result.sql()).isEqualTo(INTEGER_SQL);
        assertThat(result.parameters()).hasSize(8);
        assertThat(result.parameters().get(7))
                .isEqualTo(new ParameterBinding(SqlParameterType.INTEGER, "3", false));
        assertThat(result.parameters().subList(0, 7)).allMatch(ParameterBinding::redacted);
    }

    @Test
    void moneyMetricWithExplicitCnyAndRollingWindowCompilesWithFxBinding() {
        DevelopmentAiToSqlResult result = invoke(MONEY_UTTERANCE, SliceTestFixtures.moneySpendJson());

        Predicate predicate = new MetricPredicate(
                "n-c-c1", "order.net_paid_amount", new RollingWindow(90, WindowUnit.DAY),
                ComparisonOperator.GREATER_THAN, ConditionValue.of(new MoneyValue("1000", "CNY")));
        assertResolved(result, predicate);
        assertThat(result.sql()).isEqualTo(MONEY_SQL);
        assertThat(result.parameters()).contains(
                new ParameterBinding(SqlParameterType.CURRENCY, "USD", false),
                new ParameterBinding(SqlParameterType.DECIMAL, "7.2", false),
                new ParameterBinding(SqlParameterType.DECIMAL, "1000", false));
        assertThat(result.parameters()).contains(
                new ParameterBinding(SqlParameterType.TIMESTAMP, "2026-10-01T02:00:00Z", false));
    }

    @Test
    void nestedAllAnyNotGroupingCompilesWithoutFlattening() {
        DevelopmentAiToSqlResult result = invoke(
                SliceTestFixtures.NESTED_UTTERANCE, SliceTestFixtures.nestedGroupingJson());

        Predicate predicate = new GroupPredicate("n-g-r", PredicateType.ALL, List.of(
                new AttributePredicate(
                        "n-c-c1", "member.tier", ComparisonOperator.EQUAL,
                        ConditionValue.of(new EnumValue("GOLD"))),
                new GroupPredicate("n-g-r.1", PredicateType.ANY, List.of(
                        new MetricPredicate(
                                "n-c-c2", "order.count", new LifetimeWindow(), ComparisonOperator.GREATER_THAN,
                                ConditionValue.of(new IntegerValue(3L))),
                        new NotPredicate("n-not-r.1.1", new AttributePredicate(
                                "n-c-c3", "member.code", ComparisonOperator.EQUAL,
                                ConditionValue.of(new StringValue("ABC"))))))));
        assertResolved(result, predicate);
        assertThat(result.sql()).isEqualTo(NESTED_SQL);
        assertThat(result.sql()).contains(" OR ").contains("(NOT (").doesNotContain("JOIN");
    }

    @Test
    void nullAndNotEqualSemanticsNeverSilentlyIncludeNull() {
        SliceTestFixtures.StubModelPort port =
                SliceTestFixtures.StubModelPort.returning(SliceTestFixtures.nullSemanticsJson());
        DevelopmentAiToSqlResult result = SliceTestFixtures.service(port).invoke(SliceTestFixtures.request(
                SliceTestFixtures.NULL_UTTERANCE, SliceTestFixtures.codeOnlySnapshot(),
                SliceTestFixtures.policy(), SliceTestFixtures.registry()));

        Predicate predicate = new GroupPredicate("n-g-r", PredicateType.ALL, List.of(
                new AttributePredicate("n-c-c1", "member.code", ComparisonOperator.IS_NULL, ConditionValue.none()),
                new AttributePredicate(
                        "n-c-c2", "member.code", ComparisonOperator.NOT_EQUAL,
                        ConditionValue.of(new StringValue("ABC")))));
        assertResolved(result, predicate, SliceTestFixtures.codeOnlySnapshot());
        assertThat(result.sql()).isEqualTo(NULL_SQL);
        assertThat(result.sql()).contains("\"code\" IS NULL").contains("\"code\" <> ?");
        assertThat(result.parameters()).hasSize(6);
    }

    @Test
    void anAdversarialStringAppearsOnlyAsATypedParameter() {
        SliceTestFixtures.StubModelPort port =
                SliceTestFixtures.StubModelPort.returning(SliceTestFixtures.injectionJson(ADVERSARIAL));
        DevelopmentAiToSqlResult result = SliceTestFixtures.service(port)
                .invoke(SliceTestFixtures.request(SliceTestFixtures.INJECTION_UTTERANCE, port));

        Predicate predicate = new AttributePredicate(
                "n-c-c1", "member.code", ComparisonOperator.EQUAL, ConditionValue.of(new StringValue(ADVERSARIAL)));
        assertResolved(result, predicate);
        assertThat(result.sql()).isEqualTo(INJECTION_SQL);
        assertThat(result.parameters()).contains(new ParameterBinding(SqlParameterType.TEXT, ADVERSARIAL, false));
        assertThat(result.sql())
                .doesNotContain(ADVERSARIAL).doesNotContain("DROP").doesNotContain("--").doesNotContain("/*");
    }

    @Test
    void repeatedExactInputsProduceByteIdenticalSqlParametersAndHashes() {
        SliceTestFixtures.StubModelPort firstPort =
                SliceTestFixtures.StubModelPort.returning(SliceTestFixtures.moneySpendJson());
        SliceTestFixtures.StubModelPort secondPort =
                SliceTestFixtures.StubModelPort.returning(SliceTestFixtures.moneySpendJson());
        DevelopmentAiToSqlResult first = SliceTestFixtures.service(firstPort)
                .invoke(SliceTestFixtures.request(MONEY_UTTERANCE, firstPort));
        DevelopmentAiToSqlResult second = SliceTestFixtures.service(secondPort)
                .invoke(SliceTestFixtures.request(MONEY_UTTERANCE, secondPort));

        assertThat(first.sql()).isEqualTo(second.sql());
        assertThat(first.parameters()).isEqualTo(second.parameters());
        assertThat(first.dslEditableHash()).isEqualTo(second.dslEditableHash());
        assertThat(first.canonicalAstHash()).isEqualTo(second.canonicalAstHash());
        assertThat(first.planHash()).isEqualTo(second.planHash());
        assertThat(first.compiledHash()).isEqualTo(second.compiledHash());
    }

    @Test
    void theFixtureExecutorConfirmsPlanSemanticsAndMemberSet(@TempDir Path root) {
        SemanticQueryPlan expectedPlan = expectedPlan(new AttributePredicate(
                "n-c-c1", "member.tier", ComparisonOperator.EQUAL, ConditionValue.of(new EnumValue("GOLD"))));

        DevelopmentAiToSqlResult result = invoke(TIER_UTTERANCE, SliceTestFixtures.enumTierJson());
        assertThat(result.planHash()).isEqualTo(expectedPlan.planHash());

        var dataset = FixtureTestSupport.load(root, FixtureTestSupport.standardPayload());
        FixtureExecutionResult executed = FixtureTestSupport.execute(
                expectedPlan, dataset, FixtureTestSupport.standardMapping(),
                FixtureTestSupport.standardScope(), FixtureTestSupport.cnyFx("7.20"));

        assertThat(executed.memberIdSet()).isEqualTo(MemberIdSet.of("m-001", "m-003", "m-006"));
    }

    // ------------------------------------------------------------------ redaction

    @Test
    void theResultRedactsHostContextAndNeverDisclosesRestrictedValues() {
        DevelopmentAiToSqlResult result = invoke(TIER_UTTERANCE, SliceTestFixtures.enumTierJson());

        assertThat(result.developmentOnly()).isTrue();
        assertThat(result.evidenceClass()).isEqualTo("DEVELOPMENT_ONLY");
        assertThat(result.parameters()).filteredOn(ParameterBinding::redacted)
                .allMatch(binding -> binding.canonicalValue() == null);
        String rendered = result.toString() + result.sql() + result.parameters();
        assertThat(rendered)
                .doesNotContain("tenant-001").doesNotContain("program-001")
                .doesNotContain("scope-008").doesNotContain("user-001").doesNotContain("membership-1");
    }

    // ------------------------------------------------------------------ ambiguous / refused

    @Test
    void ambiguousGroupingCurrencyMetricAndNullNeverProduceAnyArtifact() {
        assertNoArtifact(SliceTestFixtures.request(
                MONEY_UTTERANCE, SliceTestFixtures.ambiguousSpendSnapshot(),
                SliceTestFixtures.policy(), SliceTestFixtures.registry()),
                SliceTestFixtures.ambiguousSpendJson());
        assertNoArtifact(SliceTestFixtures.request(
                MONEY_UTTERANCE, SliceTestFixtures.snapshot(),
                SliceTestFixtures.policy(), SliceTestFixtures.emptyRegistry()),
                SliceTestFixtures.missingCurrencyJson());
        assertNoArtifact(SliceTestFixtures.request(
                TIER_UTTERANCE, SliceTestFixtures.snapshot(),
                SliceTestFixtures.policy(), SliceTestFixtures.registry()),
                SliceTestFixtures.ambiguousNullJson());
        assertNoArtifact(SliceTestFixtures.request(
                TIER_UTTERANCE, SliceTestFixtures.snapshot(),
                SliceTestFixtures.policy(), SliceTestFixtures.registry()),
                SliceTestFixtures.ambiguousGroupingJson());
    }

    @Test
    void aRelationIntentIsRefusedRatherThanFlattened() {
        SliceTestFixtures.StubModelPort port =
                SliceTestFixtures.StubModelPort.returning(SliceTestFixtures.relationJson());
        DevelopmentAiToSqlResult result = SliceTestFixtures.service(port)
                .invoke(SliceTestFixtures.request("订单", port));

        assertThat(result.disposition()).isEqualTo(Disposition.UNSUPPORTED);
        assertThat(result.failureCode())
                .isEqualTo(SegmentDslCompilationErrorCodes.RELATION_NESTING_NOT_REPRESENTABLE);
        assertThat(result.sql()).isNull();
        assertThat(result.planHash()).isNull();
        assertThat(result.dslEditableHash()).isNull();
    }

    @Test
    void theConstructorInjectedAuthorizationServiceIsTheSingleAuthorizationSource() {
        SliceTestFixtures.StubModelPort port =
                SliceTestFixtures.StubModelPort.returning(SliceTestFixtures.enumTierJson());
        // An exact approval that echoes the requested resource kind, as the accepted T07/T08 boundary requires.
        com.company.campaign.builder.testsupport.RecordingAuthorizationService boundary =
                new com.company.campaign.builder.testsupport.RecordingAuthorizationService(request ->
                        com.company.campaign.security.authorization.AuthorizationDecision.allow(
                                request.resource().kind(), request.resource().kind().requiredPermission()));

        DevelopmentAiToSqlResult result = new DevelopmentAiToSqlService(port, boundary)
                .invoke(SliceTestFixtures.request(TIER_UTTERANCE, port));

        // The constructor boundary is consulted; the request has no authorization field to override it.
        assertThat(result.isCompiled()).as("%s / %s", result.disposition(), result.failureCode()).isTrue();
        assertThat(boundary.callCount()).isGreaterThan(0);
    }

    @Test
    void authorizationDenialTerminatesWithoutSql() {
        SliceTestFixtures.StubModelPort port =
                SliceTestFixtures.StubModelPort.returning(SliceTestFixtures.enumTierJson());
        DevelopmentAiToSqlService denied = new DevelopmentAiToSqlService(
                port, SegmentValidationFixtures.denyAll());
        DevelopmentAiToSqlResult result = denied.invoke(SliceTestFixtures.request(TIER_UTTERANCE, port));

        assertThat(result.disposition()).isEqualTo(Disposition.DENIED);
        assertThat(result.failureCode()).isEqualTo(DevelopmentAiToSqlErrorCodes.AUTHORIZATION_DENIED);
        assertThat(result.sql()).isNull();
    }

    // ------------------------------------------------------------------ fail closed

    @Test
    void malformedFencedProseOversizedNonUtf8EmptyThrowAndNullOutputsFailClosed() {
        assertAdapterFailure(SliceTestFixtures.enumTierJson().substring(0, 12),
                SegmentAdapterErrorCodes.OUTPUT_MALFORMED);
        assertAdapterFailure("```json\n" + SliceTestFixtures.enumTierJson() + "\n```",
                SegmentAdapterErrorCodes.OUTPUT_FENCED);
        assertAdapterFailure("Here is the JSON: " + SliceTestFixtures.enumTierJson(),
                SegmentAdapterErrorCodes.OUTPUT_PROSE);
        assertAdapterFailure("", SegmentAdapterErrorCodes.OUTPUT_EMPTY);
        assertAdapterFailure("   ", SegmentAdapterErrorCodes.OUTPUT_EMPTY);

        SliceTestFixtures.StubModelPort oversized = SliceTestFixtures.StubModelPort.returning(
                new byte[SegmentModelLimits.MAX_MODEL_OUTPUT_BYTES + 1]);
        assertFailureCode(oversized, SegmentAdapterErrorCodes.OUTPUT_OVERSIZED);

        SliceTestFixtures.StubModelPort nonUtf8 = SliceTestFixtures.StubModelPort.returning(
                new byte[] {(byte) 0xFF, (byte) 0xFE, 0x7B, 0x7D});
        assertFailureCode(nonUtf8, SegmentAdapterErrorCodes.OUTPUT_NOT_UTF8);

        SliceTestFixtures.StubModelPort throwing = SliceTestFixtures.StubModelPort.throwing();
        DevelopmentAiToSqlResult thrown = invokeWith(throwing, TIER_UTTERANCE);
        assertThat(thrown.disposition()).isEqualTo(Disposition.INVALID);
        assertThat(thrown.failureCode()).isEqualTo(SegmentAdapterErrorCodes.PROVIDER_FAILED);
        assertThat(thrown.toString()).doesNotContain("secret-like text");

        assertFailureCode(SliceTestFixtures.StubModelPort.returningNull(),
                SegmentAdapterErrorCodes.OUTPUT_MISSING);

        // A reserved host field in otherwise well-formed output fails closed without echoing the field name.
        String reservedField = SliceTestFixtures.enumTierJson().replace(
                "\"intentVersion\":\"1.3\",", "\"intentVersion\":\"1.3\",\"dataScopeId\":\"scope-1\",");
        assertAdapterFailure(reservedField, SegmentAdapterErrorCodes.OUTPUT_RESERVED_FIELD);

        // A field outside the closed schema also fails closed.
        String unknownField = SliceTestFixtures.enumTierJson().replace(
                "\"intentVersion\":\"1.3\",", "\"intentVersion\":\"1.3\",\"extra\":1,");
        assertAdapterFailure(unknownField, SegmentAdapterErrorCodes.OUTPUT_UNKNOWN_FIELD);

        // The money fixture grounds its source span in its own utterance, so that utterance is the one that
        // reaches the window contract; the default tier utterance would fail earlier at span grounding and
        // report the grounding code instead of the window code.
        String invalidWindow = SliceTestFixtures.moneySpendJson()
                .replace("\"unit\":\"DAY\"", "\"unit\":\"YEAR\"");
        assertAdapterFailure(invalidWindow, MONEY_UTTERANCE, SegmentAdapterErrorCodes.OUTPUT_WINDOW_INVALID);
    }

    @Test
    void aUniqueExactSourceTextRepairsOnlyItsMechanicalSpanIndexes() {
        String offByOne = SliceTestFixtures.enumTierJson().replace("\"end\":6", "\"end\":5");

        DevelopmentAiToSqlResult result = invoke(TIER_UTTERANCE, offByOne);

        assertThat(result.isCompiled()).isTrue();
    }

    @Test
    void anAmbiguousRepeatedSourceTextCannotRepairItsSpanIndexes() {
        String utterance = "Gold会员和Gold会员";
        String invalid = SliceTestFixtures.enumTierJson().replace("\"end\":6", "\"end\":5");
        SliceTestFixtures.StubModelPort port = SliceTestFixtures.StubModelPort.returning(invalid);

        DevelopmentAiToSqlResult result = SliceTestFixtures.service(port)
                .invoke(SliceTestFixtures.request(utterance, port));

        assertThat(result.disposition()).isEqualTo(Disposition.INVALID);
        assertThat(result.failureCode()).isEqualTo(SegmentAdapterErrorCodes.OUTPUT_INVALID);
    }

    @Test
    void staleContextCatalogAndFxPinsProduceNoSqlArtifact() {
        SliceTestFixtures.StubModelPort contextPort =
                SliceTestFixtures.StubModelPort.returning(SliceTestFixtures.enumTierJson());
        DevelopmentAiToSqlResult staleContext = SliceTestFixtures.service(contextPort)
                .invoke(SliceTestFixtures.requestWithContextPin("metadata-999"));
        assertThat(staleContext.isCompiled()).isFalse();
        assertThat(staleContext.sql()).isNull();

        SliceTestFixtures.StubModelPort catalogPort =
                SliceTestFixtures.StubModelPort.returning(SliceTestFixtures.enumTierJson());
        DevelopmentAiToSqlResult staleCatalog = SliceTestFixtures.service(catalogPort)
                .invoke(SliceTestFixtures.requestWithCatalog(TIER_UTTERANCE, StorageCatalog.empty("stale-catalog")));
        assertThat(staleCatalog.isCompiled()).isFalse();
        assertThat(staleCatalog.failureCode()).isEqualTo(DevelopmentAiToSqlErrorCodes.SQL_COMPILATION_FAILED);

        SliceTestFixtures.StubModelPort fxPort =
                SliceTestFixtures.StubModelPort.returning(SliceTestFixtures.moneySpendJson());
        DevelopmentAiToSqlResult staleFx = SliceTestFixtures.service(fxPort)
                .invoke(SliceTestFixtures.requestWithFx(MONEY_UTTERANCE, null));
        assertThat(staleFx.isCompiled()).isFalse();
        assertThat(staleFx.sql()).isNull();
        assertThat(staleFx.failureCode()).isEqualTo(DevelopmentAiToSqlErrorCodes.SQL_COMPILATION_FAILED);
    }

    // ------------------------------------------------------------------ helpers

    private static DevelopmentAiToSqlResult invoke(String utterance, String json) {
        SliceTestFixtures.StubModelPort port = SliceTestFixtures.StubModelPort.returning(json);
        return SliceTestFixtures.service(port).invoke(SliceTestFixtures.request(utterance, port));
    }

    private static DevelopmentAiToSqlResult invokeWith(SliceTestFixtures.StubModelPort port, String utterance) {
        return SliceTestFixtures.service(port).invoke(SliceTestFixtures.request(utterance, port));
    }

    private static ParameterBinding redacted() {
        return new ParameterBinding(SqlParameterType.CONTEXT_ID, null, true);
    }

    private static void assertResolved(DevelopmentAiToSqlResult result, Predicate predicate) {
        assertResolved(result, predicate, SliceTestFixtures.snapshot());
    }

    private static void assertResolved(
            DevelopmentAiToSqlResult result, Predicate predicate, com.company.campaign.metadata.domain.MetadataSnapshot snapshot) {
        SegmentDsl expectedDsl = SegmentDsl.publish(
                SegmentDslSchemaVersions.SUPPORTED, SubjectKind.MEMBER,
                MetadataBinding.fromSnapshot(snapshot), predicate);
        SegmentValidationResult validation = SegmentValidationFixtures.validate(
                expectedDsl, snapshot, SliceTestFixtures.context(), SegmentValidationFixtures.allowAll(),
                SliceTestFixtures.validationPolicy());
        assertThat(validation.valid()).isTrue();
        SemanticQueryPlan expectedPlan = new SegmentQueryPlanner(SegmentValidationFixtures.allowAll())
                .plan(validation, snapshot, SliceTestFixtures.context());

        // The exact SQL AST shape, text, parameters and pins are all covered by the independently
        // recompiled artifact hash: the slice produced exactly the accepted T09B artifact.
        var expectedCompiled = com.company.campaign.query.sql.SqlTestSupport.compile(expectedPlan);

        assertThat(result.disposition()).isEqualTo(Disposition.RESOLVED);
        assertThat(result.isCompiled()).isTrue();
        assertThat(result.dslEditableHash()).isEqualTo(expectedDsl.editableDslHash());
        assertThat(result.canonicalAstHash()).isEqualTo(validation.canonicalAstHash());
        assertThat(result.planHash()).isEqualTo(expectedPlan.planHash());
        assertThat(result.authorizationPredicatePresent()).isTrue();
        assertThat(result.compiledHash()).isEqualTo(expectedCompiled.compiledHash());
        assertThat(result.sql()).isEqualTo(expectedCompiled.sql());
        assertThat(result.catalogVersion()).isEqualTo(SliceTestFixtures.catalog().catalogVersion());
        assertThat(result.catalogHash()).isEqualTo(SliceTestFixtures.catalog().contentHash());
        assertThat(result.parameters()).allMatch(binding -> !binding.redacted() || binding.canonicalValue() == null);
    }

    private static SemanticQueryPlan expectedPlan(Predicate predicate) {
        SegmentDsl document = SegmentDsl.publish(
                SegmentDslSchemaVersions.SUPPORTED, SubjectKind.MEMBER,
                MetadataBinding.fromSnapshot(SliceTestFixtures.snapshot()), predicate);
        SegmentValidationResult validation = SegmentValidationFixtures.validate(
                document, SliceTestFixtures.snapshot(), SliceTestFixtures.context(),
                SegmentValidationFixtures.allowAll(), SliceTestFixtures.validationPolicy());
        assertThat(validation.valid()).isTrue();
        return new SegmentQueryPlanner(SegmentValidationFixtures.allowAll())
                .plan(validation, SliceTestFixtures.snapshot(), SliceTestFixtures.context());
    }

    private static void assertNoArtifact(DevelopmentAiToSqlRequest request, String json) {
        SliceTestFixtures.StubModelPort port = SliceTestFixtures.StubModelPort.returning(json);
        DevelopmentAiToSqlResult result = SliceTestFixtures.service(port).invoke(request);
        assertThat(result.disposition()).isEqualTo(Disposition.NEEDS_INPUT);
        assertThat(result.isCompiled()).isFalse();
        assertThat(result.sql()).isNull();
        assertThat(result.planHash()).isNull();
        assertThat(result.dslEditableHash()).isNull();
        assertThat(result.canonicalAstHash()).isNull();
    }

    private static void assertAdapterFailure(String json, String expectedCode) {
        assertFailureCode(SliceTestFixtures.StubModelPort.returning(json), expectedCode);
    }

    /** Asserts one raw response fails closed with a code, grounded in the utterance its spans were written for. */
    private static void assertAdapterFailure(String json, String utterance, String expectedCode) {
        DevelopmentAiToSqlResult result =
                invokeWith(SliceTestFixtures.StubModelPort.returning(json), utterance);
        assertThat(result.disposition()).isEqualTo(Disposition.INVALID);
        assertThat(result.failureCode()).isEqualTo(expectedCode);
        assertThat(result.sql()).isNull();
        assertThat(result.planHash()).isNull();
    }

    private static void assertFailureCode(SliceTestFixtures.StubModelPort port, String expectedCode) {
        DevelopmentAiToSqlResult result = invokeWith(port, TIER_UTTERANCE);
        assertThat(result.disposition()).isEqualTo(Disposition.INVALID);
        assertThat(result.failureCode()).isEqualTo(expectedCode);
        assertThat(result.sql()).isNull();
        assertThat(result.planHash()).isNull();
    }
}
