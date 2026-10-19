package com.company.campaign.builder.segment;

import com.company.campaign.contracts.Disposition;
import com.company.campaign.metadata.MetadataKind;
import com.company.campaign.metadata.MetadataTextNormalizer;
import com.company.campaign.metadata.domain.AttributeDefinition;
import com.company.campaign.metadata.domain.ComparisonOperator;
import com.company.campaign.metadata.domain.EnumValueDefinition;
import com.company.campaign.metadata.domain.MetadataSnapshot;
import com.company.campaign.metadata.domain.MetricDefinition;
import com.company.campaign.metadata.domain.SemanticDataType;
import com.company.campaign.segment.dsl.contract.MetadataBinding;
import com.company.campaign.segment.dsl.contract.SegmentDsl;
import com.company.campaign.segment.dsl.contract.SegmentDslSchemaVersions;
import com.company.campaign.segment.dsl.predicate.AttributePredicate;
import com.company.campaign.segment.dsl.predicate.ConditionValue;
import com.company.campaign.segment.dsl.predicate.GroupPredicate;
import com.company.campaign.segment.dsl.predicate.MetricPredicate;
import com.company.campaign.segment.dsl.predicate.MetricScopeFilter;
import com.company.campaign.segment.dsl.predicate.NotPredicate;
import com.company.campaign.segment.dsl.predicate.Predicate;
import com.company.campaign.segment.dsl.predicate.PredicateType;
import com.company.campaign.segment.dsl.value.BooleanValue;
import com.company.campaign.segment.dsl.value.DateValue;
import com.company.campaign.segment.dsl.value.DateTimeValue;
import com.company.campaign.segment.dsl.value.DecimalValue;
import com.company.campaign.segment.dsl.value.EnumValue;
import com.company.campaign.segment.dsl.value.IntegerValue;
import com.company.campaign.segment.dsl.value.ListValue;
import com.company.campaign.segment.dsl.value.MoneyValue;
import com.company.campaign.segment.dsl.value.RangeValue;
import com.company.campaign.segment.dsl.value.ScalarValue;
import com.company.campaign.segment.dsl.value.StringValue;
import com.company.campaign.segment.dsl.window.FixedWindow;
import com.company.campaign.segment.dsl.window.LifetimeWindow;
import com.company.campaign.segment.dsl.window.RollingWindow;
import com.company.campaign.segment.dsl.window.RelativeToAttributeWindow;
import com.company.campaign.segment.dsl.window.TimeWindow;
import com.company.campaign.segment.dsl.window.WindowUnit;
import com.company.campaign.segment.intent.IntentClause;
import com.company.campaign.segment.intent.CalendarBucketRequirement;
import com.company.campaign.segment.intent.IntentCurrency;
import com.company.campaign.segment.intent.IntentValue;
import com.company.campaign.segment.intent.IntentWindow;
import com.company.campaign.segment.intent.IntentScopeFilter;
import com.company.campaign.segment.intent.LogicalExpression;
import com.company.campaign.segment.intent.LogicalOperator;
import com.company.campaign.segment.intent.RegisteredDefaultRevisionVerifier;
import com.company.campaign.segment.intent.RegisteredDefaultSource;
import com.company.campaign.segment.intent.SegmentIntent;
import com.company.campaign.segment.intent.SegmentIntentHasher;
import com.company.campaign.segment.intent.IntentException;
import com.company.campaign.segment.resolution.ClauseResolution;
import com.company.campaign.segment.resolution.ClarificationChoiceService;
import com.company.campaign.segment.resolution.IntentResolver;
import com.company.campaign.segment.resolution.ResolutionPolicy;
import com.company.campaign.segment.resolution.ResolutionResult;
import com.company.campaign.segment.resolution.ScopeFilterResolution;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * The host-owned deterministic compiler from a resolved T10 Intent to the accepted T05 typed Segment DSL.
 *
 * <p>It is not a model and it accepts no model-generated structure. It takes the exact validated Intent, the
 * exact resolution, the exact permitted snapshot and the exact registered-default source, re-proves the Intent
 * hash, re-resolves to prove every pin and every clause disposition, then compiles the representable Phase-A
 * subset: Attribute and Metric clauses composed by {@code ALL}, {@code ANY} and unary {@code NOT}.
 *
 * <p>Stable node ids are derived only from clause ids and expression paths, never from a clock or a random
 * source. Declared boolean grouping and both expression-level and clause-level negation are preserved exactly;
 * no flattening and no De Morgan transformation is ever applied. A relation clause, or any clause whose
 * accepted definition does not belong to the Intent subject entity, fails with the stable
 * {@link SegmentDslCompilationErrorCodes#RELATION_NESTING_NOT_REPRESENTABLE} result instead of being flattened
 * into an independent predicate.
 *
 * <p>The resolution policy is an injected host-owned component: it is required to re-prove the exact policy
 * pins, and it can never be a model output.
 */
public final class ResolvedIntentDslCompiler {

    private static final Set<ComparisonOperator> LIST_OPERATORS =
            Set.of(ComparisonOperator.IN, ComparisonOperator.NOT_IN);

    private final ResolutionPolicy policy;
    private final Instant asOf;
    private final ZoneId timezone;

    public ResolvedIntentDslCompiler(ResolutionPolicy policy) {
        this(policy, null, null);
    }

    public ResolvedIntentDslCompiler(ResolutionPolicy policy, Instant asOf, ZoneId timezone) {
        this.policy = Objects.requireNonNull(policy, "policy");
        if ((asOf == null) != (timezone == null)) {
            throw new IllegalArgumentException("Calendar compilation requires both asOf and timezone");
        }
        this.asOf = asOf;
        this.timezone = timezone;
    }

    /**
     * Re-proves and compiles one resolved Intent into a typed DSL document.
     *
     * <p>No well-formed Intent can make this method throw a raw runtime exception: every value/window lowering
     * failure becomes {@link SegmentDslCompilationErrorCodes#VALUE_NOT_REPRESENTABLE}, and any residual bounded
     * lowering rejection becomes {@link SegmentDslCompilationErrorCodes#INTERNAL_LOWERING_FAILED}. Neither
     * carries the rejected raw value.
     *
     * @throws SegmentDslCompilationFailure never thrown to callers; failures are returned as a result
     */
    public DslCompilationResult compile(
            SegmentIntent intent,
            ResolutionResult resolution,
            MetadataSnapshot snapshot,
            RegisteredDefaultSource defaults) {
        Objects.requireNonNull(intent, "intent");
        Objects.requireNonNull(resolution, "resolution");
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(defaults, "defaults");
        try {
            return lowerIntent(intent, resolution, snapshot, defaults);
        } catch (CompileAbort failure) {
            return DslCompilationResult.failed(failure.disposition, failure.code, failure.clauseId);
        } catch (RuntimeException failure) {
            // The last bounded net: the accepted T05 boundary rejected a derived node identity, the published
            // document or its binding. Nothing from the failing input is echoed.
            return DslCompilationResult.failed(
                    Disposition.INVALID, SegmentDslCompilationErrorCodes.INTERNAL_LOWERING_FAILED, null);
        }
    }

    private DslCompilationResult lowerIntent(
            SegmentIntent intent,
            ResolutionResult resolution,
            MetadataSnapshot snapshot,
            RegisteredDefaultSource defaults) {
        if (!SegmentIntentHasher.hash(intent).equals(resolution.baseIntentHash())) {
            return DslCompilationResult.failed(
                    Disposition.INVALID, SegmentDslCompilationErrorCodes.INTENT_HASH_MISMATCH, null);
        }
        if (!resolution.disposition().isResolved()) {
            return DslCompilationResult.failed(
                    resolution.disposition(), SegmentDslCompilationErrorCodes.RESOLUTION_NOT_RESOLVED, null);
        }

        IntentResolver resolver = new IntentResolver(snapshot, policy, defaults);
        ResolutionResult reResolved = resolution.selectionReceipts().isEmpty()
                ? resolver.resolve(intent)
                : new ClarificationChoiceService(resolver).replay(intent, resolution.selectionReceipts());
        if (!reResolved.equals(resolution)) {
            return DslCompilationResult.failed(
                    Disposition.INVALID, SegmentDslCompilationErrorCodes.RESOLUTION_STALE, null);
        }

        Map<String, IntentClause> clausesById = new HashMap<>();
        for (IntentClause clause : intent.clauses()) {
            clausesById.put(clause.clauseId(), clause);
        }
        Map<String, ClauseResolution> resolutionsById = new HashMap<>();
        for (ClauseResolution clause : resolution.clauses()) {
            resolutionsById.put(clause.clauseId(), clause);
        }

        Context context = new Context(intent, snapshot, defaults, clausesById, resolutionsById);
        Predicate predicate = compileExpression(intent.logicalExpression(), "r", context);
        if (context.compiled.size() != intent.clauses().size()) {
            throw abort(Disposition.INVALID, SegmentDslCompilationErrorCodes.CLAUSE_NOT_COMPILED, null);
        }
        MetadataBinding binding = MetadataBinding.fromSnapshot(snapshot);
        SegmentDsl document = SegmentDsl.publish(
                SegmentDslSchemaVersions.SUPPORTED, intent.subject(), binding, predicate);
        return DslCompilationResult.resolved(document);
    }

    private Predicate compileExpression(LogicalExpression expression, String path, Context context) {
        if (expression instanceof LogicalExpression.Clause leaf) {
            IntentClause clause = context.clausesById.get(leaf.clauseId());
            if (clause == null) {
                throw abort(Disposition.INVALID, SegmentDslCompilationErrorCodes.CLAUSE_NOT_COMPILED, leaf.clauseId());
            }
            if (!context.compiled.add(clause.clauseId())) {
                throw abort(
                        Disposition.INVALID, SegmentDslCompilationErrorCodes.CLAUSE_COMPILED_TWICE, clause.clauseId());
            }
            Predicate node = compileLeaf(clause, context);
            if (clause.negated()) {
                node = new NotPredicate("n-neg-" + clause.clauseId(), node);
            }
            return node;
        }
        if (expression instanceof LogicalExpression.Group group) {
            List<Predicate> children = new ArrayList<>(group.children().size());
            for (int index = 0; index < group.children().size(); index++) {
                children.add(compileExpression(group.children().get(index), path + "." + index, context));
            }
            PredicateType type = group.operator() == LogicalOperator.ALL ? PredicateType.ALL : PredicateType.ANY;
            return new GroupPredicate("n-g-" + path, type, children);
        }
        if (expression instanceof LogicalExpression.Negation negation) {
            return new NotPredicate("n-not-" + path, compileExpression(negation.child(), path + ".0", context));
        }
        throw abort(Disposition.INVALID, SegmentDslCompilationErrorCodes.CLAUSE_NOT_COMPILED, null);
    }

    private Predicate compileLeaf(IntentClause clause, Context context) {
        ClauseResolution resolution = context.resolutionsById.get(clause.clauseId());
        if (resolution == null || !resolution.disposition().isResolved() || resolution.accepted() == null) {
            throw abort(
                    Disposition.INVALID, SegmentDslCompilationErrorCodes.CLAUSE_NOT_RESOLVED, clause.clauseId());
        }
        MetadataKind kind = resolution.acceptedKind();
        String definitionId = resolution.accepted();
        if (clause.definitionKind() != null && clause.definitionKind() != kind) {
            throw abort(
                    Disposition.INVALID, SegmentDslCompilationErrorCodes.DEFINITION_KIND_INCOMPATIBLE, clause.clauseId());
        }
        if (kind == MetadataKind.RELATION) {
            throw abort(
                    Disposition.UNSUPPORTED,
                    SegmentDslCompilationErrorCodes.RELATION_NESTING_NOT_REPRESENTABLE,
                    clause.clauseId());
        }
        if (kind == MetadataKind.ATTRIBUTE) {
            AttributeDefinition definition = findAttribute(context.snapshot, definitionId, clause);
            if (definition.subject() != context.intent.subject()) {
                throw abort(
                        Disposition.UNSUPPORTED,
                        SegmentDslCompilationErrorCodes.RELATION_NESTING_NOT_REPRESENTABLE,
                        clause.clauseId());
            }
            ComparisonOperator operator = requireAllowedOperator(clause, definition.allowedOperators());
            if (definition.dataType() == SemanticDataType.ORDERED_ENUM
                    && isOrderedEnumRangeOperator(operator)) {
                return lower(clause, () -> compileOrderedEnumAttribute(clause, definition, operator));
            }
            ConditionValue condition = lower(clause, () -> buildCondition(
                    clause, definition.dataType(), definition.enumValues(), defaults(context), operator));
            return new AttributePredicate("n-c-" + clause.clauseId(), definition.id(), operator, condition);
        }
        if (kind == MetadataKind.METRIC) {
            MetricDefinition definition = findMetric(context.snapshot, definitionId, clause);
            if (definition.subject() != context.intent.subject()) {
                throw abort(
                        Disposition.UNSUPPORTED,
                        SegmentDslCompilationErrorCodes.RELATION_NESTING_NOT_REPRESENTABLE,
                        clause.clauseId());
            }
            ComparisonOperator operator = requireAllowedOperator(clause, definition.allowedOperators());
            requireAggregation(clause, definition);
            ConditionValue condition = lower(clause, () -> buildCondition(
                    clause, definition.resultType(), List.of(), defaults(context), operator));
            List<MetricScopeFilter> scopeFilters = compileScopeFilters(clause, resolution, context);
            if (clause.window() instanceof IntentWindow.CalendarMonths calendarMonths) {
                return lower(clause, () -> compileCalendarMonths(
                        clause, definition, calendarMonths, scopeFilters, operator, condition));
            }
            TimeWindow window = lower(clause, () -> requireWindow(clause, definition, resolution, context));
            return new MetricPredicate(
                    "n-c-" + clause.clauseId(), definition.id(), window, scopeFilters, operator, condition);
        }
        throw abort(Disposition.INVALID, SegmentDslCompilationErrorCodes.DEFINITION_KIND_INCOMPATIBLE, clause.clauseId());
    }

    private List<MetricScopeFilter> compileScopeFilters(
            IntentClause clause, ClauseResolution resolution, Context context) {
        return compileScopeFilters(clause.scopeFilters(), clause, resolution, context);
    }

    private List<MetricScopeFilter> compileScopeFilters(
            List<IntentScopeFilter> source,
            IntentClause clause,
            ClauseResolution resolution,
            Context context) {
        if (clause.scopeFilters().size() + selectorFilters(clause.window()).size()
                != resolution.scopeFilters().size()) {
            throw abort(Disposition.INVALID, SegmentDslCompilationErrorCodes.RESOLUTION_STALE, clause.clauseId());
        }
        Map<String, ScopeFilterResolution> byId = new HashMap<>();
        for (ScopeFilterResolution filter : resolution.scopeFilters()) {
            byId.put(filter.filterId(), filter);
        }
        List<MetricScopeFilter> compiled = new ArrayList<>(source.size());
        for (IntentScopeFilter filter : source) {
            ScopeFilterResolution resolved = byId.get(filter.filterId());
            if (resolved == null || !resolved.disposition().isResolved()
                    || resolved.acceptedAttributeId() == null) {
                throw abort(Disposition.INVALID, SegmentDslCompilationErrorCodes.CLAUSE_NOT_RESOLVED,
                        clause.clauseId());
            }
            AttributeDefinition attribute = findAttribute(
                    context.snapshot, resolved.acceptedAttributeId(), clause);
            if (!attribute.allowedOperators().contains(filter.operator())) {
                throw abort(Disposition.INVALID, SegmentDslCompilationErrorCodes.OPERATOR_NOT_ALLOWED,
                        clause.clauseId());
            }
            ConditionValue condition = lower(
                    clause,
                    () -> buildScopeFilterCondition(filter, attribute, clause, context.defaults));
            compiled.add(new MetricScopeFilter(
                    filter.filterId(), attribute.id(), filter.operator(), condition));
        }
        return List.copyOf(compiled);
    }

    private static List<IntentScopeFilter> selectorFilters(IntentWindow window) {
        if (window instanceof IntentWindow.SelectedEvent selected) {
            return selected.selectorScopeFilters();
        }
        if (window instanceof IntentWindow.RelativeToEvent relative) {
            return relative.selectorScopeFilters();
        }
        return List.of();
    }

    private ConditionValue buildScopeFilterCondition(
            IntentScopeFilter filter,
            AttributeDefinition definition,
            IntentClause clause,
            RegisteredDefaultSource defaults) {
        ComparisonOperator operator = filter.operator();
        IntentValue value = filter.value();
        if (operator.isNullOperator()) {
            return ConditionValue.none();
        }
        String currency = definition.dataType() == SemanticDataType.MONEY
                ? requireCurrency(clause, defaults)
                : null;
        if (value instanceof IntentValue.ListValue list) {
            List<ScalarValue> items = new ArrayList<>(list.items().size());
            for (IntentValue.Scalar item : list.items()) {
                items.add(buildScalar(item, definition.dataType(), definition.enumValues(), currency, clause));
            }
            return ConditionValue.of(new ListValue(items));
        }
        if (value instanceof IntentValue.RangeValue range) {
            return ConditionValue.of(new RangeValue(
                    buildScalar(range.lower(), definition.dataType(), definition.enumValues(), currency, clause),
                    buildScalar(range.upper(), definition.dataType(), definition.enumValues(), currency, clause),
                    range.includeLower(), range.includeUpper()));
        }
        if (!(value instanceof IntentValue.Scalar scalar)) {
            throw valueType(clause);
        }
        return ConditionValue.of(buildScalar(
                scalar, definition.dataType(), definition.enumValues(), currency, clause));
    }

    private AttributeDefinition findAttribute(MetadataSnapshot snapshot, String id, IntentClause clause) {
        for (AttributeDefinition definition : snapshot.attributes()) {
            if (definition.id().equals(id)) {
                return definition;
            }
        }
        throw abort(Disposition.INVALID, SegmentDslCompilationErrorCodes.DEFINITION_UNKNOWN, clause.clauseId());
    }

    private MetricDefinition findMetric(MetadataSnapshot snapshot, String id, IntentClause clause) {
        for (MetricDefinition definition : snapshot.metrics()) {
            if (definition.id().equals(id)) {
                return definition;
            }
        }
        throw abort(Disposition.INVALID, SegmentDslCompilationErrorCodes.DEFINITION_UNKNOWN, clause.clauseId());
    }

    private ComparisonOperator requireAllowedOperator(
            IntentClause clause, List<ComparisonOperator> allowedOperators) {
        ComparisonOperator operator = clause.operator();
        if (operator == null) {
            throw abort(Disposition.INVALID, SegmentDslCompilationErrorCodes.OPERATOR_MISSING, clause.clauseId());
        }
        if (!allowedOperators.contains(operator)) {
            throw abort(Disposition.INVALID, SegmentDslCompilationErrorCodes.OPERATOR_NOT_ALLOWED, clause.clauseId());
        }
        return operator;
    }

    private TimeWindow requireWindow(
            IntentClause clause,
            MetricDefinition definition,
            ClauseResolution resolution,
            Context context) {
        IntentWindow intentWindow = clause.window();
        if (intentWindow == null) {
            throw abort(Disposition.INVALID, SegmentDslCompilationErrorCodes.WINDOW_MISSING, clause.clauseId());
        }
        if (!definition.supportedWindows().contains(intentWindow.kind())) {
            throw abort(Disposition.INVALID, SegmentDslCompilationErrorCodes.WINDOW_UNSUPPORTED, clause.clauseId());
        }
        return switch (intentWindow) {
            case IntentWindow.Rolling rolling -> new RollingWindow(rolling.amount(), WindowUnit.valueOf(rolling.unit().name()));
            case IntentWindow.Lifetime ignored -> new LifetimeWindow();
            case IntentWindow.Fixed fixed -> new FixedWindow(fixed.start(), fixed.end());
            case IntentWindow.CalendarMonths ignored -> throw valueType(clause);
            case IntentWindow.RelativeToAttribute relative -> new RelativeToAttributeWindow(
                    relative.anchorDefinitionId(), relative.startOffset(), relative.endOffset(),
                    WindowUnit.valueOf(relative.unit().name()));
            case IntentWindow.SelectedEvent selected -> new com.company.campaign.segment.dsl.window.SelectedEventWindow(
                    selected.selectorId(), compileScopeFilters(
                    selected.selectorScopeFilters(), clause, resolution, context));
            case IntentWindow.RelativeToEvent relative -> new com.company.campaign.segment.dsl.window.RelativeToEventWindow(
                    relative.selectorId(), compileScopeFilters(
                    relative.selectorScopeFilters(), clause, resolution, context),
                    relative.startOffset(), relative.endOffset(), WindowUnit.valueOf(relative.unit().name()),
                    relative.excludeAnchorEvent());
        };
    }

    private Predicate compileCalendarMonths(
            IntentClause clause,
            MetricDefinition definition,
            IntentWindow.CalendarMonths calendarMonths,
            List<MetricScopeFilter> scopeFilters,
            ComparisonOperator operator,
            ConditionValue condition) {
        if (asOf == null || timezone == null || !definition.supportedWindows().contains(
                com.company.campaign.metadata.domain.TimeWindowKind.FIXED)) {
            throw valueType(clause);
        }
        ZonedDateTime currentMonthStart = asOf.atZone(timezone)
                .toLocalDate().withDayOfMonth(1).atStartOfDay(timezone);
        ZonedDateTime firstMonthStart = currentMonthStart.minusMonths(
                calendarMonths.includeCurrent() ? calendarMonths.count() - 1L : calendarMonths.count());
        Instant overallEnd = calendarMonths.includeCurrent() ? asOf : currentMonthStart.toInstant();
        if (calendarMonths.bucketRequirement() == CalendarBucketRequirement.COMBINED) {
            return new MetricPredicate(
                    "n-c-" + clause.clauseId(), definition.id(),
                    fixed(firstMonthStart.toInstant(), overallEnd), scopeFilters, operator, condition);
        }
        List<Predicate> buckets = new ArrayList<>(calendarMonths.count());
        for (int index = 0; index < calendarMonths.count(); index++) {
            ZonedDateTime start = firstMonthStart.plusMonths(index);
            Instant end = calendarMonths.includeCurrent() && index == calendarMonths.count() - 1
                    ? asOf
                    : start.plusMonths(1).toInstant();
            buckets.add(new MetricPredicate(
                    "n-c-" + clause.clauseId() + "-month-" + (index + 1), definition.id(),
                    fixed(start.toInstant(), end), scopeFilters, operator, condition));
        }
        return buckets.size() == 1
                ? buckets.getFirst()
                : new GroupPredicate("n-cal-" + clause.clauseId(), PredicateType.ALL, buckets);
    }

    private static FixedWindow fixed(Instant start, Instant end) {
        return new FixedWindow(start.toString(), end.toString());
    }

    private void requireAggregation(IntentClause clause, MetricDefinition definition) {
        if (clause.aggregation() != null && clause.aggregation() != definition.aggregation()) {
            throw abort(
                    Disposition.INVALID, SegmentDslCompilationErrorCodes.VALUE_SHAPE_INCOMPATIBLE, clause.clauseId());
        }
    }

    private ConditionValue buildCondition(
            IntentClause clause,
            SemanticDataType dataType,
            List<EnumValueDefinition> enumValues,
            RegisteredDefaultSource defaults,
            ComparisonOperator operator) {
        if (operator.isNullOperator()) {
            if (clause.value() != null) {
                throw abort(
                        Disposition.INVALID, SegmentDslCompilationErrorCodes.VALUE_SHAPE_INCOMPATIBLE, clause.clauseId());
            }
            return ConditionValue.none();
        }
        IntentValue value = clause.value();
        if (value == null) {
            throw abort(Disposition.INVALID, SegmentDslCompilationErrorCodes.VALUE_SHAPE_INCOMPATIBLE, clause.clauseId());
        }
        if (value instanceof IntentValue.ListValue list) {
            if (!LIST_OPERATORS.contains(operator)) {
                throw abort(
                        Disposition.INVALID, SegmentDslCompilationErrorCodes.VALUE_SHAPE_INCOMPATIBLE, clause.clauseId());
            }
            String currency = dataType == SemanticDataType.MONEY ? requireCurrency(clause, defaults) : null;
            List<ScalarValue> items = new ArrayList<>(list.items().size());
            for (IntentValue.Scalar item : list.items()) {
                items.add(buildScalar(item, dataType, enumValues, currency, clause));
            }
            return ConditionValue.of(new ListValue(items));
        }
        if (value instanceof IntentValue.RangeValue range) {
            if (operator != ComparisonOperator.BETWEEN) {
                throw abort(
                        Disposition.INVALID, SegmentDslCompilationErrorCodes.VALUE_SHAPE_INCOMPATIBLE, clause.clauseId());
            }
            if ((dataType == SemanticDataType.DATE || dataType == SemanticDataType.TIMESTAMP)
                    && (!range.includeLower() || range.includeUpper())) {
                throw abort(
                        Disposition.INVALID,
                        SegmentDslCompilationErrorCodes.TEMPORAL_RANGE_NOT_HALF_OPEN,
                        clause.clauseId());
            }
            String currency = dataType == SemanticDataType.MONEY ? requireCurrency(clause, defaults) : null;
            ScalarValue lower = buildScalar(range.lower(), dataType, enumValues, currency, clause);
            ScalarValue upper = buildScalar(range.upper(), dataType, enumValues, currency, clause);
            return ConditionValue.of(new RangeValue(lower, upper, range.includeLower(), range.includeUpper()));
        }
        if (!(value instanceof IntentValue.Scalar scalar)) {
            throw abort(Disposition.INVALID, SegmentDslCompilationErrorCodes.VALUE_SHAPE_INCOMPATIBLE, clause.clauseId());
        }
        String currency = dataType == SemanticDataType.MONEY ? requireCurrency(clause, defaults) : null;
        return ConditionValue.of(buildScalar(scalar, dataType, enumValues, currency, clause));
    }

    private AttributePredicate compileOrderedEnumAttribute(
            IntentClause clause, AttributeDefinition definition, ComparisonOperator operator) {
        List<EnumValueDefinition> selected;
        if (operator == ComparisonOperator.BETWEEN) {
            if (!(clause.value() instanceof IntentValue.RangeValue range)
                    || !(range.lower() instanceof IntentValue.EnumValue lower)
                    || !(range.upper() instanceof IntentValue.EnumValue upper)) {
                throw valueType(clause);
            }
            int lowerRank = rankOf(resolveEnum(lower, definition.enumValues(), clause), definition, clause);
            int upperRank = rankOf(resolveEnum(upper, definition.enumValues(), clause), definition, clause);
            if (lowerRank > upperRank || (lowerRank == upperRank
                    && !(range.includeLower() && range.includeUpper()))) {
                throw valueType(clause);
            }
            selected = definition.enumValues().stream()
                    .filter(value -> (range.includeLower() ? value.rank() >= lowerRank : value.rank() > lowerRank)
                            && (range.includeUpper() ? value.rank() <= upperRank : value.rank() < upperRank))
                    .toList();
        } else {
            if (!(clause.value() instanceof IntentValue.EnumValue boundary)) {
                throw valueType(clause);
            }
            int rank = rankOf(resolveEnum(boundary, definition.enumValues(), clause), definition, clause);
            selected = definition.enumValues().stream()
                    .filter(value -> switch (operator) {
                        case GREATER_THAN -> value.rank() > rank;
                        case GREATER_OR_EQUAL -> value.rank() >= rank;
                        case LESS_THAN -> value.rank() < rank;
                        case LESS_OR_EQUAL -> value.rank() <= rank;
                        default -> false;
                    })
                    .toList();
        }
        boolean empty = selected.isEmpty();
        List<ScalarValue> values = (empty ? definition.enumValues() : selected).stream()
                .map(value -> (ScalarValue) new EnumValue(value.code()))
                .toList();
        return new AttributePredicate(
                "n-c-" + clause.clauseId(), definition.id(),
                empty ? ComparisonOperator.NOT_IN : ComparisonOperator.IN,
                ConditionValue.of(new ListValue(values)));
    }

    private static boolean isOrderedEnumRangeOperator(ComparisonOperator operator) {
        return switch (operator) {
            case GREATER_THAN, GREATER_OR_EQUAL, LESS_THAN, LESS_OR_EQUAL, BETWEEN -> true;
            default -> false;
        };
    }

    private int rankOf(EnumValue value, AttributeDefinition definition, IntentClause clause) {
        return definition.enumValues().stream()
                .filter(candidate -> candidate.code().equals(value.value()))
                .map(EnumValueDefinition::rank)
                .findFirst()
                .orElseThrow(() -> valueType(clause));
    }

    private ScalarValue buildScalar(
            IntentValue.Scalar scalar,
            SemanticDataType dataType,
            List<EnumValueDefinition> enumValues,
            String currency,
            IntentClause clause) {
        return switch (dataType) {
            case STRING -> {
                if (!(scalar instanceof IntentValue.StringValue text)) {
                    throw valueType(clause);
                }
                yield new StringValue(text.raw());
            }
            case BOOLEAN -> {
                if (!(scalar instanceof IntentValue.BooleanValue flag)) {
                    throw valueType(clause);
                }
                yield new BooleanValue(flag.raw());
            }
            case INTEGER -> {
                if (!(scalar instanceof IntentValue.IntegerValue number)) {
                    throw valueType(clause);
                }
                yield new IntegerValue(Long.parseLong(number.raw()));
            }
            case DECIMAL -> {
                if (scalar instanceof IntentValue.DecimalValue number) {
                    yield new DecimalValue(number.raw());
                }
                if (scalar instanceof IntentValue.IntegerValue number) {
                    yield new DecimalValue(number.raw());
                }
                throw valueType(clause);
            }
            case DATE -> {
                if (!(scalar instanceof IntentValue.DateValue date)) {
                    throw valueType(clause);
                }
                yield new DateValue(date.raw());
            }
            case TIMESTAMP -> {
                if (!(scalar instanceof IntentValue.TimestampValue instant)) {
                    throw valueType(clause);
                }
                yield new DateTimeValue(instant.raw());
            }
            case ENUM, ORDERED_ENUM -> {
                if (!(scalar instanceof IntentValue.EnumValue label)) {
                    throw valueType(clause);
                }
                yield resolveEnum(label, enumValues, clause);
            }
            case MONEY -> {
                if (!(scalar instanceof IntentValue.MoneyValue amount)) {
                    throw valueType(clause);
                }
                if (currency == null) {
                    throw abort(
                            Disposition.INVALID, SegmentDslCompilationErrorCodes.CURRENCY_MISSING, clause.clauseId());
                }
                yield new MoneyValue(amount.raw(), currency);
            }
        };
    }

    private EnumValue resolveEnum(
            IntentValue.EnumValue label, List<EnumValueDefinition> enumValues, IntentClause clause) {
        String key = MetadataTextNormalizer.normalize(label.raw());
        Set<String> matches = new LinkedHashSet<>();
        for (EnumValueDefinition value : enumValues) {
            if (value.code().equals(label.raw())) {
                matches.add(value.code());
                continue;
            }
            for (String alias : value.aliases()) {
                if (MetadataTextNormalizer.normalize(alias).equals(key)) {
                    matches.add(value.code());
                    break;
                }
            }
        }
        if (matches.isEmpty()) {
            throw abort(Disposition.INVALID, SegmentDslCompilationErrorCodes.ENUM_UNRESOLVED, clause.clauseId());
        }
        if (matches.size() > 1) {
            throw abort(Disposition.INVALID, SegmentDslCompilationErrorCodes.ENUM_AMBIGUOUS, clause.clauseId());
        }
        return new EnumValue(matches.iterator().next());
    }

    private String requireCurrency(IntentClause clause, RegisteredDefaultSource defaults) {
        IntentCurrency currency = clause.currency();
        if (currency == null) {
            if (policy.reportingCurrencyDefaultId() != null) {
                try {
                    return RegisteredDefaultRevisionVerifier.resolveCurrency(
                            defaults, policy.reportingCurrencyDefaultId());
                } catch (IntentException failure) {
                    throw abort(
                            Disposition.INVALID,
                            SegmentDslCompilationErrorCodes.CURRENCY_DEFAULT_UNRESOLVED,
                            clause.clauseId());
                }
            }
            throw abort(Disposition.INVALID, SegmentDslCompilationErrorCodes.CURRENCY_MISSING, clause.clauseId());
        }
        if (currency instanceof IntentCurrency.Explicit explicit) {
            return explicit.code();
        }
        if (currency instanceof IntentCurrency.RegisteredDefault registered) {
            try {
                RegisteredDefaultRevisionVerifier.verify(
                        defaults, registered.defaultId(), registered.version(), registered.contentHash());
                return RegisteredDefaultRevisionVerifier.resolveCurrency(defaults, registered.defaultId());
            } catch (IntentException failure) {
                throw abort(
                        Disposition.INVALID,
                        SegmentDslCompilationErrorCodes.CURRENCY_DEFAULT_UNRESOLVED,
                        clause.clauseId());
            }
        }
        throw abort(Disposition.INVALID, SegmentDslCompilationErrorCodes.CURRENCY_MISSING, clause.clauseId());
    }

    private static RegisteredDefaultSource defaults(Context context) {
        return context.defaults;
    }

    private static CompileAbort valueType(IntentClause clause) {
        return abort(Disposition.INVALID, SegmentDslCompilationErrorCodes.VALUE_TYPE_INCOMPATIBLE, clause.clauseId());
    }

    /**
     * Runs one value/window lowering step at the narrow boundary where a raw Intent slot becomes a T05 typed
     * value or window.
     *
     * <p>A deliberate {@link CompileAbort} (a shape, kind or capability failure) is preserved unchanged. Any
     * other runtime rejection - an integer outside the T05 64-bit range, a decimal/money lexeme beyond the T05
     * precision/scale/length bound, a range or window the typed constructor refuses - is translated into one
     * stable {@link SegmentDslCompilationErrorCodes#VALUE_NOT_REPRESENTABLE} {@code INVALID} failure. The raw
     * value is never echoed.
     */
    private static <T> T lower(IntentClause clause, java.util.function.Supplier<T> lowering) {
        try {
            return lowering.get();
        } catch (CompileAbort failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw abort(
                    Disposition.INVALID, SegmentDslCompilationErrorCodes.VALUE_NOT_REPRESENTABLE, clause.clauseId());
        }
    }

    private static CompileAbort abort(Disposition disposition, String code, String clauseId) {
        return new CompileAbort(disposition, code, clauseId);
    }

    private record Context(
            SegmentIntent intent,
            MetadataSnapshot snapshot,
            RegisteredDefaultSource defaults,
            Map<String, IntentClause> clausesById,
            Map<String, ClauseResolution> resolutionsById,
            Set<String> compiled
    ) {

        private Context(
                SegmentIntent intent,
                MetadataSnapshot snapshot,
                RegisteredDefaultSource defaults,
                Map<String, IntentClause> clausesById,
                Map<String, ClauseResolution> resolutionsById) {
            this(intent, snapshot, defaults, clausesById, resolutionsById, new LinkedHashSet<>());
        }
    }

    /** Internal control-flow signal; it never escapes {@link #compile}. */
    private static final class CompileAbort extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private final transient Disposition disposition;
        private final String code;
        private final String clauseId;

        private CompileAbort(Disposition disposition, String code, String clauseId) {
            super(code, null, false, false);
            this.disposition = disposition;
            this.code = code;
            this.clauseId = clauseId;
        }
    }
}
