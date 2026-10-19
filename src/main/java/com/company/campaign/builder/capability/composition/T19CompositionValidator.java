package com.company.campaign.builder.capability.composition;

import com.company.campaign.builder.compiler.AuthoringPolicyValidator;
import com.company.campaign.builder.compiler.AuthoringValidationRequest;
import com.company.campaign.builder.compiler.ResolvedCatalogReference;
import com.company.campaign.builder.compiler.ResolvedComponent;
import com.company.campaign.contracts.Issue;
import com.company.campaign.contracts.IssueSeverity;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The deterministic T19 composition policy validator.
 *
 * <p>It implements {@link AuthoringPolicyValidator}, so it can be the frozen authoring-policy validator of a
 * compile or one element of a deterministic validator chain, and it reports a stable, ordered, bounded
 * {@link Issue} list. It is a pure function of its request and its {@link T19PackIndex}: the same request and
 * index always yield the same issues in the same order, and it reads no repository, provider, clock, random
 * source or mutable registry.
 *
 * <p>It validates exactly the section-5 rules and nothing else:
 * <ul>
 *   <li>every T19 component's capability is enabled in the supplied immutable pack index at its exact
 *       contract version and frozen composition slot, on a runtime this build can author;</li>
 *   <li>a Flow Message node's {@code messageLogicalId} and a Flow Reward node's {@code promotionLogicalId}
 *       target an existing component of the required capability;</li>
 *   <li>at most one Flow Start, one Flow Reward, one basic Schedule and one Governance component;</li>
 *   <li>a Reward flow requires Governance, with a matching currency and a conservative grant/face-value
 *       exposure covered by the declared limits;</li>
 *   <li>the relative manifest slot order is Segment -&gt; Message -&gt; Promotion -&gt; Flow -&gt; Schedule
 *       -&gt; Governance;</li>
 *   <li>no T19 component carries a Segment reference or a second editable truth.</li>
 * </ul>
 *
 * <p>It deliberately does <em>not</em> validate the Journey graph, edges, reachability, cycles, degree,
 * schedule occurrence generation, DST execution or publication: those belong to T20-T23. Partial development
 * compositions are valid whenever their own dependencies are satisfied — a Message, Reward or Schedule is
 * never required merely because its pack exists.
 */
public final class T19CompositionValidator implements AuthoringPolicyValidator {

    /** The exact accepted initial T19 capability codes this validator reasons about. */
    public static final List<String> T19_CAPABILITY_CODES = List.of(
            "message.template",
            "promotion.reference",
            "flow.start",
            "flow.message",
            "flow.wait",
            "flow.condition",
            "flow.reward",
            "flow.end",
            "schedule.basic",
            "governance.limit");

    /** Largest number of issues one evaluation reports; beyond this the list is truncated deterministically. */
    public static final int MAX_ISSUES = 64;

    private static final String MESSAGE_CAPABILITY = "message.template";
    private static final String PROMOTION_CAPABILITY = "promotion.reference";
    private static final String FLOW_START_CAPABILITY = "flow.start";
    private static final String FLOW_MESSAGE_CAPABILITY = "flow.message";
    private static final String FLOW_REWARD_CAPABILITY = "flow.reward";
    private static final String SCHEDULE_CAPABILITY = "schedule.basic";
    private static final String GOVERNANCE_CAPABILITY = "governance.limit";

    private static final String MESSAGE_REFERENCE_FIELD = "messageLogicalId";
    private static final String PROMOTION_REFERENCE_FIELD = "promotionLogicalId";
    private static final String CURRENCY_FIELD = "currency";
    private static final String QUANTITY_FIELD = "quantity";
    private static final String FACE_VALUE_FIELD = "faceValue";
    private static final String MAX_GRANTS_FIELD = "maxRewardGrants";
    private static final String MAX_FACE_VALUE_FIELD = "maxRewardFaceValue";

    private static final String SEGMENT_REFERENCE_PREFIX = "SEGMENT";

