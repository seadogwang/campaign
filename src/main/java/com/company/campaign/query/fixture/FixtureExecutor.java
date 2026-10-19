package com.company.campaign.query.fixture;

import com.company.campaign.evaluation.segment.contract.MemberIdSet;
import com.company.campaign.metadata.MetadataKind;
import com.company.campaign.metadata.domain.AggregateFunction;
import com.company.campaign.metadata.domain.ComparisonOperator;
import com.company.campaign.metadata.domain.CurrencySemantics;
import com.company.campaign.metadata.domain.EmptySetBehavior;
import com.company.campaign.metadata.domain.EventTimeSemantics;
import com.company.campaign.metadata.domain.MetadataFilterEntry;
import com.company.campaign.metadata.domain.MetadataScalarValue;
import com.company.campaign.metadata.domain.RefundTreatment;
import com.company.campaign.metadata.domain.SemanticDataType;
import com.company.campaign.metadata.domain.StringCaseSensitivity;
import com.company.campaign.metadata.domain.StringSemantics;
import com.company.campaign.metadata.domain.SubjectKind;
import com.company.campaign.query.ast.QueryAst;
import com.company.campaign.query.plan.AudienceVersionDependency;
import com.company.campaign.query.plan.CurrencyBinding;
import com.company.campaign.query.plan.ResolvedDependency;
import com.company.campaign.query.plan.ResolvedWindow;
import com.company.campaign.query.plan.ResolvedWindowBinding;
import com.company.campaign.query.plan.SemanticQueryPlan;
import com.company.campaign.query.plan.TrustedPlanContext;
import com.company.campaign.segment.canonical.CanonicalAudienceVersionRef;
import com.company.campaign.segment.canonical.CanonicalDefinition;
import com.company.campaign.segment.canonical.CanonicalMetricDefinition;
import com.company.campaign.segment.canonical.CanonicalRelationReference;
import com.company.campaign.segment.canonical.CanonicalValue;
import com.company.campaign.segment.reference.AudienceMembershipPort;
import com.company.campaign.segment.reference.AudienceMembershipSet;
import com.company.campaign.segment.reference.AudienceVersionRef;

import java.math.BigDecimal;
import java.math.MathContext;
import java.text.Normalizer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * The one deterministic fixture executor.
 *
 * <p>It accepts only a genuine accepted {@link SemanticQueryPlan}, one exact immutable {@link FixtureDataset},
 * one server-owned {@link FixtureMapping}, one server-owned {@link FixtureScopeBinding} and an optional
 * explicit {@link FxRateSnapshot}, and it rejects every plan/dataset/context/version/hash mismatch before a
 * single member is evaluated.
 *
 * <p>It then evaluates the complete closed Query AST exactly as the plan pinned it and without rewriting it:
 * nested {@code ALL}/{@code ANY}/{@code NOT} grouping, every Attribute operator and type (including NULL, list
 * and range operands and definition-owned string semantics), three-valued logic in which NULL is never a
 * default and {@code NOT_EQUAL}/{@code NOT_IN} stay NULL-excluding unless an explicit branch includes NULL,
 * one same-row {@code EXISTS} versus independently satisfiable {@code EXISTS} nodes, the exact resolved window
 * boundaries and event-time fields, Metric relation/filter/refund/aggregate/distinct/empty-set semantics with
 * exact decimal arithmetic, and the mandatory DataScope predicate applied from the server-owned binding before
 * any user predicate.
 *
 * <p>Nothing here reads a clock, a JVM default timezone or locale, a random value or ambient tenant state, so
 * identical inputs always produce a byte-identical {@link FixtureExecutionResult}.
 */
public final class FixtureExecutor {

    private static final MathContext AVERAGE_CONTEXT = MathContext.DECIMAL128;

    /**
     * Executes one genuine plan against one exact dataset; {@code fxRateSnapshot} is required only for a
     * conversion.
     *
     * <p>This is the accepted reference-free entry point. A plan that freezes an Existing Audience Reference
     * cannot be executed here: there is no local membership dataset to answer for the exact pin, so the call
     * fails closed rather than evaluating the reference as "no members".
     */
    public FixtureExecutionResult execute(
            SemanticQueryPlan plan,
            FixtureDataset dataset,
            FixtureMapping mapping,
            FixtureScopeBinding scopeBinding,
            FxRateSnapshot fxRateSnapshot) {
        return execute(plan, dataset, mapping, scopeBinding, fxRateSnapshot, null);
    }

