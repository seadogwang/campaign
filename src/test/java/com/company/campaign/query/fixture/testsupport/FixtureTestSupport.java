package com.company.campaign.query.fixture.testsupport;

import com.company.campaign.contracts.SchemaVersion;
import com.company.campaign.evaluation.segment.contract.FixtureDescriptor;
import com.company.campaign.evaluation.segment.contract.FixtureRole;
import com.company.campaign.evaluation.segment.contract.MemberIdSet;
import com.company.campaign.evaluation.segment.loader.EvaluationRoot;
import com.company.campaign.metadata.MetadataSchemaVersions;
import com.company.campaign.metadata.domain.AggregateEmptySet;
import com.company.campaign.metadata.domain.AggregateFunction;
import com.company.campaign.metadata.domain.AttributeDefinition;
import com.company.campaign.metadata.domain.ComparisonOperator;
import com.company.campaign.metadata.domain.CurrencySemantics;
import com.company.campaign.metadata.domain.EmptySetBehavior;
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
import com.company.campaign.metadata.domain.SubjectKind;
import com.company.campaign.metadata.domain.TimeWindowKind;
import com.company.campaign.metadata.domain.WindowBoundarySemantics;
import com.company.campaign.metadata.testsupport.MetadataFixtures;
import com.company.campaign.query.fixture.DatasetField;
import com.company.campaign.query.fixture.DatasetRow;
import com.company.campaign.query.fixture.FixtureDataset;
import com.company.campaign.query.fixture.FixtureDatasetLoader;
import com.company.campaign.query.fixture.FixtureExecutionResult;
import com.company.campaign.query.fixture.FixtureExecutor;
import com.company.campaign.query.fixture.FixtureMapping;
import com.company.campaign.query.fixture.FixtureScopeBinding;
import com.company.campaign.query.fixture.FxRateSnapshot;
import com.company.campaign.query.plan.SemanticQueryPlan;
import com.company.campaign.segment.dsl.predicate.Predicate;
import com.company.campaign.query.testsupport.QueryPlanFixtures;
import com.company.campaign.segment.validation.testsupport.SegmentValidationFixtures;
import com.company.campaign.shared.canonical.ContentHash;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.company.campaign.query.fixture.DatasetField.EVENT_MEMBER_ID;
import static com.company.campaign.query.fixture.DatasetField.EVENT_OCCURRED_AT;
import static com.company.campaign.query.fixture.DatasetField.EVENT_TYPE;
import static com.company.campaign.query.fixture.DatasetField.MEMBER_AGE;
import static com.company.campaign.query.fixture.DatasetField.MEMBER_BALANCE;
import static com.company.campaign.query.fixture.DatasetField.MEMBER_FLAG;
import static com.company.campaign.query.fixture.DatasetField.MEMBER_ID;
import static com.company.campaign.query.fixture.DatasetField.MEMBER_JOIN_DATE;
import static com.company.campaign.query.fixture.DatasetField.MEMBER_LAST_SEEN;
import static com.company.campaign.query.fixture.DatasetField.MEMBER_NAME;
import static com.company.campaign.query.fixture.DatasetField.MEMBER_SCORE;
import static com.company.campaign.query.fixture.DatasetField.MEMBER_TIER;
import static com.company.campaign.query.fixture.DatasetField.ORDER_AMOUNT;
import static com.company.campaign.query.fixture.DatasetField.ORDER_CHANNEL;
import static com.company.campaign.query.fixture.DatasetField.ORDER_MEMBER_ID;
import static com.company.campaign.query.fixture.DatasetField.ORDER_PAID_AT;
import static com.company.campaign.query.fixture.DatasetField.ORDER_STATUS;

/**
 * Deterministic T09A test fixtures.
 *
 * <p>Payloads are written beneath a JUnit temporary directory that is a real evaluation root, loaded through
 * the production {@link FixtureDatasetLoader} (so containment, hashing and strict parsing are exercised), and
 * planned through the accepted T08 planner using only genuine T02/T04/T05/T07 contracts. No T06 corpus asset,
 * clock, network or database is touched.
 */
public final class FixtureTestSupport {

    public static final Instant AS_OF = Instant.parse("2026-10-01T02:00:00Z");
    public static final Instant WATERMARK = Instant.parse("2026-10-01T01:55:00Z");
    public static final String SCOPE_ID = "scope-008";
    public static final String FIXTURE_ID = "fixture-t09a-001";
    public static final String FIXTURE_VERSION = "1";
    public static final String PAYLOAD_REFERENCE = "evaluation/segment/fixtures/payload-001.json";