    /** Closed, value-free messages: a rejection never echoes a rejected value. */
    private static final String MESSAGE_INPUT = "The T19 composition input was absent.";
    private static final String MESSAGE_NOT_IN_CATALOG =
            "A T19 component's capability is not enabled in the frozen pack index.";
    private static final String MESSAGE_NOT_ENABLED =
            "A T19 component's capability pack is disabled for authoring.";
    private static final String MESSAGE_CONTRACT_VERSION =
            "A T19 component's contract version is not the enabled pack's exact contract version.";
    private static final String MESSAGE_SLOT =
            "A T19 component's composition slot is not the enabled pack's exact frozen slot.";
    private static final String MESSAGE_RUNTIME =
            "A T19 component's pack declares a runtime this build cannot author or preview.";
    private static final String MESSAGE_SEGMENT_REFERENCE =
            "A T19 component must not carry a Segment reference.";
    private static final String MESSAGE_START_CARDINALITY =
            "A campaign must hold at most one Flow Start node.";
    private static final String MESSAGE_REWARD_CARDINALITY =
            "A campaign must hold at most one Flow Reward node.";
    private static final String MESSAGE_SCHEDULE_CARDINALITY =
            "A campaign must hold at most one basic schedule.";
    private static final String MESSAGE_GOVERNANCE_CARDINALITY =
            "A campaign must hold at most one governance limit.";
    private static final String MESSAGE_MESSAGE_REFERENCE =
            "A Flow Message node must reference an existing message.template component.";
    private static final String MESSAGE_REWARD_REFERENCE =
            "A Flow Reward node must reference an existing promotion.reference component.";
    private static final String MESSAGE_GOVERNANCE_REQUIRED =
            "A Flow Reward node requires a governance.limit component.";
    private static final String MESSAGE_CURRENCY =
            "A reward's currency must match the governance limit's currency.";
    private static final String MESSAGE_GRANTS_EXCEEDED =
            "The conservative reward grant exposure exceeds the governance grant limit.";
    private static final String MESSAGE_FACE_VALUE_EXCEEDED =
            "The conservative reward face-value exposure exceeds the governance face-value limit.";
    private static final String MESSAGE_SLOT_ORDER =
            "The relative manifest slot order must be Segment -> Message -> Promotion -> Flow -> Schedule -> Governance.";

    private final T19PackIndex index;

    public T19CompositionValidator(T19PackIndex index) {
        this.index = Objects.requireNonNull(index, "index");
    }

    /** The one supplied immutable pack index this validator consults. */
    public T19PackIndex index() {
        return index;
    }

    @Override
    public List<Issue> validate(AuthoringValidationRequest request) {
        if (request == null) {
            return List.of(error(T19CompositionErrorCodes.INPUT_MISSING, null, MESSAGE_INPUT));
        }
        List<ResolvedComponent> components = request.components();
        List<Issue> issues = new ArrayList<>();
        Map<String, ResolvedComponent> byLogicalId = new HashMap<>();

        for (ResolvedComponent component : components) {
            if (component == null) {
                issues.add(error(T19CompositionErrorCodes.INPUT_MISSING, null, MESSAGE_INPUT));
                continue;
            }
            byLogicalId.putIfAbsent(component.logicalId(), component);
        }

        validateComponentFacts(components, issues);
        validateCardinality(components, issues);
        validateReferences(components, byLogicalId, issues);
        validateRewardGovernance(components, issues);
        validateSlotOrder(components, issues);

        return bounded(issues);
    }

    // ------------------------------------------------------------------ per-component pack agreement

    private void validateComponentFacts(List<ResolvedComponent> components, List<Issue> issues) {
        for (ResolvedComponent component : components) {
            if (component == null || !T19_CAPABILITY_CODES.contains(component.capabilityCode())) {
                continue;
            }
            T19PackFacts facts = index.facts(component.capabilityCode()).orElse(null);
            if (facts == null) {
                issues.add(error(T19CompositionErrorCodes.CAPABILITY_NOT_IN_CATALOG,
                        component.logicalId(), MESSAGE_NOT_IN_CATALOG));
                continue;
            }
            if (!facts.enabled()) {
                issues.add(error(T19CompositionErrorCodes.CAPABILITY_NOT_ENABLED,
                        component.logicalId(), MESSAGE_NOT_ENABLED));
            }
            if (!facts.contractVersion().equals(component.contractVersion())) {
                issues.add(error(T19CompositionErrorCodes.CONTRACT_VERSION_MISMATCH,
                        component.logicalId(), MESSAGE_CONTRACT_VERSION));
            }
            if (!facts.slot().equals(component.slot())) {
                issues.add(error(T19CompositionErrorCodes.SLOT_MISMATCH,
                        component.logicalId(), MESSAGE_SLOT));
            }
            if (!index.runtimeCompatible(facts)) {
                issues.add(error(T19CompositionErrorCodes.RUNTIME_INCOMPATIBLE,
                        component.logicalId(), MESSAGE_RUNTIME));
            }
            for (ResolvedCatalogReference reference : component.references()) {
                if (isSegmentReference(reference)) {
                    issues.add(error(T19CompositionErrorCodes.SEGMENT_REFERENCE,
                            component.logicalId(), MESSAGE_SEGMENT_REFERENCE));
                }
            }
        }
    }

    // ------------------------------------------------------------------ cardinality

