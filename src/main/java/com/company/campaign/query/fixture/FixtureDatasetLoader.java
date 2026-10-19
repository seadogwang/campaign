package com.company.campaign.query.fixture;

import com.company.campaign.evaluation.segment.contract.EvaluationException;
import com.company.campaign.evaluation.segment.contract.FixtureDescriptor;
import com.company.campaign.evaluation.segment.contract.FixtureRole;
import com.company.campaign.evaluation.segment.contract.MemberIdSet;
import com.company.campaign.evaluation.segment.loader.EvaluationRoot;
import com.company.campaign.evaluation.segment.loader.RealPathResolver;
import com.company.campaign.shared.canonical.CanonicalJson;
import com.company.campaign.shared.error.ContractException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Strict, deterministic loader for the reviewed corpus-v1 fixture payload shape.
 *
 * <p>It answers exactly one question and refuses every shortcut to it: <em>are these bytes the dataset the
 * reviewed descriptor describes?</em> The payload is resolved beneath the explicitly supplied evaluation root
 * through the accepted T03 boundary — lexical reference validation plus mandatory real-path containment, with
 * the bytes read from the already-resolved real path — its SHA-256 is checked against the descriptor before a
 * single field is interpreted, and only then is it parsed.
 *
 * <p>Parsing is closed and strict: an object field outside the declared dataset shape, a required field that
 * is missing, a value of the wrong type, a duplicate member/order/event/refund identifier, a row relating to
 * an absent member or order, a payload identity or time context that contradicts the descriptor, or a member
 * set that is not exactly the declared universe all fail with a bounded {@link FixtureErrorCodes} code that
 * echoes no payload value. Nothing here reads a clock, a default timezone, a locale default or ambient tenant
 * state.
 */
public final class FixtureDatasetLoader {

    private static final CanonicalJson CANONICAL_JSON = new CanonicalJson();

    private static final Set<String> TOP_LEVEL_FIELDS = Set.of(
            "asOf", "currencies", "dataset", "events", "fixtureId", "members", "orders", "refunds",
            "role", "statuses", "watermark");

    private static final Set<String> MEMBER_FIELDS = Set.of(
            "memberId", "tier", "status", "city", "joinDate", "name", "age", "score", "flag",
            "lastSeenAt", "balance", "balanceCurrency");
    private static final Set<String> MEMBER_REQUIRED =
            Set.of("memberId", "tier", "status", "city", "joinDate");

    private static final Set<String> ORDER_FIELDS = Set.of(
            "orderId", "memberId", "amount", "currency", "paidAt", "refunded", "status", "channel");
    private static final Set<String> ORDER_REQUIRED =
            Set.of("orderId", "memberId", "amount", "currency", "paidAt", "refunded", "status");

    private static final Set<String> EVENT_FIELDS = Set.of("eventId", "memberId", "occurredAt", "type");
    private static final Set<String> EVENT_REQUIRED = Set.of("eventId", "memberId", "occurredAt", "type");

    private static final Set<String> REFUND_FIELDS = Set.of("orderId", "amount");
    private static final Set<String> REFUND_REQUIRED = Set.of("orderId", "amount");

    private final EvaluationRoot root;
    private final RealPathResolver realPathResolver;

    /** Creates a loader that resolves payloads beneath the explicitly supplied evaluation root. */
    public FixtureDatasetLoader(EvaluationRoot root) {
        this.root = Objects.requireNonNull(root, "root");
        this.realPathResolver = new RealPathResolver(root.evaluationRoot());
    }

    /** Loads, hash-verifies, strictly parses and binds one fixture dataset described by {@code descriptor}. */
    public FixtureDataset load(FixtureDescriptor descriptor) {
        if (descriptor == null) {
            throw new FixtureException(FixtureErrorCodes.FIXTURE_INPUT_MISSING, "descriptor",
                    "Loading a fixture dataset requires a descriptor.");
        }
        byte[] bytes = readVerifiedBytes(descriptor);
        ObjectNode payload = parsePayload(bytes);
        return bind(descriptor, payload);
    }

    // ------------------------------------------------------------------ bytes and containment