    public static final MemberIdSet UNIVERSE = MemberIdSet.of(
            "m-001", "m-002", "m-003", "m-004", "m-005", "m-006");
    public static final MemberIdSet EXISTS_UNIVERSE = MemberIdSet.of("m-001", "m-002", "m-003", "m-004");
    /** The exact member universe of {@link #balanceFreePayload()}. */
    public static final MemberIdSet BALANCE_FREE_UNIVERSE = MemberIdSet.of("m-001");
    /** The exact member universe of {@link #cnyBalancePayload()}. */
    public static final MemberIdSet CNY_BALANCE_UNIVERSE = MemberIdSet.of("m-001", "m-002", "m-003");

    private FixtureTestSupport() {
    }

    // ------------------------------------------------------------------ loading

    /** Writes the payload beneath a temporary evaluation root and loads it through the production loader. */
    public static FixtureDataset load(Path repositoryRoot, String payload) {
        return load(repositoryRoot, payload, UNIVERSE, FixtureRole.PRIMARY, FIXTURE_ID);
    }

    /** Writes a payload and loads it with an explicit universe under the standard role and fixture id. */
    public static FixtureDataset load(Path repositoryRoot, String payload, MemberIdSet universe) {
        return load(repositoryRoot, payload, universe, FixtureRole.PRIMARY, FIXTURE_ID);
    }

    /** Writes a payload and loads it with an explicit universe, role and fixture id. */
    public static FixtureDataset load(
            Path repositoryRoot, String payload, MemberIdSet universe, FixtureRole role, String fixtureId) {
        byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
        writeBytes(repositoryRoot, bytes);
        return loaderFor(repositoryRoot).load(descriptorFor(repositoryRoot, bytes, universe, role, fixtureId));
    }