    /**
     * Executes one genuine plan, resolving any Existing Audience Reference through an exact-pin membership
     * port.
     *
     * <p>The reference is evaluated by looking up the exact immutable pin — audience, version identity and
     * content hash together — so a membership set registered for different bytes can never answer, and an
     * unregistered pin fails closed instead of degrading to an empty set. The lookup result itself never leaves
     * this executor: only the matched count, the condition statistics and the canonical result hash go
     * outward, and no member identifier is ever logged, prompted or returned.
     *
     * <p>Ordinary {@code ALL}/{@code ANY}/{@code NOT} composition around a reference keeps its normal
     * three-valued semantics, because the reference is evaluated as one more predicate at its own structural
     * path rather than being folded into the surrounding tree.
     */
    public FixtureExecutionResult execute(
            SemanticQueryPlan plan,
            FixtureDataset dataset,
            FixtureMapping mapping,
            FixtureScopeBinding scopeBinding,
            FxRateSnapshot fxRateSnapshot,
            AudienceMembershipPort audienceMembership) {
        requirePresent(plan, "plan");
        requirePresent(dataset, "dataset");
        requirePresent(mapping, "mapping");
        requirePresent(scopeBinding, "scopeBinding");

        TrustedPlanContext context = plan.trustedContext();
        if (plan.subject() != SubjectKind.MEMBER) {
            throw new FixtureException(FixtureErrorCodes.FIXTURE_UNSUPPORTED_SEMANTIC, "planSubject",
                    "The fixture executor evaluates member-rooted plans only.");
        }
        if (plan.hasAudienceReferences()) {
            if (audienceMembership == null) {
                throw new FixtureException(FixtureErrorCodes.FIXTURE_UNSUPPORTED_SEMANTIC, "audienceMembership",
                        "Executing an Existing Audience Reference requires an exact-pin membership port.");
            }
            if (!plan.dataScopePredicate().isEffectiveIntersection()) {
                throw planMismatch("dataScopeId");
            }
        } else if (!plan.dataScopePredicate().dataScopeId().equals(context.dataScopeId())) {
            throw planMismatch("dataScopeId");
        }
        if (!dataset.asOf().equals(context.asOf())
                || !dataset.dataWatermark().equals(context.dataWatermark())
                || !dataset.timezone().getId().equals(context.timezone())) {
            throw planMismatch("fixtureTimeContext");
        }

        MemberIdSet scopeMembers = scopeBinding.scope(plan.dataScopePredicate().dataScopeId());
        if (scopeMembers == null) {
            throw new FixtureException(FixtureErrorCodes.FIXTURE_SCOPE_MISSING, "scopeBinding",
                    "No server-owned DataScope membership binding exists for the plan's DataScope.");
        }

        Evaluation evaluation = new Evaluation(plan, dataset, mapping, fxRateSnapshot, audienceMembership);

        List<String> matched = new ArrayList<>();
        Map<String, int[]> statistics = new TreeMap<>();
        for (DatasetRow.MemberRow member : dataset.members()) {
            if (!scopeMembers.contains(member.memberId())) {
                // DataScope membership is mandatory and is applied before any user predicate.
                continue;
            }
            Truth truth = evaluation.evaluate(
                    plan.predicateRoot(), "r", new RowContext(SubjectKind.MEMBER, member), statistics);
            if (truth == Truth.TRUE) {
                matched.add(member.memberId());
            }
        }

        FxRateSnapshot fx = evaluation.requiredFx();
        return new FixtureExecutionResult(
                new MemberIdSet(matched),
                plan.planHash(),
                dataset.fixtureId(),
                dataset.fixtureVersion(),
                dataset.payloadHash(),
                dataset.datasetHash(),
                mapping.mappingVersion(),
                mapping.mappingHash(),
                scopeBinding.bindingVersion(),
                scopeBinding.bindingHash(),
                plan.metadataSnapshotId(),
                // The scope this execution was actually placed under: the plan's requirement, which is the
                // caller's own scope for a reference-free plan and the host-computed effective intersection
                // once a reference narrows it.
                plan.dataScopePredicate().dataScopeId(),
                context.asOf(),
                context.timezone(),
                context.dataWatermark(),
                fx == null ? null : fx.snapshotId(),
                fx == null ? null : fx.contentHash(),
                statistics(statistics));
    }

    private static List<FixtureExecutionResult.PathStatistic> statistics(Map<String, int[]> counts) {
        List<FixtureExecutionResult.PathStatistic> statistics = new ArrayList<>(counts.size());
        counts.forEach((path, triple) ->
                statistics.add(new FixtureExecutionResult.PathStatistic(path, triple[0], triple[1], triple[2])));
        return statistics;
    }

    // ------------------------------------------------------------------ truth values

    private enum Truth {
        TRUE,
        FALSE,
        UNKNOWN;

        static Truth of(boolean condition) {
            return condition ? TRUE : FALSE;
        }
    }

    private static Truth and(Truth left, Truth right) {
        if (left == Truth.FALSE || right == Truth.FALSE) {
            return Truth.FALSE;
        }
        return left == Truth.TRUE && right == Truth.TRUE ? Truth.TRUE : Truth.UNKNOWN;
    }

    private static Truth or(Truth left, Truth right) {
        if (left == Truth.TRUE || right == Truth.TRUE) {
            return Truth.TRUE;
        }
        return left == Truth.FALSE && right == Truth.FALSE ? Truth.FALSE : Truth.UNKNOWN;
    }

    private static Truth negate(Truth value) {
        return switch (value) {
            case TRUE -> Truth.FALSE;
            case FALSE -> Truth.TRUE;
            case UNKNOWN -> Truth.UNKNOWN;
        };
    }

    /** One typed left-hand value compared against a canonical operand. */
    private interface TypedValue {

        boolean equalTo(CanonicalValue operand);

        default int compareTo(CanonicalValue operand) {
            throw unsupported("orderingOperator");
        }

        static TypedValue comparably(java.util.function.ToIntFunction<CanonicalValue> comparison) {
            return new TypedValue() {
                @Override
                public boolean equalTo(CanonicalValue operand) {
                    return comparison.applyAsInt(operand) == 0;
                }

                @Override
                public int compareTo(CanonicalValue operand) {
                    return comparison.applyAsInt(operand);
                }
            };
        }
    }

    private static Truth apply(ComparisonOperator operator, TypedValue left, CanonicalValue operand) {
        return switch (operator) {
            case EQUAL -> Truth.of(left.equalTo(operand));
            case NOT_EQUAL -> Truth.of(!left.equalTo(operand));
            case GREATER_THAN -> Truth.of(left.compareTo(operand) > 0);
            case GREATER_OR_EQUAL -> Truth.of(left.compareTo(operand) >= 0);
            case LESS_THAN -> Truth.of(left.compareTo(operand) < 0);
            case LESS_OR_EQUAL -> Truth.of(left.compareTo(operand) <= 0);
            case IN -> Truth.of(anyMatch((CanonicalValue.ListValue) operand, left::equalTo));
            case NOT_IN -> Truth.of(!anyMatch((CanonicalValue.ListValue) operand, left::equalTo));
            case BETWEEN -> {
                CanonicalValue.RangeValue range = (CanonicalValue.RangeValue) operand;
                int lower = left.compareTo(range.lower());
                int upper = left.compareTo(range.upper());
                boolean aboveLower = range.includeLower() ? lower >= 0 : lower > 0;
                boolean belowUpper = range.includeUpper() ? upper <= 0 : upper < 0;
                yield Truth.of(aboveLower && belowUpper);
            }
            case CONTAINS, NOT_CONTAINS, STARTS_WITH, ENDS_WITH -> throw unsupported("stringOperator");
            case IS_NULL, IS_NOT_NULL -> throw resultInvalid("nullOperator");
        };
    }