    private void validateCardinality(List<ResolvedComponent> components, List<Issue> issues) {
        cardinality(components, FLOW_START_CAPABILITY,
                T19CompositionErrorCodes.START_CARDINALITY, MESSAGE_START_CARDINALITY, issues);
        cardinality(components, FLOW_REWARD_CAPABILITY,
                T19CompositionErrorCodes.REWARD_CARDINALITY, MESSAGE_REWARD_CARDINALITY, issues);
        cardinality(components, SCHEDULE_CAPABILITY,
                T19CompositionErrorCodes.SCHEDULE_CARDINALITY, MESSAGE_SCHEDULE_CARDINALITY, issues);
        cardinality(components, GOVERNANCE_CAPABILITY,
                T19CompositionErrorCodes.GOVERNANCE_CARDINALITY, MESSAGE_GOVERNANCE_CARDINALITY, issues);
    }

    private static void cardinality(
            List<ResolvedComponent> components,
            String capabilityCode,
            String code,
            String message,
            List<Issue> issues) {
        List<ResolvedComponent> matches = of(components, capabilityCode);
        if (matches.size() > 1) {
            // Attribute the issue to the second component so the report is stable and points at the excess.
            issues.add(error(code, matches.get(1).logicalId(), message));
        }
    }

    // ------------------------------------------------------------------ cross-component references

    private void validateReferences(
            List<ResolvedComponent> components,
            Map<String, ResolvedComponent> byLogicalId,
            List<Issue> issues) {
        for (ResolvedComponent component : components) {
            if (component == null) {
                continue;
            }
            if (FLOW_MESSAGE_CAPABILITY.equals(component.capabilityCode())) {
                if (!targets(byLogicalId, text(component, MESSAGE_REFERENCE_FIELD), MESSAGE_CAPABILITY)) {
                    issues.add(error(T19CompositionErrorCodes.MESSAGE_REFERENCE_UNRESOLVED,
                            component.logicalId(), MESSAGE_MESSAGE_REFERENCE));
                }
            } else if (FLOW_REWARD_CAPABILITY.equals(component.capabilityCode())) {
                if (!targets(byLogicalId, text(component, PROMOTION_REFERENCE_FIELD), PROMOTION_CAPABILITY)) {
                    issues.add(error(T19CompositionErrorCodes.REWARD_REFERENCE_UNRESOLVED,
                            component.logicalId(), MESSAGE_REWARD_REFERENCE));
                }
            }
        }
    }

    private static boolean targets(
            Map<String, ResolvedComponent> byLogicalId,
            String logicalId,
            String requiredCapability) {
        if (logicalId == null) {
            return false;
        }
        ResolvedComponent target = byLogicalId.get(logicalId);
        return target != null && requiredCapability.equals(target.capabilityCode());
    }

    // ------------------------------------------------------------------ reward / governance exposure

    private void validateRewardGovernance(List<ResolvedComponent> components, List<Issue> issues) {
        List<ResolvedComponent> rewardNodes = of(components, FLOW_REWARD_CAPABILITY);
        List<ResolvedComponent> promotions = of(components, PROMOTION_CAPABILITY);
        List<ResolvedComponent> governances = of(components, GOVERNANCE_CAPABILITY);

        if (!rewardNodes.isEmpty() && governances.isEmpty()) {
            issues.add(error(T19CompositionErrorCodes.GOVERNANCE_REQUIRED,
                    rewardNodes.get(0).logicalId(), MESSAGE_GOVERNANCE_REQUIRED));
        }
        if (governances.isEmpty()) {
            // A partial composition with no governance limit has no exposure to prove; that is not a failure.
            return;
        }

        ResolvedComponent governance = governances.get(0);
        String governanceCurrency = text(governance, CURRENCY_FIELD);
        Long maxGrants = integer(governance, MAX_GRANTS_FIELD);
        BigDecimal maxFaceValue = decimal(governance, MAX_FACE_VALUE_FIELD);

        BigDecimal totalFaceValue = BigDecimal.ZERO;
        long totalGrants = 0L;
        boolean grantsKnown = true;
        boolean faceValueKnown = true;
        for (ResolvedComponent promotion : promotions) {
            String currency = text(promotion, CURRENCY_FIELD);
            if (governanceCurrency != null && currency != null && !governanceCurrency.equals(currency)) {
                issues.add(error(T19CompositionErrorCodes.REWARD_CURRENCY_MISMATCH,
                        promotion.logicalId(), MESSAGE_CURRENCY));
            }
            Long quantity = integer(promotion, QUANTITY_FIELD);
            BigDecimal faceValue = decimal(promotion, FACE_VALUE_FIELD);
            if (quantity == null) {
                grantsKnown = false;
            } else {
                totalGrants += quantity;
            }
            if (quantity == null || faceValue == null) {
                faceValueKnown = false;
            } else {
                totalFaceValue = totalFaceValue.add(faceValue.multiply(BigDecimal.valueOf(quantity)));
            }
        }

        if (grantsKnown && maxGrants != null && totalGrants > maxGrants) {
            issues.add(error(T19CompositionErrorCodes.REWARD_GRANTS_EXCEEDED,
                    governance.logicalId(), MESSAGE_GRANTS_EXCEEDED));
        }
        if (faceValueKnown && maxFaceValue != null && totalFaceValue.compareTo(maxFaceValue) > 0) {
            issues.add(error(T19CompositionErrorCodes.REWARD_FACE_VALUE_EXCEEDED,
                    governance.logicalId(), MESSAGE_FACE_VALUE_EXCEEDED));
        }
    }