    /** Writes raw payload bytes to the standard reference beneath the temporary evaluation root. */
    public static void writeBytes(Path repositoryRoot, byte[] bytes) {
        Path file = repositoryRoot.resolve(PAYLOAD_REFERENCE);
        try {
            Files.createDirectories(file.getParent());
            Files.write(file, bytes);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    /** The production loader configured for a temporary evaluation root. */
    public static FixtureDatasetLoader loaderFor(Path repositoryRoot) {
        return new FixtureDatasetLoader(EvaluationRoot.of(repositoryRoot, "evaluation/segment"));
    }

    /** A descriptor whose payload hash is the exact hash of the supplied bytes. */
    public static FixtureDescriptor descriptorFor(
            Path repositoryRoot, byte[] bytes, MemberIdSet universe, FixtureRole role, String fixtureId) {
        return new FixtureDescriptor(
                fixtureId, FIXTURE_VERSION, role, PAYLOAD_REFERENCE, ContentHash.sha256(bytes), AS_OF,
                ZoneId.of("Asia/Shanghai"), WATERMARK, universe);
    }

    // ------------------------------------------------------------------ payload building

    /** The reviewed corpus-v1 payload envelope with the standard fixture identity and time pins. */
    public static String payload(List<String> members, List<String> orders, List<String> events, List<String> refunds) {
        return payload(FIXTURE_ID, "PRIMARY", members, orders, events, refunds);
    }

    /** A corpus-v1 payload envelope with explicit identity and role. */
    public static String payload(
            String fixtureId, String role, List<String> members, List<String> orders,
            List<String> events, List<String> refunds) {
        return "{\"asOf\":\"2026-10-01T02:00:00Z\",\"currencies\":[\"CNY\",\"USD\"],"
                + "\"dataset\":\"fixture_t09a\",\"fixtureId\":\"" + fixtureId + "\","
                + "\"role\":\"" + role + "\",\"statuses\":[\"ACTIVE\",\"CHURNED\",\"DORMANT\"],"
                + "\"watermark\":\"2026-10-01T01:55:00Z\","
                + "\"members\":" + array(members) + ","
                + "\"orders\":" + array(orders) + ","
                + "\"events\":" + array(events) + ","
                + "\"refunds\":" + array(refunds) + "}";
    }

    private static String array(List<String> values) {
        return "[" + String.join(",", values) + "]";
    }

    public static String member(
            String id, String tier, String status, String city, String joinDate, String name,
            Integer age, String score, Boolean flag, String lastSeen, String balance, String balanceCurrency) {
        return "{\"memberId\":\"" + id + "\",\"tier\":" + text(tier) + ",\"status\":" + text(status)
                + ",\"city\":" + text(city) + ",\"joinDate\":\"" + joinDate + "\",\"name\":" + text(name)
                + ",\"age\":" + (age == null ? "null" : age)
                + ",\"score\":" + (score == null ? "null" : score)
                + ",\"flag\":" + (flag == null ? "null" : flag)
                + ",\"lastSeenAt\":" + text(lastSeen)
                + ",\"balance\":" + (balance == null ? "null" : balance)
                + ",\"balanceCurrency\":" + text(balanceCurrency) + "}";
    }

    public static String order(
            String id, String memberId, String amount, String currency, String paidAt,
            String refunded, String status, String channel) {
        return "{\"orderId\":\"" + id + "\",\"memberId\":\"" + memberId + "\",\"amount\":" + amount
                + ",\"currency\":\"" + currency + "\",\"paidAt\":\"" + paidAt + "\",\"refunded\":" + refunded
                + ",\"status\":\"" + status + "\",\"channel\":" + text(channel) + "}";
    }

    public static String event(String id, String memberId, String occurredAt, String type) {
        return "{\"eventId\":\"" + id + "\",\"memberId\":\"" + memberId + "\",\"occurredAt\":\""
                + occurredAt + "\",\"type\":\"" + type + "\"}";
    }

    public static String refund(String orderId, String amount) {
        return "{\"orderId\":\"" + orderId + "\",\"amount\":" + amount + "}";
    }

    private static String text(String value) {
        return value == null ? "null" : "\"" + value + "\"";
    }

    // ------------------------------------------------------------------ standard dataset

    /**
     * The standard discriminating dataset.
     *
     * <pre>
     * m-001 GOLD   ACTIVE  Shanghai  2026-01-15  Alice  30   1.50 true  100.00 CNY
     * m-002 SILVER ACTIVE  Shanghai  2026-02-15  Bob    40   2.50 false 200.00 CNY
     * m-003 GOLD   DORMANT Beijing   2026-03-15  carol  50   3.50 null  300.00 USD
     * m-004 SILVER ACTIVE  Beijing   2026-04-15  Dave   null null true  null
     * m-005 null   ACTIVE  Shanghai  2026-05-15  null   25   5.00 false 500.00 CNY
     * m-006 GOLD   CHURNED Guangzhou 2026-06-15  fullwidth-Eric 35 6.00 true 600.00 CNY
     * </pre>
     *
     * <p>Orders {@code o-001..o-007} cover the rolling window start (inclusive), the rolling window end
     * (exclusive) and a refunded order {@code o-002}; m-004 and m-006 have no orders at all.
     */
    public static String standardPayload() {
        return payload(standardMembers(), standardOrders(), List.of(), List.of(refund("o-002", "10.00")));
    }

    public static List<String> standardMembers() {
        return List.of(
                member("m-001", "GOLD", "ACTIVE", "Shanghai", "2026-01-15", "Alice", 30, "1.50", true,
                        "2026-09-30T10:00:00Z", "100.00", "CNY"),
                member("m-002", "SILVER", "ACTIVE", "Shanghai", "2026-02-15", "Bob", 40, "2.50", false,
                        "2026-09-29T10:00:00Z", "200.00", "CNY"),
                member("m-003", "GOLD", "DORMANT", "Beijing", "2026-03-15", "carol", 50, "3.50", null,
                        "2026-09-28T10:00:00Z", "300.00", "USD"),
                member("m-004", "SILVER", "ACTIVE", "Beijing", "2026-04-15", "Dave", null, null, true,
                        "2026-09-27T10:00:00Z", null, null),
                member("m-005", null, "ACTIVE", "Shanghai", "2026-05-15", null, 25, "5.00", false,
                        null, "500.00", "CNY"),
                member("m-006", "GOLD", "CHURNED", "Guangzhou", "2026-06-15", "Ｅｒｉｃ",
                        35, "6.00", true, "2026-09-26T10:00:00Z", "600.00", "CNY"));
    }

    public static List<String> standardOrders() {
        return List.of(
                order("o-001", "m-001", "100.00", "CNY", "2026-09-30T09:00:00Z", "0.00", "PAID", "ONLINE"),
                order("o-002", "m-001", "50.00", "CNY", "2026-09-20T09:00:00Z", "10.00", "COMPLETED", "STORE"),
                order("o-003", "m-002", "200.00", "CNY", "2026-09-15T09:00:00Z", "0.00", "PAID", "ONLINE"),
                order("o-004", "m-003", "300.00", "USD", "2026-08-01T09:00:00Z", "0.00", "PAID", "STORE"),
                order("o-005", "m-005", "25.00", "CNY", "2026-09-29T23:30:00Z", "0.00", "PAID", "ONLINE"),
                order("o-006", "m-001", "7.00", "CNY", "2026-09-30T02:00:00Z", "0.00", "PAID", "ONLINE"),
                order("o-007", "m-002", "9.00", "CNY", "2026-10-01T02:00:00Z", "0.00", "PAID", "ONLINE"));
    }

    /** A dataset with no orders, events or refunds at all, for COUNT-over-empty and empty-set behavior. */
    public static String emptyActivityPayload() {
        return payload(List.of(standardMembers().get(0), standardMembers().get(1)), List.of(), List.of(), List.of());
    }

    /** A dataset whose members have no monetary attribute at all, for NULL-over-empty attribute behavior. */
    public static String balanceFreePayload() {
        return payload(
                List.of(
                        member("m-001", "GOLD", "ACTIVE", "Shanghai", "2026-01-15", "Alice", 30, "1.50", true,
                                "2026-09-30T10:00:00Z", null, null)),
                List.of(), List.of(), List.of());
    }

    /** A balances-in-one-currency dataset, for the single-currency-only success path. */
    public static String cnyBalancePayload() {
        return payload(
                List.of(
                        member("m-001", "GOLD", "ACTIVE", "Shanghai", "2026-01-15", "Alice", 30, "1.50", true,
                                "2026-09-30T10:00:00Z", "100.00", "CNY"),
                        member("m-002", "SILVER", "ACTIVE", "Shanghai", "2026-02-15", "Bob", 40, "2.50", false,
                                "2026-09-29T10:00:00Z", "300.00", "CNY"),
                        member("m-003", "GOLD", "DORMANT", "Beijing", "2026-03-15", "carol", 50, "3.50", null,
                                "2026-09-28T10:00:00Z", null, null)),
                List.of(), List.of(), List.of());
    }

    /**
     * The EXISTS dataset: m-002 satisfies both predicates on one order; m-003 satisfies them only on two
     * different orders; m-004 satisfies neither; m-001 has no orders at all.
     */
    public static String existsPayload() {
        List<String> members = List.of(
                member("m-001", "GOLD", "ACTIVE", "Shanghai", "2026-01-15", "Alice", 30, "1.50", true,
                        "2026-09-30T10:00:00Z", "100.00", "CNY"),
                member("m-002", "SILVER", "ACTIVE", "Shanghai", "2026-02-15", "Bob", 40, "2.50", false,
                        "2026-09-29T10:00:00Z", "200.00", "CNY"),
                member("m-003", "GOLD", "DORMANT", "Beijing", "2026-03-15", "carol", 50, "3.50", null,
                        "2026-09-28T10:00:00Z", "300.00", "CNY"),
                member("m-004", "SILVER", "ACTIVE", "Beijing", "2026-04-15", "Dave", null, null, true,
                        "2026-09-27T10:00:00Z", null, null));
        List<String> orders = List.of(
                order("o-101", "m-002", "100.00", "CNY", "2026-09-30T09:00:00Z", "0.00", "PAID", "ONLINE"),
                order("o-102", "m-003", "300.00", "CNY", "2026-09-30T02:00:00Z", "0.00", "PAID", "STORE"),
                order("o-103", "m-003", "5.00", "CNY", "2026-09-25T09:00:00Z", "0.00", "PAID", "ONLINE"),
                order("o-104", "m-004", "300.00", "CNY", "2026-10-01T02:00:00Z", "0.00", "PAID", "STORE"));
        return payload(members, orders, List.of(), List.of());
    }

    /** The events dataset: m-001 has two events, m-002 one, m-003 and m-004 none. */
    public static String eventsPayload() {
        List<String> members = List.of(
                member("m-001", "GOLD", "ACTIVE", "Shanghai", "2026-01-15", "Alice", 30, "1.50", true,
                        "2026-09-30T10:00:00Z", "100.00", "CNY"),
                member("m-002", "SILVER", "ACTIVE", "Shanghai", "2026-02-15", "Bob", 40, "2.50", false,
                        "2026-09-29T10:00:00Z", "200.00", "CNY"),
                member("m-003", "GOLD", "DORMANT", "Beijing", "2026-03-15", "carol", 50, "3.50", null,
                        "2026-09-28T10:00:00Z", "300.00", "CNY"),
                member("m-004", "SILVER", "ACTIVE", "Beijing", "2026-04-15", "Dave", null, null, true,
                        "2026-09-27T10:00:00Z", null, null));
        List<String> events = List.of(
                event("e-101", "m-001", "2026-09-30T10:00:00Z", "LOGIN"),
                event("e-102", "m-001", "2026-09-30T10:00:00Z", "PURCHASE"),
                event("e-103", "m-002", "2026-09-29T10:00:00Z", "LOGIN"));
        return payload(members, List.of(), events, List.of());
    }

    // ------------------------------------------------------------------ mapping and scope

    /** The exact Metadata pin the standard test snapshot publishes. */
    private static SchemaVersion memberVersion() {
        return MetadataFixtures.memberVersion().schemaVersion();
    }

    private static SchemaVersion orderVersion() {
        return MetadataFixtures.orderVersion().schemaVersion();
    }

    private static List<FixtureMapping.FieldBinding> standardFields() {
        return List.of(
                new FixtureMapping.FieldBinding("source.id", SubjectKind.MEMBER, MEMBER_ID),
                new FixtureMapping.FieldBinding("member.id", SubjectKind.MEMBER, MEMBER_ID),
                new FixtureMapping.FieldBinding("target.parent_id", SubjectKind.ORDER, ORDER_MEMBER_ID),
                new FixtureMapping.FieldBinding("order.member_id", SubjectKind.ORDER, ORDER_MEMBER_ID),
                new FixtureMapping.FieldBinding("order.paid_at", SubjectKind.ORDER, ORDER_PAID_AT));
    }

    private static List<FixtureMapping.AttributeBinding> standardAttributes() {
        return List.of(
                new FixtureMapping.AttributeBinding("member.tier", SubjectKind.MEMBER, memberVersion(), MEMBER_TIER),
                new FixtureMapping.AttributeBinding("member.name", SubjectKind.MEMBER, memberVersion(), MEMBER_NAME),
                new FixtureMapping.AttributeBinding("member.balance", SubjectKind.MEMBER, memberVersion(), MEMBER_BALANCE),
                new FixtureMapping.AttributeBinding("member.age", SubjectKind.MEMBER, memberVersion(), MEMBER_AGE),
                new FixtureMapping.AttributeBinding("member.score", SubjectKind.MEMBER, memberVersion(), MEMBER_SCORE),
                new FixtureMapping.AttributeBinding("member.joined_on", SubjectKind.MEMBER, memberVersion(), MEMBER_JOIN_DATE),
                new FixtureMapping.AttributeBinding("member.last_seen", SubjectKind.MEMBER, memberVersion(), MEMBER_LAST_SEEN),
                new FixtureMapping.AttributeBinding("member.flag", SubjectKind.MEMBER, memberVersion(), MEMBER_FLAG),
                new FixtureMapping.AttributeBinding("order.amount", SubjectKind.ORDER, orderVersion(), ORDER_AMOUNT),
                new FixtureMapping.AttributeBinding("order.channel", SubjectKind.ORDER, orderVersion(), ORDER_CHANNEL));
    }

    private static List<FixtureMapping.MetricBinding> standardMetrics() {
        return List.of(
                metric("order.net_paid_amount", "member.orders", ORDER_AMOUNT, Map.of(), ORDER_PAID_AT),
                metric("order.count", "member.orders", null, Map.of(), null),
                metric("order.avg_amount", "member.orders", ORDER_AMOUNT, Map.of(), null),
                metric("order.min_amount", "member.orders", ORDER_AMOUNT, Map.of(), null),
                metric("order.max_amount", "member.orders", ORDER_AMOUNT, Map.of(), null),
                metric("order.latest_channel", "member.orders", ORDER_CHANNEL, Map.of(), null),
                metric("order.inclusive_amount", "member.orders", ORDER_AMOUNT, Map.of(), ORDER_PAID_AT));
    }

    private static FixtureMapping.MetricBinding metric(
            String id, String relationId, DatasetField inputField,
            Map<String, DatasetField> filterFields, DatasetField eventTimeField) {
        return new FixtureMapping.MetricBinding(
                id, SubjectKind.MEMBER, memberVersion(), relationId, SubjectKind.ORDER,
                inputField, filterFields, eventTimeField);
    }

    private static List<FixtureMapping.RelationBinding> standardRelations() {
        return List.of(new FixtureMapping.RelationBinding(
                "member.orders", SubjectKind.MEMBER, memberVersion(),
                SubjectKind.ORDER, orderVersion(), MEMBER_ID, ORDER_MEMBER_ID));
    }

    /** The server-owned mapping for {@code SegmentValidationFixtures.snapshot()}. */
    public static FixtureMapping standardMapping() {
        return FixtureMapping.publish(
                "fixture-mapping-v1", standardFields(), standardAttributes(), standardMetrics(), standardRelations());
    }

    /**
     * The standard mapping with exactly one metric's aggregate input field replaced.
     *
     * <p>Every other fact — mapping version, fields, attributes, relations, all other metrics and the
     * replaced metric's relation, subject, filters and event-time field — stays identical, so the two
     * mappings differ only in the input field. Used by hash-coverage tests to prove that an omitted semantic
     * component is part of the canonical mapping material.
     */
    public static FixtureMapping standardMappingWithMetricInput(String definitionId, DatasetField inputField) {
        List<FixtureMapping.MetricBinding> metrics = new ArrayList<>();
        for (FixtureMapping.MetricBinding binding : standardMetrics()) {
            metrics.add(binding.definitionId().equals(definitionId)
                    ? new FixtureMapping.MetricBinding(
                            binding.definitionId(), binding.subject(), binding.entitySchemaVersion(),
                            binding.relationId(), binding.targetEntity(), inputField,
                            binding.filterFields(), binding.eventTimeField())
                    : binding);
        }
        return FixtureMapping.publish(
                "fixture-mapping-v1", standardFields(), standardAttributes(), metrics, standardRelations());
    }

    /** The mapping extended with the refund-subtracting, filter-honouring and distinct-count metrics. */
    public static FixtureMapping enhancedMapping() {
        List<FixtureMapping.MetricBinding> metrics = new ArrayList<>(standardMetrics());
        metrics.add(metric("order.refunded_amount", "member.orders", ORDER_AMOUNT, Map.of(), ORDER_PAID_AT));
        metrics.add(metric("order.filtered_amount", "member.orders", ORDER_AMOUNT,
                Map.of("status", ORDER_STATUS), ORDER_PAID_AT));
        metrics.add(metric("order.distinct_channel", "member.orders", ORDER_CHANNEL, Map.of(), ORDER_PAID_AT));
        return FixtureMapping.publish(
                "fixture-mapping-v2", standardFields(), standardAttributes(), metrics, standardRelations());
    }

    /** The mapping for the minimal single-currency snapshot used by the currency tests. */
    public static FixtureMapping singleCurrencyMapping() {
        return FixtureMapping.publish(
                "fixture-mapping-single-v1",
                List.of(new FixtureMapping.FieldBinding("source.id", SubjectKind.MEMBER, MEMBER_ID)),
                List.of(new FixtureMapping.AttributeBinding(
                        "member.single_balance", SubjectKind.MEMBER, memberVersion(), MEMBER_BALANCE)),
                List.of(),
                List.of());
    }

    /** The mapping for the events snapshot, including the event relation and its event-time field. */
    public static FixtureMapping eventsMapping() {
        return FixtureMapping.publish(
                "fixture-mapping-events-v1",
                List.of(
                        new FixtureMapping.FieldBinding("source.id", SubjectKind.MEMBER, MEMBER_ID),
                        new FixtureMapping.FieldBinding("event.member_id", SubjectKind.EVENT, EVENT_MEMBER_ID),
                        new FixtureMapping.FieldBinding("event.occurred_at", SubjectKind.EVENT, EVENT_OCCURRED_AT)),
                List.of(new FixtureMapping.AttributeBinding(
                        "event.type", SubjectKind.EVENT, MetadataSchemaVersions.SUPPORTED, EVENT_TYPE)),
                List.of(),
                List.of(new FixtureMapping.RelationBinding(
                        "member.events", SubjectKind.MEMBER, memberVersion(),
                        SubjectKind.EVENT, MetadataSchemaVersions.SUPPORTED, MEMBER_ID, EVENT_MEMBER_ID)));
    }

    /** The server-owned DataScope binding for the standard trusted context. */
    public static FixtureScopeBinding standardScope() {
        return FixtureScopeBinding.publish("fixture-scope-v1", List.of(
                new FixtureScopeBinding.ScopeEntry(SCOPE_ID, UNIVERSE)));
    }

    /** A scope binding that excludes m-005 and m-006, for DataScope-ordering tests. */
    public static FixtureScopeBinding restrictedScope() {
        return FixtureScopeBinding.publish("fixture-scope-v1", List.of(
                new FixtureScopeBinding.ScopeEntry(SCOPE_ID, MemberIdSet.of("m-001", "m-002", "m-003", "m-004"))));
    }

    /** A scope binding that declares no entry for the plan's DataScope at all. */
    public static FixtureScopeBinding emptyScope() {
        return FixtureScopeBinding.publish("fixture-scope-v1", List.of());
    }

    // ------------------------------------------------------------------ custom snapshots

    /** A snapshot whose only monetary attribute requires an already-single currency. */
    public static MetadataSnapshot singleCurrencySnapshot() {
        return MetadataSnapshot.publish(
                MetadataSchemaVersions.SUPPORTED,
                QueryPlanFixtures.SNAPSHOT_ID,
                QueryPlanFixtures.SNAPSHOT_VERSION,
                MetadataFixtures.TENANT,
                MetadataFixtures.PROGRAM,
                List.of(MetadataFixtures.moneyAttribute(
                        "member.single_balance", "Single Balance", CurrencySemantics.SINGLE_CURRENCY_ONLY)),
                List.of(),
                List.of());
    }

    /** A snapshot holding a member-to-events relation and an event type attribute. */
    public static MetadataSnapshot eventsSnapshot() {
        EntityVersionRef eventVersion = new EntityVersionRef(SubjectKind.EVENT, MetadataSchemaVersions.SUPPORTED);
        AttributeDefinition eventType = new AttributeDefinition(
                "event.type", eventVersion, SubjectKind.EVENT, "Event Type",
                SemanticDataType.ENUM, null, List.of(),
                List.of(ComparisonOperator.EQUAL, ComparisonOperator.NOT_EQUAL, ComparisonOperator.IN,
                        ComparisonOperator.NOT_IN, ComparisonOperator.IS_NULL, ComparisonOperator.IS_NOT_NULL),
                List.of(new EnumValueDefinition("LOGIN", List.of()), new EnumValueDefinition("PURCHASE", List.of())),
                NullSemantics.EXCLUDED, CurrencySemantics.NOT_APPLICABLE, List.of(), List.of(), null);
        RelationDefinition events = new RelationDefinition(
                "member.events", "Member Events", List.of(),
                SubjectKind.MEMBER, MetadataFixtures.memberVersion(),
                SubjectKind.EVENT, eventVersion,
                RelationCardinality.ONE_TO_MANY,
                new RelationScope(RelationJoinKind.CORRELATED_BY_SOURCE_KEY, "source.id", "event.member_id"),
                new RelationEventTimeCapability(EventTimeSemantics.EVENT_TIME, "event.occurred_at"),
                NullSemantics.EXCLUDED, EmptySetBehavior.NULL, List.of(), List.of(), null);
        return MetadataSnapshot.publish(
                MetadataSchemaVersions.SUPPORTED,
                QueryPlanFixtures.SNAPSHOT_ID,
                QueryPlanFixtures.SNAPSHOT_VERSION,
                MetadataFixtures.TENANT,
                MetadataFixtures.PROGRAM,
                List.of(eventType),
                List.of(),
                List.of(events));
    }

    /** The standard snapshot plus the refund-subtracting, filter-honouring and distinct-count metrics. */
    public static MetadataSnapshot enhancedSnapshot() {
        List<MetricDefinition> metrics = new ArrayList<>(SegmentValidationFixtures.metrics());
        metrics.add(enhancedMetric("order.refunded_amount", OrderMetricKind.REFUND));
        metrics.add(enhancedMetric("order.filtered_amount", OrderMetricKind.FILTERED));
        metrics.add(enhancedMetric("order.distinct_channel", OrderMetricKind.DISTINCT));
        return MetadataSnapshot.publish(
                MetadataSchemaVersions.SUPPORTED,
                QueryPlanFixtures.SNAPSHOT_ID,
                QueryPlanFixtures.SNAPSHOT_VERSION,
                MetadataFixtures.TENANT,
                MetadataFixtures.PROGRAM,
                SegmentValidationFixtures.attributes(),
                metrics,
                SegmentValidationFixtures.relations());
    }

    private enum OrderMetricKind {
        REFUND,
        FILTERED,
        DISTINCT
    }

    private static MetricDefinition enhancedMetric(String id, OrderMetricKind kind) {
        AggregateFunction aggregation = kind == OrderMetricKind.DISTINCT
                ? AggregateFunction.COUNT_DISTINCT
                : AggregateFunction.SUM;
        SemanticDataType resultType = kind == OrderMetricKind.DISTINCT
                ? SemanticDataType.INTEGER
                : SemanticDataType.MONEY;
        MetadataFilter filter = kind == OrderMetricKind.FILTERED
                ? new MetadataFilter(List.of(new MetadataFilterEntry("status", List.of("PAID"))))
                : MetadataFilter.empty();
        RefundTreatment refunds = kind == OrderMetricKind.REFUND
                ? RefundTreatment.SUBTRACT
                : RefundTreatment.IGNORE;
        CurrencySemantics currency = resultType == SemanticDataType.MONEY
                ? CurrencySemantics.CONVERT_TO_REPORTING_CURRENCY
                : CurrencySemantics.NOT_APPLICABLE;
        return new MetricDefinition(
                id, MetadataFixtures.memberVersion(), SubjectKind.MEMBER, id,
                resultType, null, List.of(),
                List.of(ComparisonOperator.EQUAL, ComparisonOperator.NOT_EQUAL,
                        ComparisonOperator.GREATER_THAN, ComparisonOperator.LESS_THAN,
                        ComparisonOperator.IS_NULL, ComparisonOperator.IS_NOT_NULL),
                aggregation, "member.orders", "order.paid_at", EventTimeSemantics.EVENT_TIME,
                filter, refunds, currency,
                List.of(TimeWindowKind.ROLLING, TimeWindowKind.LIFETIME, TimeWindowKind.FIXED),
                WindowBoundarySemantics.INCLUSIVE_START_EXCLUSIVE_END,
                NullSemantics.UNKNOWN_VALUE, AggregateEmptySet.zero(resultType),
                List.of(), List.of(), null);
    }

    // ------------------------------------------------------------------ plans and execution

    /** Plans a predicate against the standard accepted T08 test snapshot and trusted context. */
    public static SemanticQueryPlan plan(Predicate predicate) {
        return plan(SegmentValidationFixtures.snapshot(), predicate);
    }

    /** Plans a predicate against an explicit snapshot and the standard trusted context. */
    public static SemanticQueryPlan plan(MetadataSnapshot snapshot, Predicate predicate) {
        return QueryPlanFixtures.plan(
                QueryPlanFixtures.document(snapshot, predicate), snapshot, SegmentValidationFixtures.context());
    }

    /** The one production executor under test. */
    public static final FixtureExecutor EXECUTOR = new FixtureExecutor();

    /** A hash-pinned CNY-reporting FX snapshot with a single USD rate, observed before the evaluation instant. */
    public static FxRateSnapshot cnyFx(String usdRate) {
        return FxRateSnapshot.publish("fx-001", "1", "CNY", Instant.parse("2026-10-01T00:00:00Z"),
                Map.of("USD", new java.math.BigDecimal(usdRate)));
    }

    /** Executes a plan through the production executor with no FX snapshot. */
    public static FixtureExecutionResult execute(
            SemanticQueryPlan plan, FixtureDataset dataset, FixtureMapping mapping) {
        return EXECUTOR.execute(plan, dataset, mapping, standardScope(), null);
    }

    /** Executes a plan through the production executor with an explicit scope binding and FX snapshot. */
    public static FixtureExecutionResult execute(
            SemanticQueryPlan plan, FixtureDataset dataset, FixtureMapping mapping,
            FixtureScopeBinding scope, FxRateSnapshot fxRateSnapshot) {
        return EXECUTOR.execute(plan, dataset, mapping, scope, fxRateSnapshot);
    }

    // ------------------------------------------------------------------ dataset introspection

    /** The member ids in one dataset, in declared order. */
    public static List<String> memberIds(FixtureDataset dataset) {
        List<String> ids = new ArrayList<>();
        for (DatasetRow.MemberRow member : dataset.members()) {
            ids.add(member.memberId());
        }
        return List.copyOf(ids);
    }
}