    private static boolean anyMatch(CanonicalValue.ListValue list, java.util.function.Predicate<CanonicalValue> test) {
        for (CanonicalValue item : list.items()) {
            if (test.test(item)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ evaluation state

    /** The row a sub-expression is evaluated against, together with its subject entity. */
    private record RowContext(SubjectKind entity, DatasetRow row) {
    }

    /** One aggregate measure: an exact decimal plus the currency it is expressed in, when it is monetary. */
    private record Measure(BigDecimal value, String currency) {
    }

    /** One aggregate outcome; a null measure means the value is NULL or UNDEFINED, never a default number. */
    private record Outcome(Measure measure, boolean undefined) {
    }

    /** All immutable, pre-resolved facts one execution depends on. */
    private static final class Evaluation {

        private final FixtureDataset dataset;
        private final FixtureMapping mapping;
        private final TrustedPlanContext context;
        private final Map<String, ResolvedDependency.AttributeDependency> attributesById = new LinkedHashMap<>();
        private final Map<String, ResolvedDependency.MetricDependency> metricsById = new LinkedHashMap<>();
        private final Map<String, ResolvedDependency.RelationDependency> relationsById = new LinkedHashMap<>();
        private final Map<String, ResolvedWindowBinding> windowsByPath = new LinkedHashMap<>();
        private final Map<String, CurrencyBinding> currencyByKey = new LinkedHashMap<>();
        private final Map<String, AudienceVersionDependency> audienceByPin = new LinkedHashMap<>();
        private final Map<String, BigDecimal> refundsByOrder = new LinkedHashMap<>();
        private final FxRateSnapshot fxRateSnapshot;
        private final AudienceMembershipPort audienceMembership;
        private final boolean conversionRequired;

        Evaluation(
                SemanticQueryPlan plan,
                FixtureDataset dataset,
                FixtureMapping mapping,
                FxRateSnapshot fxRateSnapshot,
                AudienceMembershipPort audienceMembership) {
            this.dataset = dataset;
            this.mapping = mapping;
            this.context = plan.trustedContext();
            this.fxRateSnapshot = fxRateSnapshot;
            this.audienceMembership = audienceMembership;
            for (AudienceVersionDependency dependency : plan.audienceDependencies()) {
                audienceByPin.put(dependency.reference().pinKey(), dependency);
            }
            for (ResolvedDependency dependency : plan.dependencies()) {
                switch (dependency) {
                    case ResolvedDependency.AttributeDependency attribute ->
                            attributesById.put(attribute.id(), attribute);
                    case ResolvedDependency.MetricDependency metric -> metricsById.put(metric.id(), metric);
                    case ResolvedDependency.RelationDependency relation -> relationsById.put(relation.id(), relation);
                }
            }
            for (ResolvedWindowBinding binding : plan.windowBindings()) {
                windowsByPath.put(binding.path(), binding);
            }
            for (CurrencyBinding binding : plan.currencyBindings()) {
                currencyByKey.put(binding.kind().name() + "|" + binding.definitionId(), binding);
            }
            boolean conversion = false;
            for (CurrencyBinding binding : plan.currencyBindings()) {
                if (binding.action() == CurrencyBinding.Action.CONVERT_TO_REPORTING_CURRENCY) {
                    conversion = true;
                    break;
                }
            }
            this.conversionRequired = conversion;
            if (conversion) {
                if (fxRateSnapshot == null) {
                    throw new FixtureException(FixtureErrorCodes.FIXTURE_FX_REQUIRED, "fxRateSnapshot",
                            "A currency conversion requires an explicit immutable FX-rate snapshot.");
                }
                if (!fxRateSnapshot.targetCurrency().equals(context.reportingCurrency())) {
                    throw new FixtureException(FixtureErrorCodes.FIXTURE_FX_INVALID, "fxTargetCurrency",
                            "The FX-rate snapshot does not target the plan's reporting currency.");
                }
                if (fxRateSnapshot.rateTimestamp().isAfter(context.asOf())) {
                    throw new FixtureException(FixtureErrorCodes.FIXTURE_FX_INVALID, "fxRateTimestamp",
                            "The FX-rate snapshot's rate timestamp is after the trusted evaluation instant.");
                }
            }
            for (RefundRow refund : dataset.refunds()) {
                refundsByOrder.merge(refund.orderId(), refund.amount(), BigDecimal::add);
            }
        }

        /** The FX snapshot this execution actually required, or {@code null} when none was needed. */
        FxRateSnapshot requiredFx() {
            return conversionRequired ? fxRateSnapshot : null;
        }

        // -------------------------------------------------------------- traversal

        Truth evaluate(QueryAst node, String path, RowContext ctx, Map<String, int[]> statistics) {
            Truth truth = switch (node) {
                case QueryAst.Group group -> evaluateGroup(group, path, ctx, statistics);
                case QueryAst.Negation negation ->
                        negate(evaluate(negation.child(), path + ".not", ctx, statistics));
                case QueryAst.Attribute attribute -> evaluateAttribute(attribute, ctx);
                case QueryAst.Metric metric -> evaluateMetric(metric, path, ctx);
                case QueryAst.MetricCalculation ignored ->
                        throw unsupported("metricExpressionFixtureEvaluation");
                case QueryAst.Exists exists -> evaluateExists(exists, path, ctx, statistics);
                case QueryAst.AudienceReference reference -> evaluateAudienceReference(reference, ctx);
            };
            statistics.computeIfAbsent(path, ignored -> new int[3])[truth.ordinal()]++;
            return truth;
        }

        /**
         * Evaluates one Existing Audience Reference by the exact immutable pin it names.
         *
         * <p>The membership lookup is keyed by the full pin, so a local dataset registered for one version's
         * bytes can never answer for another's, and a pin with no local membership refuses rather than
         * evaluating as an empty set. Because the plan's mandatory DataScope pre-filter has already restricted
         * the candidate members to the host-computed effective intersection — which planning proved is no wider
         * than this reference's own effective scope — membership in the exact version set is exactly the
         * semantics the reference declared.
         *
         * <p>The member set itself never leaves this method: only a truth value is produced, and only counts,
         * hashes and the matched count travel onward.
         */
        private Truth evaluateAudienceReference(QueryAst.AudienceReference reference, RowContext ctx) {
            AudienceVersionDependency dependency = requireAudienceDependency(reference.reference());
            if (dependency.subject() != ctx.entity() || ctx.entity() != SubjectKind.MEMBER) {
                throw unsupported("audienceReferenceSubject");
            }
            AudienceVersionRef pin = dependency.reference();
            Optional<AudienceMembershipSet> members;
            try {
                members = audienceMembership.lookup(pin, dependency.effectiveDataScope());
            } catch (RuntimeException exception) {
                throw unavailable("audienceReferenceMembership");
            }
            if (members == null || members.isEmpty()) {
                throw unavailable("audienceReferenceMembership");
            }
            AudienceMembershipSet set = members.get();
            if (!set.version().equals(pin)) {
                // A set that answers for a different pin is not the material this reference named.
                throw unavailable("audienceReferenceMembership");
            }
            String memberId = ((DatasetRow.MemberRow) ctx.row()).memberId();
            return Truth.of(set.contains(memberId));
        }

        /** The plan's frozen dependency for one exact pin; a reference without one is contradictory material. */
        private AudienceVersionDependency requireAudienceDependency(CanonicalAudienceVersionRef pin) {
            AudienceVersionDependency dependency = audienceByPin.get(
                    pin.audienceId() + "/" + pin.versionId() + "@" + pin.versionHash().value());
            if (dependency == null || !dependency.reference().versionHash().equals(pin.versionHash())) {
                throw planMismatch("audienceDependency");
            }
            return dependency;
        }

        private Truth evaluateGroup(
                QueryAst.Group group, String path, RowContext ctx, Map<String, int[]> statistics) {
            boolean all = group.kind() == QueryAst.GroupKind.ALL;
            Truth accumulator = all ? Truth.TRUE : Truth.FALSE;
            List<QueryAst> children = group.children();
            for (int index = 0; index < children.size(); index++) {
                Truth child = evaluate(children.get(index), path + "." + index, ctx, statistics);
                accumulator = all ? and(accumulator, child) : or(accumulator, child);
            }
            return accumulator;
        }

        private Truth evaluateExists(
                QueryAst.Exists exists, String path, RowContext ctx, Map<String, int[]> statistics) {
            ResolvedDependency.RelationDependency dependency = requireRelationDependency(exists.relation());
            FixtureMapping.RelationBinding binding = requireRelationBinding(dependency);
            if (dependency.reference().sourceEntity() != ctx.entity()) {
                throw unsupported("existsSourceEntity");
            }
            ResolvedWindowBinding window = requireWindow(path, ResolvedWindowBinding.WindowScope.RELATION);
            for (DatasetRow row : relatedRows(dependency, binding, ctx)) {
                if (!inRelationWindow(row, window, dependency)) {
                    continue;
                }
                Truth child = evaluate(
                        exists.child(),
                        path + ".exists",
                        new RowContext(dependency.reference().targetEntity(), row),
                        statistics);
                if (child == Truth.TRUE) {
                    // One EXISTS keeps every child predicate on this same related row.
                    return Truth.TRUE;
                }
            }
            return Truth.FALSE;
        }

        // -------------------------------------------------------------- attributes

        private Truth evaluateAttribute(QueryAst.Attribute attribute, RowContext ctx) {
            CanonicalDefinition definition = attribute.definition();
            if (definition.subject() != ctx.entity()) {
                throw unsupported("attributeSubject");
            }
            ResolvedDependency.AttributeDependency dependency = attributesById.get(definition.id());
            if (dependency == null || !dependency.definition().equals(definition)) {
                throw mappingMissing("attributeDependency");
            }
            FixtureMapping.AttributeBinding binding = mapping.attribute(
                    definition.id(), dependency.entityVersion().entity(), dependency.entityVersion().schemaVersion());
            if (binding == null || binding.subject() != definition.subject()
                    || binding.field().dataType() != definition.dataType()) {
                throw mappingMissing("attributeBinding");
            }
            DatasetField field = binding.field();
            Object raw = DatasetValues.value(ctx.row(), field);
            ComparisonOperator operator = attribute.operator();
            if (operator.isNullOperator()) {
                boolean isNull = raw == null;
                return Truth.of(operator == ComparisonOperator.IS_NULL ? isNull : !isNull);
            }
            if (raw == null) {
                // NULL is not a default value: any value comparison against it is unknown.
                return Truth.UNKNOWN;
            }
            CurrencyBinding currency = requireCurrencyBinding(
                    MetadataKind.ATTRIBUTE, definition.id(), definition.dataType(), definition.currencySemantics());
            String rawCurrency = field.currencyField() == null
                    ? null
                    : (String) DatasetValues.value(ctx.row(), field.currencyField());
            return compareScalar(
                    definition.dataType(), definition.stringSemantics(), raw, rawCurrency,
                    operator, attribute.operand(), currency);
        }

        // -------------------------------------------------------------- metrics

        private Truth evaluateMetric(QueryAst.Metric metric, String path, RowContext ctx) {
            CanonicalMetricDefinition definition = metric.definition();
            if (definition.subject() != ctx.entity()) {
                throw unsupported("metricSubject");
            }
            ResolvedDependency.MetricDependency dependency = metricsById.get(definition.id());
            if (dependency == null || !dependency.definition().equals(definition)) {
                throw mappingMissing("metricDependency");
            }
            FixtureMapping.MetricBinding binding = mapping.metric(
                    definition.id(), dependency.entityVersion().entity(), dependency.entityVersion().schemaVersion());
            if (binding == null || !binding.relationId().equals(dependency.relationId())) {
                throw mappingMissing("metricBinding");
            }
            FixtureMapping.RelationBinding relationBinding = requireRelationBinding(dependency.relation());
            SubjectKind targetEntity = dependency.relation().reference().targetEntity();
            if (binding.targetEntity() != targetEntity) {
                throw mappingMissing("metricTargetEntity");
            }
            ResolvedWindowBinding window = requireWindow(path, ResolvedWindowBinding.WindowScope.METRIC);
            requireMetricEventTime(dependency, binding, window, targetEntity);

            AggregateFunction aggregation = definition.aggregation();
            if (aggregation != AggregateFunction.COUNT && binding.inputField() == null) {
                throw mappingMissing("metricInputField");
            }
            if (dependency.refundTreatment() != RefundTreatment.IGNORE
                    && (targetEntity != SubjectKind.ORDER || binding.inputField() != DatasetField.ORDER_AMOUNT)) {
                throw unsupported("metricRefundTreatment");
            }

            List<DatasetRow> rows = new ArrayList<>();
            for (DatasetRow row : relatedRows(dependency.relation(), relationBinding, ctx)) {
                if (inMetricWindow(row, window, definition, ctx)) {
                    rows.add(row);
                }
            }
            rows = applyMetricFilter(rows, dependency, binding);

            CurrencyBinding currency = requireCurrencyBinding(
                    MetadataKind.METRIC, definition.id(), definition.resultType(), definition.currencySemantics());

            if (definition.resultType() == SemanticDataType.STRING) {
                return compareStringMetric(metric, rows, binding, aggregation);
            }
            Outcome outcome = aggregate(aggregation, rows, dependency, binding, currency, metric.operand());
            if (outcome.measure() == null) {
                // NULL and UNDEFINED are deliberately not numbers: a comparison against them is unknown.
                return Truth.UNKNOWN;
            }
            Measure measure = outcome.measure();
            return compareScalar(
                    definition.resultType(), definition.stringSemantics(), measure.value(), measure.currency(),
                    metric.operator(), metric.operand(), currency);
        }

        private void requireMetricEventTime(
                ResolvedDependency.MetricDependency dependency,
                FixtureMapping.MetricBinding binding,
                ResolvedWindowBinding window,
                SubjectKind targetEntity) {
            if (!(window instanceof ResolvedWindowBinding.MetricWindow metricWindow)) {
                throw resultInvalid("metricWindowScope");
            }
            // The plan normalizes an absent event-time capability to NOT_APPLICABLE, so compare normalized.
            EventTimeSemantics expectedSemantics = dependency.eventTimeSemantics() == null
                    ? EventTimeSemantics.NOT_APPLICABLE
                    : dependency.eventTimeSemantics();
            if (metricWindow.boundarySemantics() != dependency.definition().windowBoundarySemantics()
                    || metricWindow.eventTimeSemantics() != expectedSemantics) {
                throw mappingMissing("metricWindowSemantics");
            }
            String eventTimeField = metricWindow.eventTimeField();
            if (eventTimeField == null) {
                if (binding.eventTimeField() != null && expectedSemantics != EventTimeSemantics.NOT_APPLICABLE) {
                    throw mappingMissing("metricEventTimeField");
                }
                return;
            }
            if (binding.eventTimeField() != requireField(eventTimeField, targetEntity)) {
                throw mappingMissing("metricEventTimeField");
            }
        }

        private List<DatasetRow> applyMetricFilter(
                List<DatasetRow> rows,
                ResolvedDependency.MetricDependency dependency,
                FixtureMapping.MetricBinding binding) {
            List<MetadataFilterEntry> entries = dependency.filter().entries();
            if (entries.isEmpty()) {
                return rows;
            }
            List<DatasetRow> filtered = new ArrayList<>(rows.size());
            for (DatasetRow row : rows) {
                if (matchesFilter(row, entries, binding)) {
                    filtered.add(row);
                }
            }
            return filtered;
        }

        private boolean matchesFilter(
                DatasetRow row, List<MetadataFilterEntry> entries, FixtureMapping.MetricBinding binding) {
            for (MetadataFilterEntry entry : entries) {
                DatasetField field = binding.filterFields().get(entry.key());
                if (field == null) {
                    throw mappingMissing("metricFilterField");
                }
                Object value = DatasetValues.value(row, field);
                if (!(value instanceof String text) || !entry.values().contains(text)) {
                    return false;
                }
            }
            return true;
        }

        private Truth compareStringMetric(
                QueryAst.Metric metric,
                List<DatasetRow> rows,
                FixtureMapping.MetricBinding binding,
                AggregateFunction aggregation) {
            if (aggregation != AggregateFunction.MIN && aggregation != AggregateFunction.MAX) {
                throw unsupported("metricAggregation");
            }
            List<String> values = new ArrayList<>(rows.size());
            for (DatasetRow row : rows) {
                Object value = DatasetValues.value(row, binding.inputField());
                if (value instanceof String text) {
                    values.add(text);
                }
            }
            if (values.isEmpty()) {
                if (metric.definition().emptySetBehavior() != EmptySetBehavior.NULL) {
                    throw unsupported("metricEmptySet");
                }
                return Truth.UNKNOWN;
            }
            String best = values.get(0);
            for (String value : values) {
                int comparison = value.compareTo(best);
                if (aggregation == AggregateFunction.MIN ? comparison < 0 : comparison > 0) {
                    best = value;
                }
            }
            String extremum = best;
            TypedValue left = TypedValue.comparably(
                    operand -> normalizeString(metric.definition().stringSemantics(), extremum)
                            .compareTo(normalizeString(metric.definition().stringSemantics(), text(operand))));
            return apply(metric.operator(), left, metric.operand());
        }

        private Outcome aggregate(
                AggregateFunction aggregation,
                List<DatasetRow> rows,
                ResolvedDependency.MetricDependency dependency,
                FixtureMapping.MetricBinding binding,
                CurrencyBinding currency,
                CanonicalValue operand) {
            if (aggregation == AggregateFunction.COUNT) {
                return new Outcome(new Measure(BigDecimal.valueOf(rows.size()), null), false);
            }
            if (aggregation == AggregateFunction.COUNT_DISTINCT) {
                Set<String> distinct = new LinkedHashSet<>();
                for (DatasetRow row : rows) {
                    Object value = DatasetValues.value(row, binding.inputField());
                    if (value != null) {
                        distinct.add(distinctKey(value));
                    }
                }
                return new Outcome(new Measure(BigDecimal.valueOf(distinct.size()), null), false);
            }

            boolean monetary = dependency.definition().resultType() == SemanticDataType.MONEY;
            List<Measure> measures = new ArrayList<>(rows.size());
            for (DatasetRow row : rows) {
                Measure measure = measure(row, dependency, binding, currency, monetary);
                if (measure != null) {
                    measures.add(measure);
                }
            }
            if (measures.isEmpty()) {
                return emptyOutcome(
                        dependency.definition().emptySetBehavior(), dependency.definition().emptySetValue(),
                        currency, operand);
            }
            String currency0 = measures.get(0).currency();
            if (currency.action() == CurrencyBinding.Action.SINGLE_CURRENCY_ONLY) {
                for (Measure measure : measures) {
                    if (measure.currency() != null && !measure.currency().equals(currency0)) {
                        throw new FixtureException(FixtureErrorCodes.FIXTURE_CURRENCY_MISMATCH, "singleCurrency",
                                "A single-currency aggregate spans more than one currency.");
                    }
                }
            }
            BigDecimal result = switch (aggregation) {
                case SUM -> sum(measures);
                case AVG -> sum(measures).divide(BigDecimal.valueOf(measures.size()), AVERAGE_CONTEXT);
                case MIN -> extremum(measures, true);
                case MAX -> extremum(measures, false);
                case COUNT, COUNT_DISTINCT -> throw unsupported("metricAggregation");
            };
            return new Outcome(new Measure(result, currency0), false);
        }

        private Outcome emptyOutcome(
                EmptySetBehavior behavior,
                MetadataScalarValue value,
                CurrencyBinding currency,
                CanonicalValue operand) {
            return switch (behavior) {
                case ZERO -> new Outcome(new Measure(emptyZero(value), anchorCurrency(currency, operand)), false);
                case NULL, UNDEFINED -> new Outcome(null, behavior == EmptySetBehavior.UNDEFINED);
            };
        }

        /** The currency an empty monetary zero is expressed in: the reporting currency, or the operand's own. */
        private String anchorCurrency(CurrencyBinding binding, CanonicalValue operand) {
            if (binding.action() == CurrencyBinding.Action.CONVERT_TO_REPORTING_CURRENCY) {
                return context.reportingCurrency();
            }
            CanonicalValue anchor = operand;
            if (operand instanceof CanonicalValue.ListValue list && !list.items().isEmpty()) {
                anchor = list.items().get(0);
            } else if (operand instanceof CanonicalValue.RangeValue range) {
                anchor = range.lower();
            }
            return anchor instanceof CanonicalValue.MoneyValue money
                    ? money.currency()
                    : context.reportingCurrency();
        }

        private static BigDecimal emptyZero(MetadataScalarValue value) {
            if (value == null) {
                throw resultInvalid("emptySetValue");
            }
            try {
                return new BigDecimal(value.canonicalValue());
            } catch (NumberFormatException exception) {
                throw resultInvalid("emptySetValue");
            }
        }

        private static BigDecimal sum(List<Measure> measures) {
            BigDecimal total = BigDecimal.ZERO;
            for (Measure measure : measures) {
                total = total.add(measure.value());
            }
            return total;
        }

        private static BigDecimal extremum(List<Measure> measures, boolean minimum) {
            BigDecimal best = measures.get(0).value();
            for (Measure measure : measures) {
                int comparison = measure.value().compareTo(best);
                if (minimum ? comparison < 0 : comparison > 0) {
                    best = measure.value();
                }
            }
            return best;
        }

        /** One row's measure, adjusted for refunds and converted when the plan requires a conversion. */
        private Measure measure(
                DatasetRow row,
                ResolvedDependency.MetricDependency dependency,
                FixtureMapping.MetricBinding binding,
                CurrencyBinding currency,
                boolean monetary) {
            BigDecimal amount = DatasetValues.decimal(row, binding.inputField());
            if (amount == null) {
                return null;
            }
            if (dependency.refundTreatment() == RefundTreatment.SUBTRACT
                    && binding.inputField() == DatasetField.ORDER_AMOUNT) {
                BigDecimal refund = refundsByOrder.get(((DatasetRow.OrderRow) row).orderId());
                if (refund != null) {
                    amount = amount.subtract(refund);
                }
            }
            if (!monetary) {
                return new Measure(amount, null);
            }
            String sourceCurrency = rowCurrency(row, binding.inputField());
            if (currency.action() == CurrencyBinding.Action.CONVERT_TO_REPORTING_CURRENCY) {
                return new Measure(convert(amount, sourceCurrency, currency), context.reportingCurrency());
            }
            return new Measure(amount, sourceCurrency);
        }

        private String rowCurrency(DatasetRow row, DatasetField inputField) {
            DatasetField currencyField = inputField == null ? null : inputField.currencyField();
            if (currencyField == null) {
                return null;
            }
            Object value = DatasetValues.value(row, currencyField);
            if (!(value instanceof String text)) {
                throw new FixtureException(FixtureErrorCodes.FIXTURE_CURRENCY_MISMATCH, "currencyMissing",
                        "A monetary dataset value has no currency.");
            }
            return text;
        }

        // -------------------------------------------------------------- relations

        private List<DatasetRow> relatedRows(
                ResolvedDependency.RelationDependency dependency,
                FixtureMapping.RelationBinding binding,
                RowContext ctx) {
            CanonicalRelationReference reference = dependency.reference();
            List<DatasetRow> candidates = dataset.rowsOf(reference.targetEntity());
            if (reference.sourceJoinField() == null) {
                if (reference.targetJoinField() != null) {
                    throw unsupported("relationJoinKind");
                }
                return candidates;
            }
            String sourceKey = DatasetValues.joinKey(ctx.row(), binding.sourceKeyField());
            if (sourceKey == null) {
                return List.of();
            }
            List<DatasetRow> related = new ArrayList<>();
            for (DatasetRow row : candidates) {
                if (sourceKey.equals(DatasetValues.joinKey(row, binding.targetKeyField()))) {
                    related.add(row);
                }
            }
            return related;
        }

        private ResolvedDependency.RelationDependency requireRelationDependency(CanonicalRelationReference reference) {
            ResolvedDependency.RelationDependency dependency = relationsById.get(reference.id());
            if (dependency == null || !dependency.reference().equals(reference)) {
                throw mappingMissing("relationDependency");
            }
            return dependency;
        }

        private FixtureMapping.RelationBinding requireRelationBinding(
                ResolvedDependency.RelationDependency dependency) {
            CanonicalRelationReference reference = dependency.reference();
            FixtureMapping.RelationBinding binding = mapping.relation(
                    reference.id(),
                    dependency.sourceVersion().entity(), dependency.sourceVersion().schemaVersion(),
                    dependency.targetVersion().entity(), dependency.targetVersion().schemaVersion());
            if (binding == null || binding.sourceEntity() != reference.sourceEntity()
                    || binding.targetEntity() != reference.targetEntity()) {
                throw mappingMissing("relationBinding");
            }
            if (reference.sourceJoinField() != null
                    && binding.sourceKeyField() != requireField(reference.sourceJoinField(), reference.sourceEntity())) {
                throw mappingMissing("relationSourceKeyField");
            }
            if (reference.targetJoinField() != null
                    && binding.targetKeyField() != requireField(reference.targetJoinField(), reference.targetEntity())) {
                throw mappingMissing("relationTargetKeyField");
            }
            return binding;
        }

        private DatasetField requireField(String fieldName, SubjectKind entity) {
            DatasetField field = mapping.field(fieldName, entity);
            if (field == null) {
                throw mappingMissing("fieldBinding");
            }
            return field;
        }

        // -------------------------------------------------------------- windows

        private ResolvedWindowBinding requireWindow(String path, ResolvedWindowBinding.WindowScope scope) {
            ResolvedWindowBinding binding = windowsByPath.get(path);
            if (binding == null || binding.scope() != scope) {
                throw mappingMissing("windowBinding");
            }
            return binding;
        }

        private boolean inRelationWindow(
                DatasetRow row,
                ResolvedWindowBinding window,
                ResolvedDependency.RelationDependency dependency) {
            ResolvedWindowBinding.RelationWindow relationWindow = (ResolvedWindowBinding.RelationWindow) window;
            String eventTimeField = relationWindow.eventTimeField();
            if (eventTimeField == null) {
                return true;
            }
            DatasetField field = requireField(eventTimeField, dependency.reference().targetEntity());
            return withinWindow(
                    DatasetValues.instant(row, field), relationWindow.resolvedWindow(),
                    relationWindow.includeStart(), relationWindow.includeEnd());
        }

        private boolean inMetricWindow(
                DatasetRow row,
                ResolvedWindowBinding window,
                CanonicalMetricDefinition definition,
                RowContext sourceContext) {
            ResolvedWindowBinding.MetricWindow metricWindow = (ResolvedWindowBinding.MetricWindow) window;
            if (metricWindow.eventTimeSemantics() == EventTimeSemantics.NOT_APPLICABLE
                    || metricWindow.eventTimeField() == null) {
                return true;
            }
            String eventTimeField = metricWindow.eventTimeField();
            DatasetField field = requireField(eventTimeField, metricWindowFieldEntity(definition, eventTimeField));
            Instant eventTime = DatasetValues.instant(row, field);
            if (metricWindow.resolvedWindow() instanceof ResolvedWindow.RelativeToAttribute relative) {
                ResolvedDependency.AttributeDependency dependency =
                        attributesById.get(relative.anchorDefinitionId());
                if (dependency == null || dependency.definition().subject() != sourceContext.entity()) {
                    throw mappingMissing("relativeWindowAnchor");
                }
                FixtureMapping.AttributeBinding binding = mapping.attribute(
                        dependency.id(), dependency.entityVersion().entity(),
                        dependency.entityVersion().schemaVersion());
                if (binding == null) {
                    throw mappingMissing("relativeWindowAnchor");
                }
                Instant anchor = DatasetValues.instant(sourceContext.row(), binding.field());
                if (eventTime == null || anchor == null) {
                    return false;
                }
                try {
                    Instant start = anchor.plusSeconds(Math.multiplyExact(relative.startOffsetDays(), 86_400L));
                    Instant end = anchor.plusSeconds(Math.multiplyExact(relative.endOffsetDays(), 86_400L));
                    return bounded(eventTime, start, end, metricWindow.includeStart(), metricWindow.includeEnd());
                } catch (java.time.DateTimeException | ArithmeticException exception) {
                    throw unsupported("relativeWindowArithmetic");
                }
            }
            return withinWindow(
                    eventTime, metricWindow.resolvedWindow(),
                    metricWindow.includeStart(), metricWindow.includeEnd());
        }

        private SubjectKind metricWindowFieldEntity(CanonicalMetricDefinition definition, String eventTimeField) {
            ResolvedDependency.MetricDependency dependency = metricsById.get(definition.id());
            if (dependency == null) {
                throw mappingMissing("metricDependency");
            }
            return dependency.relation().reference().targetEntity();
        }

        private static boolean withinWindow(
                Instant instant, ResolvedWindow window, boolean includeStart, boolean includeEnd) {
            if (instant == null) {
                return false;
            }
            return switch (window) {
                case ResolvedWindow.Rolling rolling ->
                        bounded(instant, rolling.start(), rolling.end(), includeStart, includeEnd);
                case ResolvedWindow.Fixed fixed ->
                        bounded(instant, fixed.start(), fixed.end(), includeStart, includeEnd);
                case ResolvedWindow.Lifetime lifetime -> instant.isBefore(lifetime.dataWatermark());
                case ResolvedWindow.RelativeToAttribute ignored ->
                        throw unsupported("relativeWindowContext");
                case ResolvedWindow.SelectedEvent ignored ->
                        throw unsupported("selectedEventWindowContext");
                case ResolvedWindow.RelativeToEvent ignored ->
                        throw unsupported("relativeEventWindowContext");
            };
        }

        private static boolean bounded(
                Instant instant, Instant start, Instant end, boolean includeStart, boolean includeEnd) {
            boolean afterStart = includeStart ? !instant.isBefore(start) : instant.isAfter(start);
            boolean beforeEnd = includeEnd ? !instant.isAfter(end) : instant.isBefore(end);
            return afterStart && beforeEnd;
        }

        // -------------------------------------------------------------- currency

        private CurrencyBinding requireCurrencyBinding(
                MetadataKind kind, String definitionId, SemanticDataType dataType, CurrencySemantics semantics) {
            CurrencyBinding binding = currencyByKey.get(kind.name() + "|" + definitionId);
            if (binding == null) {
                throw mappingMissing("currencyBinding");
            }
            if (dataType == SemanticDataType.MONEY && (semantics == null || !semantics.isExplicitPolicy())) {
                throw unsupported("monetaryPolicy");
            }
            return binding;
        }

        private BigDecimal convert(BigDecimal amount, String sourceCurrency, CurrencyBinding binding) {
            BigDecimal rate = fxRateSnapshot.rate(sourceCurrency);
            if (rate == null) {
                throw new FixtureException(FixtureErrorCodes.FIXTURE_FX_INVALID, "fxRate",
                        "The FX-rate snapshot declares no exact rate for a required source currency.");
            }
            return amount.multiply(rate);
        }

        private int moneyCompare(
                BigDecimal left, String leftCurrency, CanonicalValue.MoneyValue right, CurrencyBinding binding) {
            if (leftCurrency == null) {
                throw new FixtureException(FixtureErrorCodes.FIXTURE_CURRENCY_MISMATCH, "currencyMissing",
                        "A monetary value has no currency and cannot be compared.");
            }
            BigDecimal rightAmount = numeric(right);
            if (binding.action() == CurrencyBinding.Action.CONVERT_TO_REPORTING_CURRENCY) {
                return convert(left, leftCurrency, binding).compareTo(convert(rightAmount, right.currency(), binding));
            }
            if (binding.action() == CurrencyBinding.Action.SINGLE_CURRENCY_ONLY) {
                if (!leftCurrency.equals(right.currency())) {
                    throw new FixtureException(FixtureErrorCodes.FIXTURE_CURRENCY_MISMATCH, "singleCurrency",
                            "A single-currency comparison named two different currencies.");
                }
                return left.compareTo(rightAmount);
            }
            throw unsupported("monetaryPolicy");
        }

        // -------------------------------------------------------------- comparison

        private Truth compareScalar(
                SemanticDataType type,
                StringSemantics stringSemantics,
                Object raw,
                String rawCurrency,
                ComparisonOperator operator,
                CanonicalValue operand,
                CurrencyBinding currency) {
            if (operator.isNullOperator()) {
                throw resultInvalid("nullOperator");
            }
            return switch (type) {
                case MONEY -> apply(operator,
                        TypedValue.comparably(item -> moneyCompare(
                                (BigDecimal) raw, rawCurrency, money(item), currency)), operand);
                case STRING -> compareString(stringSemantics, (String) raw, operator, operand);
                case ENUM, ORDERED_ENUM -> apply(operator,
                        TypedValue.comparably(item -> ((String) raw).compareTo(enumCode(item))), operand);
                case DATE -> apply(operator,
                        TypedValue.comparably(item -> ((String) raw).compareTo(date(item))), operand);
                case TIMESTAMP -> apply(operator,
                        TypedValue.comparably(item -> ((Instant) raw).compareTo(instant(item))), operand);
                case INTEGER, DECIMAL -> apply(operator,
                        TypedValue.comparably(item -> ((BigDecimal) raw).compareTo(numeric(item))), operand);
                case BOOLEAN -> compareBoolean((Boolean) raw, operator, operand);
            };
        }

        private Truth compareString(
                StringSemantics semantics, String raw, ComparisonOperator operator, CanonicalValue operand) {
            String left = normalizeString(semantics, raw);
            return switch (operator) {
                case CONTAINS -> Truth.of(left.contains(normalizeString(semantics, text(operand))));
                case NOT_CONTAINS -> Truth.of(!left.contains(normalizeString(semantics, text(operand))));
                case STARTS_WITH -> Truth.of(left.startsWith(normalizeString(semantics, text(operand))));
                case ENDS_WITH -> Truth.of(left.endsWith(normalizeString(semantics, text(operand))));
                default -> apply(operator,
                        TypedValue.comparably(item ->
                                left.compareTo(normalizeString(semantics, text(item)))), operand);
            };
        }

        private Truth compareBoolean(Boolean raw, ComparisonOperator operator, CanonicalValue operand) {
            boolean right = ((CanonicalValue.BooleanValue) operand).value();
            return switch (operator) {
                case EQUAL -> Truth.of(raw == right);
                case NOT_EQUAL -> Truth.of(raw != right);
                case IN -> Truth.of(anyMatch((CanonicalValue.ListValue) operand,
                        item -> ((CanonicalValue.BooleanValue) item).value() == raw));
                case NOT_IN -> Truth.of(!anyMatch((CanonicalValue.ListValue) operand,
                        item -> ((CanonicalValue.BooleanValue) item).value() == raw));
                default -> throw unsupported("booleanOperator");
            };
        }

        // -------------------------------------------------------------- operand readers

        static BigDecimal numeric(CanonicalValue value) {
            return switch (value) {
                case CanonicalValue.IntegerValue integer -> BigDecimal.valueOf(integer.value());
                case CanonicalValue.DecimalValue decimal -> new BigDecimal(decimal.value());
                case CanonicalValue.MoneyValue money -> new BigDecimal(money.amount());
                default -> throw resultInvalid("numericOperand");
            };
        }

        static Instant instant(CanonicalValue value) {
            if (value instanceof CanonicalValue.InstantValue instant) {
                return Instant.parse(instant.instant());
            }
            throw resultInvalid("instantOperand");
        }

        static String date(CanonicalValue value) {
            if (value instanceof CanonicalValue.DateValue date) {
                return date.value();
            }
            throw resultInvalid("dateOperand");
        }

        static String text(CanonicalValue value) {
            if (value instanceof CanonicalValue.TextValue text) {
                return text.value();
            }
            throw resultInvalid("textOperand");
        }

        static String enumCode(CanonicalValue value) {
            if (value instanceof CanonicalValue.EnumValue code) {
                return code.code();
            }
            throw resultInvalid("enumOperand");
        }

        static CanonicalValue.MoneyValue money(CanonicalValue value) {
            if (value instanceof CanonicalValue.MoneyValue money) {
                return money;
            }
            throw resultInvalid("moneyOperand");
        }

        static String distinctKey(Object value) {
            return value instanceof BigDecimal decimal
                    ? "n:" + decimal.stripTrailingZeros().toPlainString()
                    : "s:" + value;
        }

        /**
         * The accepted T07 string rule, mirrored because the canonicalizer keeps it package-private: the
         * declared Unicode form runs first, then locale-neutral or locale-specific case folding. No collation
         * is applied, because ordering decisions are Metadata-owned and already validated.
         */
        static String normalizeString(StringSemantics semantics, String value) {
            String text = value;
            if (semantics != null) {
                Normalizer.Form form = switch (semantics.normalization()) {
                    case NONE -> null;
                    case NFC -> Normalizer.Form.NFC;
                    case NFD -> Normalizer.Form.NFD;
                    case NFKC -> Normalizer.Form.NFKC;
                    case NFKD -> Normalizer.Form.NFKD;
                };
                if (form != null) {
                    text = Normalizer.normalize(text, form);
                }
                if (semantics.caseSensitivity() == StringCaseSensitivity.CASE_INSENSITIVE) {
                    Locale locale = semantics.isLocaleNeutral() ? Locale.ROOT : semantics.locale().toLocale();
                    text = text.toLowerCase(locale);
                }
            }
            return text;
        }
    }

    // ------------------------------------------------------------------ failures

    private static void requirePresent(Object value, String invariant) {
        if (value == null) {
            throw new FixtureException(FixtureErrorCodes.FIXTURE_INPUT_MISSING, invariant,
                    "The fixture executor requires a non-null input.");
        }
    }

    private static FixtureException planMismatch(String invariant) {
        return new FixtureException(FixtureErrorCodes.FIXTURE_PLAN_MISMATCH, invariant,
                "The plan, the fixture dataset and the trusted context disagree on a pinned value.");
    }

    private static FixtureException mappingMissing(String invariant) {
        return new FixtureException(FixtureErrorCodes.FIXTURE_MAPPING_MISSING, invariant,
                "A plan dependency has no exact server-owned mapping entry for this dataset.");
    }

    private static FixtureException unsupported(String invariant) {
        return new FixtureException(FixtureErrorCodes.FIXTURE_UNSUPPORTED_SEMANTIC, invariant,
                "An accepted plan combination has no deterministic fixture semantics.");
    }

    private static FixtureException resultInvalid(String invariant) {
        return new FixtureException(FixtureErrorCodes.FIXTURE_RESULT_INVALID, invariant,
                "A fixture execution invariant could not be evaluated.");
    }

    /** An exact-pin local lookup could not answer; an unknown membership set is never an empty one. */
    private static FixtureException unavailable(String invariant) {
        return new FixtureException(FixtureErrorCodes.FIXTURE_REFERENCE_MEMBERSHIP_MISSING, invariant,
                "No local membership dataset answers for one exact frozen audience version pin.");
    }
}