    // ------------------------------------------------------------------ relative slot order

    private void validateSlotOrder(List<ResolvedComponent> components, List<Issue> issues) {
        int previousRank = -1;
        for (ResolvedComponent component : components) {
            if (component == null) {
                continue;
            }
            int rank;
            if (T19_CAPABILITY_CODES.contains(component.capabilityCode())) {
                rank = index.slotRank(component.slot().code());
            } else {
                // Any non-T19 (pre-existing Segment) component is the first editable truth and ranks before
                // every T19 slot.
                rank = T19PackIndex.SEGMENT_RANK;
            }
            if (rank < 0) {
                issues.add(error(T19CompositionErrorCodes.SLOT_ORDER_INVALID,
                        component.logicalId(), MESSAGE_SLOT_ORDER));
                continue;
            }
            if (rank < previousRank) {
                issues.add(error(T19CompositionErrorCodes.SLOT_ORDER_INVALID,
                        component.logicalId(), MESSAGE_SLOT_ORDER));
            }
            previousRank = Math.max(previousRank, rank);
        }
    }

    // ------------------------------------------------------------------ helpers

    private static List<ResolvedComponent> of(List<ResolvedComponent> components, String capabilityCode) {
        List<ResolvedComponent> matches = new ArrayList<>();
        for (ResolvedComponent component : components) {
            if (component != null && capabilityCode.equals(component.capabilityCode())) {
                matches.add(component);
            }
        }
        return matches;
    }

    private static boolean isSegmentReference(ResolvedCatalogReference reference) {
        return reference != null && reference.entityKind().startsWith(SEGMENT_REFERENCE_PREFIX);
    }

    private static String text(ResolvedComponent component, String field) {
        Object value = component.value().fields().get(field);
        return value instanceof String text ? text : null;
    }

    private static Long integer(ResolvedComponent component, String field) {
        return asLong(component.value().fields().get(field));
    }

    private static BigDecimal decimal(ResolvedComponent component, String field) {
        return asDecimal(component.value().fields().get(field));
    }

    private static Long asLong(Object value) {
        if (value instanceof BigDecimal decimal) {
            try {
                return decimal.longValueExact();
            } catch (ArithmeticException exception) {
                return null;
            }
        }
        if (value instanceof BigInteger integer) {
            try {
                return integer.longValueExact();
            } catch (ArithmeticException exception) {
                return null;
            }
        }
        if (value instanceof Integer || value instanceof Long || value instanceof Short
                || value instanceof Byte) {
            return ((Number) value).longValue();
        }
        return null;
    }

    private static BigDecimal asDecimal(Object value) {
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof Number number) {
            return new BigDecimal(number.toString());
        }
        if (value instanceof String text) {
            try {
                return new BigDecimal(text);
            } catch (NumberFormatException exception) {
                return null;
            }
        }
        return null;
    }

    private static Issue error(String code, String nodeId, String message) {
        return new Issue(code, IssueSeverity.ERROR, null, nodeId, null, message, Map.of());
    }

    /**
     * Freezes the issue list into one deterministic order and bounds it.
     *
     * <p>Issues are ordered by code, then by the (safe) component logical id, then by message, so the same
     * request always reports the same sequence regardless of component order within a slot. The list is then
     * truncated to {@link #MAX_ISSUES}, so the validator can never return an unbounded result.
     */
    private static List<Issue> bounded(List<Issue> issues) {
        List<Issue> ordered = new ArrayList<>(issues);
        ordered.sort(Comparator
                .comparing(Issue::code)
                .thenComparing(Issue::nodeId,
                        Comparator.nullsFirst(Comparator.<String>naturalOrder()))
                .thenComparing(Issue::message));
        if (ordered.size() > MAX_ISSUES) {
            return List.copyOf(ordered.subList(0, MAX_ISSUES));
        }
        return List.copyOf(ordered);
    }

    @Override
    public String toString() {
        return "T19CompositionValidator[index=" + index + ']';
    }
}