    private byte[] readVerifiedBytes(FixtureDescriptor descriptor) {
        Path candidate;
        try {
            candidate = root.resolve(descriptor.payloadPath());
        } catch (EvaluationException exception) {
            throw new FixtureException(FixtureErrorCodes.FIXTURE_PAYLOAD_NOT_FOUND, "payloadPath",
                    "A fixture payload reference is not a resolvable asset reference beneath the evaluation root.");
        }
        Path file;
        try {
            file = realPathResolver.resolveExistingFile(candidate, descriptor.payloadPath());
        } catch (EvaluationException exception) {
            throw new FixtureException(FixtureErrorCodes.FIXTURE_PAYLOAD_NOT_FOUND, "payloadPath",
                    "A fixture payload does not resolve to a regular file beneath the real evaluation root.");
        }
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(file);
        } catch (IOException exception) {
            throw new FixtureException(FixtureErrorCodes.FIXTURE_PAYLOAD_UNREADABLE, "payloadBytes",
                    "A fixture payload cannot be read as bytes.");
        }
        if (!descriptor.payloadHash().matches(bytes)) {
            throw new FixtureException(FixtureErrorCodes.FIXTURE_HASH_MISMATCH, "payloadHash",
                    "A fixture payload's bytes do not match the hash the descriptor declares for them.");
        }
        return bytes;
    }

    private static ObjectNode parsePayload(byte[] bytes) {
        String json = decodeUtf8(bytes);
        JsonNode tree;
        try {
            tree = CANONICAL_JSON.readTree(json);
        } catch (ContractException exception) {
            throw malformed("payloadJson");
        }
        if (!tree.isObject()) {
            throw malformed("payloadShape");
        }
        ObjectNode object = (ObjectNode) tree;
        requireOnlyFields(object, TOP_LEVEL_FIELDS, "payloadShape");
        return object;
    }

    private static String decodeUtf8(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException exception) {
            throw malformed("payloadEncoding");
        }
    }

    // ------------------------------------------------------------------ binding

    private static FixtureDataset bind(FixtureDescriptor descriptor, ObjectNode payload) {
        requireOnlyFields(payload, TOP_LEVEL_FIELDS, "payloadShape");

        String fixtureId = requireText(payload, "fixtureId");
        if (!descriptor.fixtureId().equals(fixtureId)) {
            throw identity("fixtureId");
        }
        String dataset = payload.hasNonNull("dataset") ? requireText(payload, "dataset") : fixtureId;
        FixtureRole role = parseRole(requireText(payload, "role"));
        if (role != descriptor.role()) {
            throw identity("fixtureRole");
        }
        Instant asOf = requireInstant(payload, "asOf");
        Instant watermark = requireInstant(payload, "watermark");
        if (!descriptor.asOf().equals(asOf) || !descriptor.dataWatermark().equals(watermark)) {
            throw identity("fixtureTimeContext");
        }

        List<String> currencies = optionalTextArray(payload, "currencies");
        List<String> statuses = optionalTextArray(payload, "statuses");

        List<DatasetRow.MemberRow> members = parseMembers(requireArray(payload, "members"));
        List<DatasetRow.OrderRow> orders = parseOrders(requireArray(payload, "orders"));
        List<DatasetRow.EventRow> events = parseEvents(requireArray(payload, "events"));
        List<RefundRow> refunds = parseRefunds(requireArray(payload, "refunds"));

        Set<String> memberIds = new HashSet<>();
        for (DatasetRow.MemberRow member : members) {
            memberIds.add(member.memberId());
        }
        Set<String> orderIds = new HashSet<>();
        for (DatasetRow.OrderRow order : orders) {
            orderIds.add(order.orderId());
        }
        for (DatasetRow.OrderRow order : orders) {
            if (!memberIds.contains(order.memberId())) {
                throw dangling("orderMember");
            }
        }
        for (DatasetRow.EventRow event : events) {
            if (!memberIds.contains(event.memberId())) {
                throw dangling("eventMember");
            }
        }
        for (RefundRow refund : refunds) {
            if (!orderIds.contains(refund.orderId())) {
                throw dangling("refundOrder");
            }
        }
        requireUniverse(descriptor.memberUniverse(), memberIds);

        return new FixtureDataset(
                descriptor.fixtureId(),
                descriptor.fixtureVersion(),
                descriptor.role(),
                dataset,
                asOf,
                descriptor.timezone(),
                watermark,
                descriptor.memberUniverse(),
                descriptor.payloadHash(),
                currencies,
                statuses,
                members,
                orders,
                events,
                refunds);
    }

    /**
     * Requires mathematical equality between the payload's member-ID set and the descriptor universe: neither
     * a payload member outside the universe nor a universe member absent from the payload may pass. The
     * payload set is duplicate-free ({@link #parseMembers}) and the universe rejects duplicates, so two
     * equal-size sets whose payload members are all contained in the universe are exactly equal. The size
     * check catches omitted universe members and the containment check catches undeclared payload members,
     * both failing closed with the same bounded, non-disclosing code.
     */
    private static void requireUniverse(MemberIdSet universe, Set<String> memberIds) {
        if (universe.size() != memberIds.size()) {
            throw universeMismatch();
        }
        for (String memberId : memberIds) {
            if (!universe.contains(memberId)) {
                throw universeMismatch();
            }
        }
    }

    // ------------------------------------------------------------------ collections

    private static List<DatasetRow.MemberRow> parseMembers(JsonNode array) {
        List<DatasetRow.MemberRow> members = new ArrayList<>(array.size());
        Set<String> seen = new LinkedHashSet<>();
        for (JsonNode node : array) {
            ObjectNode member = requireObject(node, "memberRow");
            requireOnlyFields(member, MEMBER_FIELDS, "memberRow");
            requirePresentFields(member, MEMBER_REQUIRED, "memberRow");
            String id = requireText(member, "memberId");
            if (!seen.add(id)) {
                throw duplicate("memberId");
            }
            members.add(new DatasetRow.MemberRow(
                    id,
                    optionalText(member, "tier"),
                    optionalText(member, "status"),
                    optionalText(member, "city"),
                    requireDate(member, "joinDate"),
                    optionalText(member, "name"),
                    optionalInteger(member, "age"),
                    optionalDecimal(member, "score"),
                    optionalBoolean(member, "flag"),
                    optionalInstant(member, "lastSeenAt"),
                    optionalDecimal(member, "balance"),
                    optionalText(member, "balanceCurrency")));
        }
        return members;
    }

    private static List<DatasetRow.OrderRow> parseOrders(JsonNode array) {
        List<DatasetRow.OrderRow> orders = new ArrayList<>(array.size());
        Set<String> seen = new LinkedHashSet<>();
        for (JsonNode node : array) {
            ObjectNode order = requireObject(node, "orderRow");
            requireOnlyFields(order, ORDER_FIELDS, "orderRow");
            requirePresentFields(order, ORDER_REQUIRED, "orderRow");
            String id = requireText(order, "orderId");
            if (!seen.add(id)) {
                throw duplicate("orderId");
            }
            orders.add(new DatasetRow.OrderRow(
                    id,
                    requireText(order, "memberId"),
                    requireDecimal(order, "amount"),
                    requireText(order, "currency"),
                    requireInstant(order, "paidAt"),
                    requireDecimal(order, "refunded"),
                    optionalText(order, "status"),
                    optionalText(order, "channel")));
        }
        return orders;
    }

    private static List<DatasetRow.EventRow> parseEvents(JsonNode array) {
        List<DatasetRow.EventRow> events = new ArrayList<>(array.size());
        Set<String> seen = new LinkedHashSet<>();
        for (JsonNode node : array) {
            ObjectNode event = requireObject(node, "eventRow");
            requireOnlyFields(event, EVENT_FIELDS, "eventRow");
            requirePresentFields(event, EVENT_REQUIRED, "eventRow");
            String id = requireText(event, "eventId");
            if (!seen.add(id)) {
                throw duplicate("eventId");
            }
            events.add(new DatasetRow.EventRow(
                    id,
                    requireText(event, "memberId"),
                    requireInstant(event, "occurredAt"),
                    optionalText(event, "type")));
        }
        return events;
    }

    private static List<RefundRow> parseRefunds(JsonNode array) {
        List<RefundRow> refunds = new ArrayList<>(array.size());
        // A refund has no identity field of its own, so orderId is its identity key: two refund rows for one
        // order would silently change aggregate semantics by summing twice, so reject the second before the
        // dataset is constructed.
        Set<String> seen = new LinkedHashSet<>();
        for (JsonNode node : array) {
            ObjectNode refund = requireObject(node, "refundRow");
            requireOnlyFields(refund, REFUND_FIELDS, "refundRow");
            requirePresentFields(refund, REFUND_REQUIRED, "refundRow");
            String orderId = requireText(refund, "orderId");
            if (!seen.add(orderId)) {
                throw duplicate("refundOrderId");
            }
            refunds.add(new RefundRow(orderId, requireDecimal(refund, "amount")));
        }
        return refunds;
    }

    // ------------------------------------------------------------------ field readers

    private static void requireOnlyFields(ObjectNode object, Set<String> allowed, String invariant) {
        Iterator<String> names = object.fieldNames();
        while (names.hasNext()) {
            if (!allowed.contains(names.next())) {
                throw new FixtureException(FixtureErrorCodes.FIXTURE_UNKNOWN_FIELD, invariant,
                        "A fixture payload object declares a field outside the closed dataset shape.");
            }
        }
    }

    private static void requirePresentFields(ObjectNode object, Set<String> required, String invariant) {
        for (String field : required) {
            if (!object.has(field)) {
                throw new FixtureException(FixtureErrorCodes.FIXTURE_MISSING_FIELD, invariant,
                        "A fixture payload object omits a required dataset field.");
            }
        }
    }

    private static JsonNode requireArray(ObjectNode object, String field) {
        JsonNode node = object.get(field);
        if (node == null || !node.isArray()) {
            throw malformed("payloadCollections");
        }
        return node;
    }

    private static ObjectNode requireObject(JsonNode node, String invariant) {
        if (node == null || !node.isObject()) {
            throw malformed(invariant);
        }
        return (ObjectNode) node;
    }

    private static String requireText(ObjectNode object, String field) {
        JsonNode node = object.get(field);
        if (node == null || !node.isTextual()) {
            throw malformed("payloadFieldType");
        }
        return node.textValue();
    }

    private static String optionalText(ObjectNode object, String field) {
        JsonNode node = object.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isTextual()) {
            throw malformed("payloadFieldType");
        }
        return node.textValue();
    }

    private static BigDecimal requireDecimal(ObjectNode object, String field) {
        JsonNode node = object.get(field);
        BigDecimal value = decimal(node);
        if (value == null) {
            throw malformed("payloadFieldType");
        }
        return value;
    }

    private static BigDecimal optionalDecimal(ObjectNode object, String field) {
        JsonNode node = object.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        BigDecimal value = decimal(node);
        if (value == null) {
            throw malformed("payloadFieldType");
        }
        return value;
    }

    private static BigDecimal decimal(JsonNode node) {
        if (node == null) {
            return null;
        }
        if (node.isNumber()) {
            return node.decimalValue();
        }
        if (node.isTextual()) {
            try {
                return new BigDecimal(node.textValue());
            } catch (NumberFormatException exception) {
                throw malformed("payloadFieldType");
            }
        }
        return null;
    }

    private static Integer optionalInteger(ObjectNode object, String field) {
        JsonNode node = object.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isIntegralNumber() || !node.canConvertToInt()) {
            throw malformed("payloadFieldType");
        }
        return node.intValue();
    }

    private static Boolean optionalBoolean(ObjectNode object, String field) {
        JsonNode node = object.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isBoolean()) {
            throw malformed("payloadFieldType");
        }
        return node.booleanValue();
    }

    private static String requireDate(ObjectNode object, String field) {
        String text = requireText(object, field);
        try {
            return LocalDate.parse(text).toString();
        } catch (DateTimeException exception) {
            throw malformed("payloadFieldType");
        }
    }

    private static Instant requireInstant(ObjectNode object, String field) {
        String text = requireText(object, field);
        try {
            return Instant.parse(text);
        } catch (DateTimeException exception) {
            throw malformed("payloadFieldType");
        }
    }

    private static Instant optionalInstant(ObjectNode object, String field) {
        String text = optionalText(object, field);
        if (text == null) {
            return null;
        }
        try {
            return Instant.parse(text);
        } catch (DateTimeException exception) {
            throw malformed("payloadFieldType");
        }
    }

    private static List<String> optionalTextArray(ObjectNode object, String field) {
        JsonNode node = object.get(field);
        if (node == null || node.isNull()) {
            return List.of();
        }
        if (!node.isArray()) {
            throw malformed("payloadFieldType");
        }
        List<String> values = new ArrayList<>(node.size());
        for (JsonNode element : node) {
            if (!element.isTextual()) {
                throw malformed("payloadFieldType");
            }
            values.add(element.textValue());
        }
        return values;
    }

    private static FixtureRole parseRole(String role) {
        try {
            return FixtureRole.valueOf(role);
        } catch (IllegalArgumentException exception) {
            throw malformed("fixtureRole");
        }
    }

    // ------------------------------------------------------------------ failures

    private static FixtureException malformed(String invariant) {
        return new FixtureException(FixtureErrorCodes.FIXTURE_PAYLOAD_MALFORMED, invariant,
                "A fixture payload is not a well-formed corpus-v1 dataset.");
    }

    private static FixtureException duplicate(String invariant) {
        return new FixtureException(FixtureErrorCodes.FIXTURE_DUPLICATE_ID, invariant,
                "A fixture payload declares the same row identifier more than once.");
    }

    private static FixtureException dangling(String invariant) {
        return new FixtureException(FixtureErrorCodes.FIXTURE_DANGLING_REFERENCE, invariant,
                "A fixture payload row relates to a member or order the payload does not declare.");
    }

    private static FixtureException universeMismatch() {
        return new FixtureException(FixtureErrorCodes.FIXTURE_UNIVERSE_MISMATCH, "memberUniverse",
                "A fixture payload's member set is not exactly the descriptor's declared member universe.");
    }

    private static FixtureException identity(String invariant) {
        return new FixtureException(FixtureErrorCodes.FIXTURE_IDENTITY_MISMATCH, invariant,
                "A fixture payload's declared identity or time context contradicts its descriptor.");
    }

    /** The evaluation root this loader was configured with. */
    public EvaluationRoot root() {
        return root;
    }
}