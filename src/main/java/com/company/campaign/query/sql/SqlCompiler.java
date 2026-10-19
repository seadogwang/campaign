package com.company.campaign.query.sql;

import com.company.campaign.metadata.MetadataKind;
import com.company.campaign.metadata.domain.AggregateFunction;
import com.company.campaign.metadata.domain.ComparisonOperator;
import com.company.campaign.metadata.domain.EmptySetBehavior;
import com.company.campaign.metadata.domain.EntityVersionRef;
import com.company.campaign.metadata.domain.MetadataFilterEntry;
import com.company.campaign.metadata.domain.MetadataScalarValue;
import com.company.campaign.metadata.domain.RefundTreatment;
import com.company.campaign.metadata.domain.RelationJoinKind;
import com.company.campaign.metadata.domain.SemanticDataType;
import com.company.campaign.metadata.domain.StringCaseSensitivity;
import com.company.campaign.metadata.domain.StringNormalization;
import com.company.campaign.metadata.domain.StringSemantics;
import com.company.campaign.metadata.domain.SubjectKind;
import com.company.campaign.query.QueryException;
import com.company.campaign.query.ast.QueryAst;
import com.company.campaign.query.plan.AudienceVersionDependency;
import com.company.campaign.query.plan.CurrencyBinding;
import com.company.campaign.query.plan.DataScopePredicate;
import com.company.campaign.query.plan.ReferenceExecutionCheck;
import com.company.campaign.query.plan.ResolvedDependency;
import com.company.campaign.query.plan.ResolvedWindow;
import com.company.campaign.query.plan.ResolvedWindowBinding;
import com.company.campaign.query.plan.SemanticQueryPlan;
import com.company.campaign.query.plan.TrustedPlanContext;
import com.company.campaign.segment.canonical.CanonicalAudienceVersionRef;
import com.company.campaign.segment.canonical.CanonicalDefinition;
import com.company.campaign.segment.canonical.CanonicalMetricDefinition;
import com.company.campaign.segment.canonical.CanonicalMetricExpression;
import com.company.campaign.segment.canonical.CanonicalMetricExpressionType;
import com.company.campaign.segment.canonical.CanonicalMetricFilter;
import com.company.campaign.segment.canonical.CanonicalRelationReference;
import com.company.campaign.segment.canonical.CanonicalValue;
import com.company.campaign.shared.canonical.ContentHash;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The one deterministic compiler from a genuine T08 {@link SemanticQueryPlan} and an exact server-owned
 * {@link StorageCatalog} to one immutable {@link CompiledSql} artifact.
 *
 * <p>Its inputs are mandatory and narrow: a real plan, an exact catalog, a bounded {@link SqlCompilePolicy}
 * and — only when an exact conversion is required — an immutable {@link FxRateBinding}. No table, schema,
 * column, join, function, operator or SQL fragment can arrive from the Segment DSL, a model, a request or any
 * other caller string, because the compiler never reads one: every physical name comes from the catalog and
 * every value becomes a typed bind parameter.
 *
 * <p>Before producing an artifact the compiler re-proves plan/catalog compatibility for every referenced
 * Attribute, Metric and Relation: entity/definition/relation/schema pins are looked up exactly, the storage
 * data type, result type, relation, aggregate, empty-set behavior, filter field and event-time field must
 * agree, and anything an exact lowering cannot represent fails closed with a stable code rather than being
 * approximated. Semantics are preserved exactly: grouping is never flattened, one {@code EXISTS} stays one
 * same-row correlated subquery, {@code NOT_EQUAL}/{@code NOT_IN} stay NULL-excluding, {@code IS_NULL} stays
 * explicit, existence-equivalent {@code COUNT} comparisons become correlated {@code EXISTS}/{@code NOT EXISTS}
 * predicates while all other counts remain scalar aggregates, window boundaries and inclusivity come only
 * from the plan's resolved bindings and every ID, timestamp, currency, rate pin and operand is a parameter.
 *
 * <p>The compiler is immutable and holds no state between calls, so it is thread-safe and repeated
 * compilations of identical inputs produce byte-identical artifacts.
 */
public final class SqlCompiler {

    private static final char LIKE_ESCAPE = '!';
    private static final String PATH_ROOT = "r";
    private static final String NOT_STEP = ".not";
    private static final String EXISTS_STEP = ".exists";

    /** Compiles a plan with no external binding; a required conversion then fails closed. */
    public CompiledSql compile(SemanticQueryPlan plan, StorageCatalog catalog, SqlCompilePolicy policy) {
        return compile(plan, catalog, policy, null, null);
    }

    /**
     * Compiles a plan with an optional immutable FX binding.
     *
     * <p>{@code fxRateBinding} is required only when the plan pins a currency conversion and the catalog
     * declares an FX strategy; otherwise it is ignored, and a conversion without one fails closed.
     *
     * <p>A plan that freezes an Existing Audience Reference cannot be compiled here: SQL generation must not
     * be able to weaken or remove the execution-time re-check, so the call fails closed with
     * {@link SqlErrorCodes#SQL_REFERENCE_GUARD_REQUIRED}. Use
     * {@link #compile(SemanticQueryPlan, StorageCatalog, SqlCompilePolicy, FxRateBinding, ReferenceExecutionCheck)}
     * with a host-built check instead.
     */
    public CompiledSql compile(
            SemanticQueryPlan plan,
            StorageCatalog catalog,
            SqlCompilePolicy policy,
            FxRateBinding fxRateBinding) {
        return compile(plan, catalog, policy, fxRateBinding, null);
    }

    /**
     * Compiles a plan, re-checking every frozen audience reference before any artifact is produced.
     *
     * <p>{@code referenceExecutionCheck} is required exactly when the plan freezes at least one Existing
     * Audience Reference. It is run first — before a single node is lowered — so a withdrawn version, a lost
     * {@code SEGMENT} permission, a released retention hold or a moved effective DataScope refuses to produce
     * SQL at all. A plan with no reference ignores the argument completely, so a reference-free compilation is
     * byte-identical to what it was before references existed.
     */
    public CompiledSql compile(
            SemanticQueryPlan plan,
            StorageCatalog catalog,
            SqlCompilePolicy policy,
            FxRateBinding fxRateBinding,
            ReferenceExecutionCheck referenceExecutionCheck) {
        requirePresent(plan, "plan");
        requirePresent(catalog, "catalog");
        requirePresent(policy, "policy");
        if (plan.hasAudienceReferences()) {
            if (referenceExecutionCheck == null) {
                throw new SqlException(SqlErrorCodes.SQL_REFERENCE_GUARD_REQUIRED, "referenceExecutionCheck",
                        "Compiling an Existing Audience Reference requires the host execution check.");
            }
            try {
                referenceExecutionCheck.requireExecutable(plan);
            } catch (QueryException exception) {
                // The guard's own stable non-disclosing refusal is preserved verbatim.
                throw exception;
            }
        }
        try {
            return new Builder(plan, catalog, policy, fxRateBinding).compile();
        } catch (SqlException exception) {
            // A genuine stable failure already carries its own bounded code; never relabel it.
            throw exception;
        } catch (RuntimeException exception) {
            throw new SqlException(SqlErrorCodes.SQL_AST_INVALID, "compileInvariant",
                    "The compiler could not evaluate a plan invariant and published no artifact.");
        }
    }

    private static void requirePresent(Object value, String invariant) {
        if (value == null) {
            throw new SqlException(SqlErrorCodes.SQL_INPUT_MISSING, invariant,
                    "The SQL compiler requires a non-null input.");
        }
    }

    /** One compilation; all mutable counters live here and never on the compiler itself. */
    private static final class Builder {

        private final SemanticQueryPlan plan;
        private final StorageCatalog catalog;
        private final SqlCompilePolicy policy;
        private final FxRateBinding fxRateBinding;
        private final TrustedPlanContext context;
        private final Map<String, AudienceVersionDependency> audienceByPin = new LinkedHashMap<>();
        private final Map<String, ResolvedDependency.AttributeDependency> attributesById = new LinkedHashMap<>();
        private final Map<String, ResolvedDependency.MetricDependency> metricsById = new LinkedHashMap<>();
        private final Map<String, ResolvedDependency.RelationDependency> relationsById = new LinkedHashMap<>();
        private final Map<String, ResolvedWindowBinding> windowsByPath = new LinkedHashMap<>();
        private final Map<String, CurrencyBinding> currencyByKey = new LinkedHashMap<>();

        private int aliasCounter;
        private int parameterCount;
        private boolean fxRequired;

        Builder(SemanticQueryPlan plan, StorageCatalog catalog, SqlCompilePolicy policy,
                FxRateBinding fxRateBinding) {
            this.plan = plan;
            this.catalog = catalog;
            this.policy = policy;
            this.fxRateBinding = fxRateBinding;
            this.context = plan.trustedContext();
            for (AudienceVersionDependency dependency : plan.audienceDependencies()) {
                audienceByPin.put(dependency.reference().pinKey(), dependency);
            }
            for (ResolvedDependency dependency : plan.dependencies()) {
                switch (dependency) {
                    case ResolvedDependency.AttributeDependency attribute ->
                            attributesById.put(attribute.id(), attribute);
                    case ResolvedDependency.MetricDependency metric -> metricsById.put(metric.id(), metric);
                    case ResolvedDependency.RelationDependency relation ->
                            relationsById.put(relation.id(), relation);
                }
            }
            for (ResolvedWindowBinding binding : plan.windowBindings()) {
                windowsByPath.put(binding.path(), binding);
            }
            for (CurrencyBinding binding : plan.currencyBindings()) {
                currencyByKey.put(currencyBindingKey(binding.kind(), binding.definitionId()), binding);
            }
        }

        CompiledSql compile() {
            SqlSelect root = compileRoot();
            SqlRenderer.Rendered rendered = SqlRenderer.render(root);
            ContentHash external = fxRequired && fxRateBinding != null ? fxRateBinding.contentHash() : null;
            ContentHash planHash = plan.planHash();
            ContentHash catalogHash = catalog.contentHash();
            ContentHash compiledHash = CompiledSql.computeHash(
                    root, rendered.sql(), rendered.parameters(), planHash, catalog.catalogVersion(), catalogHash,
                    SqlVersions.SQL_COMPILER_VERSION, SqlVersions.SQL_AST_VERSION,
                    SqlVersions.SQL_RENDER_FORMAT_VERSION, external);
            return new CompiledSql(
                    root, rendered.sql(), rendered.parameters(), planHash, catalog.catalogVersion(), catalogHash,
                    SqlVersions.SQL_COMPILER_VERSION, SqlVersions.SQL_AST_VERSION,
                    SqlVersions.SQL_RENDER_FORMAT_VERSION, external, compiledHash);
        }

        // ------------------------------------------------------------------ root

        private SqlSelect compileRoot() {
            if (plan.subject() != SubjectKind.MEMBER) {
                throw unsupported("planSubject");
            }
            DataScopePredicate dataScope = plan.dataScopePredicate();
            if (dataScope.subject() != plan.subject()) {
                throw planMismatch("dataScopePredicate");
            }
            // A reference-free plan must still require exactly the caller's scope. A reference-bearing plan
            // requires the host-computed effective intersection and its exact content hash, which is what the
            // mandatory DataScope subquery below then binds; claiming only the caller's scope there would be a
            // wider requirement than the referenced versions allow.
            if (dataScope.isEffectiveIntersection()) {
                if (plan.audienceDependencies().isEmpty() || dataScope.dataScopeHash() == null) {
                    throw planMismatch("dataScopePredicate");
                }
            } else if (!dataScope.dataScopeId().equals(context.dataScopeId())) {
                throw planMismatch("dataScopePredicate");
            }
            EntityVersionRef rootVersion = rootEntityVersion();
            StorageCatalog.EntityStorage rootStorage = catalog.entity(rootVersion);
            if (rootStorage == null) {
                throw mappingMissing("rootEntity");
            }

            SqlIdentifier rootAlias = nextAlias("m");
            SqlExpression memberKey = new SqlExpression.Column(rootAlias, rootStorage.primaryKeyColumn());

            // The root predicates use the exact context columns the catalog declares for the selected root
            // entity shape, exactly as every related-entity subquery uses its own entity mapping. The legacy
            // catalog-level context is gone, so there is no second, possibly contradictory, execution fact.
            List<SqlExpression> predicates =
                    new ArrayList<>(contextPredicates(rootAlias, rootStorage.context(), "rootContext"));
            predicates.add(dataScopeExists(rootAlias, memberKey));
            predicates.add(compileNode(
                    plan.predicateRoot(), rootAlias, plan.subject(), rootVersion, PATH_ROOT, 0));

            return new SqlSelect(
                    memberKey, rootStorage.table(), rootAlias, new SqlExpression.And(List.copyOf(predicates)));
        }

        /**
         * The mandatory DataScope membership predicate: one correlated {@code EXISTS} over the server-owned
         * membership table, keyed by the trusted scope identifier and bound to the root key. It is a sibling of
         * the user predicate, never part of it, so no user input and no optimization can remove it.
         */
        private SqlExpression dataScopeExists(SqlIdentifier rootAlias, SqlExpression memberKey) {
            StorageCatalog.DataScopeStorage scope = catalog.dataScope();
            SqlIdentifier scopeAlias = nextAlias("d");
            List<SqlExpression> predicates = List.of(
                    new SqlExpression.Comparison(
                            new SqlExpression.Column(scopeAlias, scope.scopeIdColumn()),
                            SqlComparisonOperator.EQUAL,
                            parameter(SqlParameter.contextId(plan.dataScopePredicate().dataScopeId()))),
                    new SqlExpression.Comparison(
                            new SqlExpression.Column(scopeAlias, scope.tenantColumn()),
                            SqlComparisonOperator.EQUAL,
                            parameter(SqlParameter.contextId(context.tenantId()))),
                    new SqlExpression.Comparison(
                            new SqlExpression.Column(scopeAlias, scope.programColumn()),
                            SqlComparisonOperator.EQUAL,
                            parameter(SqlParameter.contextId(context.programId()))),
                    new SqlExpression.Comparison(
                            new SqlExpression.Column(scopeAlias, scope.memberIdColumn()),
                            SqlComparisonOperator.EQUAL,
                            memberKey));
            SqlSelect subquery = new SqlSelect(
                    new SqlExpression.Column(scopeAlias, scope.memberIdColumn()),
                    scope.table(),
                    scopeAlias,
                    new SqlExpression.And(predicates));
            return new SqlExpression.Exists(subquery);
        }

        /**
         * The mandatory tenant/program context predicates for one tenant-owned table.
         *
         * <p>They constrain the root SELECT and every correlated subquery table, and are compiler-owned
         * siblings of the correlation, window, filter and user predicates of the select they belong to, built
         * only from the trusted plan context and the exact context columns the catalog declares for that table
         * shape. A request, DSL or plan predicate can neither supply, replace nor remove one, and a shape whose
         * context mapping the catalog does not declare fails closed rather than silently reading rows from
         * another tenant or program.
         */
        private List<SqlExpression> contextPredicates(
                SqlIdentifier alias, StorageCatalog.ContextStorage storage, String invariant) {
            if (storage == null) {
                throw mappingMissing(invariant);
            }
            return List.of(
                    new SqlExpression.Comparison(
                            new SqlExpression.Column(alias, storage.tenantColumn()),
                            SqlComparisonOperator.EQUAL,
                            parameter(SqlParameter.contextId(context.tenantId()))),
                    new SqlExpression.Comparison(
                            new SqlExpression.Column(alias, storage.programColumn()),
                            SqlComparisonOperator.EQUAL,
                            parameter(SqlParameter.contextId(context.programId()))));
        }

        /**
         * Resolves the root entity version from facts the plan already carries, never by re-reading Metadata:
         * a root-subject dependency pin, or the source/target pin of a relation that crosses the root entity.
         * Two disagreeing pins are contradictory and fail closed.
         */
        private EntityVersionRef rootEntityVersion() {
            SubjectKind root = plan.subject();
            EntityVersionRef resolved = null;
            for (ResolvedDependency dependency : plan.dependencies()) {
                EntityVersionRef candidate = switch (dependency) {
                    case ResolvedDependency.AttributeDependency attribute ->
                            attribute.entityVersion().entity() == root ? attribute.entityVersion() : null;
                    case ResolvedDependency.MetricDependency metric ->
                            metric.entityVersion().entity() == root ? metric.entityVersion() : null;
                    case ResolvedDependency.RelationDependency relation ->
                            relation.reference().sourceEntity() == root ? relation.sourceVersion()
                                    : (relation.reference().targetEntity() == root ? relation.targetVersion() : null);
                };
                if (candidate != null) {
                    if (resolved != null && !resolved.equals(candidate)) {
                        throw planMismatch("rootEntityVersion");
                    }
                    resolved = candidate;
                }
            }
            if (resolved == null) {
                // A plan whose only content is an Existing Audience Reference has no Metadata dependency to
                // pin the root entity. The server-owned membership binding declares the exact member entity
                // version its rows key on, so the root entity is still an allowlisted catalog fact rather
                // than an assumption.
                StorageCatalog.AudienceMembershipStorage membership =
                        plan.hasAudienceReferences() ? catalog.audienceMembership() : null;
                if (membership == null || membership.memberVersion().entity() != root) {
                    throw mappingMissing("rootEntityVersion");
                }
                return membership.memberVersion();
            }
            return resolved;
        }

        // ------------------------------------------------------------------ traversal

        private SqlExpression compileNode(
                QueryAst node,
                SqlIdentifier alias,
                SubjectKind entity,
                EntityVersionRef version,
                String path,
                int depth) {
            return switch (node) {
                case QueryAst.Group group -> {
                    List<SqlExpression> children = new ArrayList<>(group.children().size());
                    for (int index = 0; index < group.children().size(); index++) {
                        children.add(compileNode(
                                group.children().get(index), alias, entity, version, path + "." + index, depth));
                    }
                    yield group.kind() == QueryAst.GroupKind.ALL
                            ? new SqlExpression.And(children)
                            : new SqlExpression.Or(children);
                }
                case QueryAst.Negation negation -> new SqlExpression.Not(
                        compileNode(negation.child(), alias, entity, version, path + NOT_STEP, depth));
                case QueryAst.Attribute attribute -> compileAttribute(attribute, alias, entity, version);
                case QueryAst.Metric metric -> compileMetric(metric, alias, entity, version, path, depth);
                case QueryAst.MetricCalculation calculation ->
                        compileMetricCalculation(calculation, alias, entity, version, path, depth);
                case QueryAst.Exists exists -> compileExists(exists, alias, entity, version, path, depth);
                case QueryAst.AudienceReference reference ->
                        compileAudienceReference(reference, alias, entity, version, depth);
            };
        }

        // ------------------------------------------------------------------ attributes

        private SqlExpression compileAttribute(
                QueryAst.Attribute attribute, SqlIdentifier alias, SubjectKind entity, EntityVersionRef version) {
            CanonicalDefinition definition = attribute.definition();
            ResolvedDependency.AttributeDependency dependency = attributeDependency(definition.id());
            if (!dependency.definition().equals(definition)) {
                throw planMismatch("attributeDependency");
            }
            if (definition.subject() != entity) {
                throw unsupported("attributeSubject");
            }
            StorageCatalog.AttributeStorage storage =
                    catalog.attribute(definition.id(), entity, version.schemaVersion());
            if (storage == null) {
                throw mappingMissing("attributeStorage");
            }
            if (storage.dataType() != definition.dataType()) {
                throw mappingMissing("attributeDataType");
            }

            SqlExpression column = new SqlExpression.Column(alias, storage.valueColumn());
            ComparisonOperator operator = attribute.operator();
            if (operator.isNullOperator()) {
                return operator == ComparisonOperator.IS_NULL
                        ? new SqlExpression.IsNull(column)
                        : new SqlExpression.IsNotNull(column);
            }
            if (definition.dataType() == SemanticDataType.MONEY) {
                return monetaryComparison(column, storage.currencyColumn(), attribute, alias);
            }
            if (definition.dataType() == SemanticDataType.STRING) {
                requireIdentityStringSemantics(definition.stringSemantics());
            }
            return plainComparison(column, operator, attribute.operand());
        }

        /**
         * Compiles one monetary comparison under its exact per-definition T08 currency action.
         *
         * <p>A single-currency comparison adds the exact same-currency predicate, so two different currencies
         * are never compared as if they were one. A conversion requires the catalog's FX strategy and an
         * explicit pinned binding, converts both sides with a correlated parameterized rate lookup and never
         * assumes a rate. Anything else fails closed.
         */
        private SqlExpression monetaryComparison(
                SqlExpression valueColumn,
                SqlIdentifier currencyColumn,
                QueryAst.Attribute attribute,
                SqlIdentifier alias) {
            ComparisonOperator operator = attribute.operator();
            if (operator == ComparisonOperator.IN || operator == ComparisonOperator.NOT_IN
                    || operator == ComparisonOperator.BETWEEN) {
                throw unsupported("monetaryOperator");
            }
            if (!(attribute.operand() instanceof CanonicalValue.MoneyValue money)) {
                throw planMismatch("moneyOperand");
            }
            if (currencyColumn == null) {
                throw unsupported("attributeCurrencyColumn");
            }
            CurrencyBinding binding = currencyBinding(MetadataKind.ATTRIBUTE, attribute.definition().id());
            SqlComparisonOperator sqlOperator = sqlOperator(operator);
            return switch (binding.action()) {
                case CONVERT_TO_REPORTING_CURRENCY -> {
                    requireFxConversion();
                    SqlExpression left = new SqlExpression.Multiply(
                            valueColumn, rateExpression(new SqlExpression.Column(alias, currencyColumn)));
                    SqlExpression right = new SqlExpression.Multiply(
                            parameter(SqlParameter.decimal(new BigDecimal(money.amount()))),
                            rateExpression(parameter(SqlParameter.currency(money.currency()))));
                    yield new SqlExpression.Comparison(left, sqlOperator, right);
                }
                case SINGLE_CURRENCY_ONLY -> new SqlExpression.And(List.of(
                        new SqlExpression.Comparison(
                                valueColumn, sqlOperator,
                                parameter(SqlParameter.decimal(new BigDecimal(money.amount())))),
                        new SqlExpression.Comparison(
                                new SqlExpression.Column(alias, currencyColumn),
                                SqlComparisonOperator.EQUAL,
                                parameter(SqlParameter.currency(money.currency())))));
                default -> throw unsupported("monetaryPolicy");
            };
        }

        private SqlExpression plainComparison(
                SqlExpression left, ComparisonOperator operator, CanonicalValue operand) {
            return switch (operator) {
                case IN -> new SqlExpression.InList(left, listParameters(operand), false);
                case NOT_IN -> new SqlExpression.InList(left, listParameters(operand), true);
                case BETWEEN -> {
                    CanonicalValue.RangeValue range = (CanonicalValue.RangeValue) operand;
                    yield new SqlExpression.Between(
                            left,
                            scalarParameter(range.lower()),
                            scalarParameter(range.upper()),
                            range.includeLower(),
                            range.includeUpper());
                }
                case CONTAINS -> like(left, "%" + escapeLike(text(operand)) + "%");
                case STARTS_WITH -> like(left, escapeLike(text(operand)) + "%");
                case ENDS_WITH -> like(left, "%" + escapeLike(text(operand)));
                case NOT_CONTAINS -> new SqlExpression.Not(like(left, "%" + escapeLike(text(operand)) + "%"));
                case IS_NULL, IS_NOT_NULL -> throw planMismatch("nullOperator");
                default -> new SqlExpression.Comparison(left, sqlOperator(operator), scalarParameter(operand));
            };
        }

        private SqlExpression like(SqlExpression value, String pattern) {
            // The pattern is bound through the counted compiler path, so it increments the exact same
            // parameter counter as every other value and can never bypass SqlCompilePolicy.maxParameters().
            return new SqlExpression.Like(value, parameter(SqlParameter.text(pattern)), LIKE_ESCAPE);
        }

        // ------------------------------------------------------------------ metrics

        private SqlExpression compileMetricCalculation(
                QueryAst.MetricCalculation calculation,
                SqlIdentifier sourceAlias,
                SubjectKind entity,
                EntityVersionRef version,
                String path,
                int depth) {
            SqlExpression value = compileMetricExpression(
                    calculation.expression(), calculation.window(), calculation.operand(), sourceAlias,
                    entity, version, path + ".expression.root", depth);
            return metricExpressionComparison(
                    value, calculation.expression().resultType(), calculation.operator(), calculation.operand());
        }

        private SqlExpression compileMetricExpression(
                CanonicalMetricExpression expression,
                com.company.campaign.segment.canonical.CanonicalWindow window,
                CanonicalValue comparisonOperand,
                SqlIdentifier sourceAlias,
                SubjectKind entity,
                EntityVersionRef version,
                String path,
                int depth) {
            return switch (expression) {
                case CanonicalMetricExpression.MetricRef reference -> compileExpressionMetricValue(
                        reference.definition(), window, comparisonOperand, sourceAlias, entity, version, path, depth);
                case CanonicalMetricExpression.Add add -> {
                    List<SqlExpression> operands = new ArrayList<>(add.operands().size());
                    for (int index = 0; index < add.operands().size(); index++) {
                        operands.add(compileMetricExpression(
                                add.operands().get(index), window, comparisonOperand, sourceAlias, entity, version,
                                path + "." + index, depth));
                    }
                    yield new SqlExpression.Add(operands);
                }
                case CanonicalMetricExpression.Subtract subtract -> new SqlExpression.Subtract(
                        compileMetricExpression(
                                subtract.left(), window, comparisonOperand, sourceAlias, entity, version,
                                path + ".left", depth),
                        compileMetricExpression(
                                subtract.right(), window, comparisonOperand, sourceAlias, entity, version,
                                path + ".right", depth));
                case CanonicalMetricExpression.Multiply multiply -> new SqlExpression.Multiply(
                        compileMetricExpression(
                                multiply.left(), window, comparisonOperand, sourceAlias, entity, version,
                                path + ".left", depth),
                        compileMetricExpression(
                                multiply.right(), window, comparisonOperand, sourceAlias, entity, version,
                                path + ".right", depth));
                case CanonicalMetricExpression.Divide divide -> new SqlExpression.Divide(
                        new SqlExpression.CastDecimal(compileMetricExpression(
                                divide.left(), window, comparisonOperand, sourceAlias, entity, version,
                                path + ".left", depth)),
                        new SqlExpression.CastDecimal(compileMetricExpression(
                                divide.right(), window, comparisonOperand, sourceAlias, entity, version,
                                path + ".right", depth)),
                        (SqlExpression.BindParameter) parameter(SqlParameter.decimal(BigDecimal.ZERO)));
            };
        }

        private SqlExpression compileExpressionMetricValue(
                CanonicalMetricDefinition definition,
                com.company.campaign.segment.canonical.CanonicalWindow window,
                CanonicalValue comparisonOperand,
                SqlIdentifier sourceAlias,
                SubjectKind entity,
                EntityVersionRef version,
                String path,
                int depth) {
            ResolvedDependency.MetricDependency dependency = metricDependency(definition.id());
            if (!dependency.definition().equals(definition) || definition.subject() != entity) {
                throw planMismatch("metricExpressionDependency");
            }
            StorageCatalog.MetricStorage storage = catalog.metric(definition.id(), entity, version.schemaVersion());
            if (storage == null
                    || storage.resultType() != definition.resultType()
                    || !storage.relationId().equals(dependency.relationId())) {
                throw mappingMissing("metricExpressionStorage");
            }
            if (!storage.supportedAggregates().contains(definition.aggregation())
                    || !storage.supportedEmptySetBehaviors().contains(definition.emptySetBehavior())) {
                throw unsupported("metricExpressionAggregate");
            }
            requireSupportedMetricResult(definition.resultType());
            if (definition.aggregation() == AggregateFunction.COUNT
                    && dependency.refundTreatment() == RefundTreatment.SUBTRACT) {
                throw unsupported("metricExpressionRefundTreatment");
            }
            if (dependency.eventTimeField() != null
                    && !dependency.eventTimeField().equals(storage.eventTimeField())) {
                throw mappingMissing("metricExpressionEventTimeField");
            }

            ResolvedDependency.RelationDependency relationDependency = dependency.relation();
            StorageCatalog.RelationStorage relationStorage = relationStorage(relationDependency);
            if (relationStorage.joinKind() == RelationJoinKind.TARGET_FILTERED) {
                throw unsupported("relationJoinKind");
            }
            ResolvedWindowBinding windowBinding = windowBinding(path, ResolvedWindowBinding.WindowScope.METRIC);
            if (!(windowBinding instanceof ResolvedWindowBinding.MetricWindow metricWindow)
                    || !metricWindow.canonicalWindow().equals(window)) {
                throw planMismatch("metricExpressionWindow");
            }

            EntityVersionRef targetVersion = relationDependency.targetVersion();
            requireDepth(depth + 1);
            StorageCatalog.EntityStorage targetStorage = catalog.entity(targetVersion);
            if (targetStorage == null) {
                throw mappingMissing("targetEntity");
            }
            SqlIdentifier targetAlias = nextAlias("r");
            List<SqlExpression> predicates = new ArrayList<>(
                    contextPredicates(targetAlias, targetStorage.context(), "metricContext"));
            if (relationStorage.sourceKeyColumn() != null) {
                predicates.add(new SqlExpression.Comparison(
                        new SqlExpression.Column(targetAlias, relationStorage.targetKeyColumn()),
                        SqlComparisonOperator.EQUAL,
                        new SqlExpression.Column(sourceAlias, relationStorage.sourceKeyColumn())));
            }
            if (metricWindow.eventTimeField() != null) {
                if (storage.eventTimeColumn() == null) {
                    throw mappingMissing("metricEventTimeColumn");
                }
                predicates.add(windowPredicate(
                        metricWindow, targetAlias, storage.eventTimeColumn(), sourceAlias, entity, version));
            }
            for (MetadataFilterEntry entry : dependency.filter().entries()) {
                SqlIdentifier filterColumn = storage.filterColumns().get(entry.key());
                if (filterColumn == null) {
                    throw mappingMissing("metricFilterField");
                }
                predicates.add(new SqlExpression.InList(
                        new SqlExpression.Column(targetAlias, filterColumn),
                        textParameters(entry.values()),
                        false));
            }
            addSingleCurrencyMetricPredicate(
                    definition, comparisonOperand, storage, targetAlias, predicates);
            SqlExpression aggregate = applyEmptySet(
                    compileAggregate(definition, dependency, storage, targetStorage, targetAlias), definition);
            return new SqlExpression.ScalarSubquery(new SqlSelect(
                    aggregate,
                    targetStorage.table(),
                    targetAlias,
                    new SqlExpression.And(List.copyOf(predicates))));
        }

        private SqlExpression metricExpressionComparison(
                SqlExpression expression,
                CanonicalMetricExpressionType resultType,
                ComparisonOperator operator,
                CanonicalValue operand) {
            if (operator.isNullOperator()
                    || operator == ComparisonOperator.IN
                    || operator == ComparisonOperator.NOT_IN
                    || operator == ComparisonOperator.BETWEEN) {
                throw unsupported("metricExpressionOperator");
            }
            if (resultType.dataType() != SemanticDataType.MONEY) {
                return plainComparison(expression, operator, operand);
            }
            if (!(operand instanceof CanonicalValue.MoneyValue money)) {
                throw planMismatch("metricExpressionMoneyOperand");
            }
            SqlExpression right = switch (resultType.currencySemantics()) {
                case CONVERT_TO_REPORTING_CURRENCY -> {
                    requireFxConversion();
                    yield new SqlExpression.Multiply(
                            parameter(SqlParameter.decimal(new BigDecimal(money.amount()))),
                            rateExpression(parameter(SqlParameter.currency(money.currency()))));
                }
                case SINGLE_CURRENCY_ONLY ->
                        parameter(SqlParameter.decimal(new BigDecimal(money.amount())));
                case NOT_APPLICABLE -> throw unsupported("metricExpressionCurrencyAction");
            };
            return new SqlExpression.Comparison(expression, sqlOperator(operator), right);
        }

        private SqlExpression compileMetric(
                QueryAst.Metric metric,
                SqlIdentifier sourceAlias,
                SubjectKind entity,
                EntityVersionRef version,
                String path,
                int depth) {
            CanonicalMetricDefinition definition = metric.definition();
            ResolvedDependency.MetricDependency dependency = metricDependency(definition.id());
            if (!dependency.definition().equals(definition)) {
                throw planMismatch("metricDependency");
            }
            if (definition.subject() != entity) {
                throw unsupported("metricSubject");
            }
            StorageCatalog.MetricStorage storage = catalog.metric(definition.id(), entity, version.schemaVersion());
            if (storage == null) {
                throw mappingMissing("metricStorage");
            }
            if (storage.resultType() != definition.resultType()) {
                throw mappingMissing("metricResultType");
            }
            if (!storage.relationId().equals(dependency.relationId())) {
                throw mappingMissing("metricRelationId");
            }
            if (!storage.supportedAggregates().contains(definition.aggregation())) {
                throw unsupported("metricAggregation");
            }
            if (!storage.supportedEmptySetBehaviors().contains(definition.emptySetBehavior())) {
                throw unsupported("metricEmptySet");
            }
            requireSupportedMetricResult(definition.resultType());
            if (definition.aggregation() == AggregateFunction.COUNT
                    && dependency.refundTreatment() == RefundTreatment.SUBTRACT) {
                throw unsupported("metricRefundTreatment");
            }
            if (dependency.eventTimeField() != null
                    && !dependency.eventTimeField().equals(storage.eventTimeField())) {
                throw mappingMissing("metricEventTimeField");
            }

            ResolvedDependency.RelationDependency relationDependency = dependency.relation();
            StorageCatalog.RelationStorage relationStorage = relationStorage(relationDependency);
            if (relationStorage.joinKind() == RelationJoinKind.TARGET_FILTERED) {
                throw unsupported("relationJoinKind");
            }

            ResolvedWindowBinding window = windowBinding(path, ResolvedWindowBinding.WindowScope.METRIC);
            if (!(window instanceof ResolvedWindowBinding.MetricWindow metricWindow)) {
                throw planMismatch("metricWindow");
            }

            EntityVersionRef targetVersion = relationDependency.targetVersion();
            requireDepth(depth + 1);
            StorageCatalog.EntityStorage targetStorage = catalog.entity(targetVersion);
            if (targetStorage == null) {
                throw mappingMissing("targetEntity");
            }
            SqlIdentifier targetAlias = nextAlias("r");

            List<SqlExpression> predicates = new ArrayList<>(
                    contextPredicates(targetAlias, targetStorage.context(), "metricContext"));
            if (relationStorage.sourceKeyColumn() != null) {
                predicates.add(new SqlExpression.Comparison(
                        new SqlExpression.Column(targetAlias, relationStorage.targetKeyColumn()),
                        SqlComparisonOperator.EQUAL,
                        new SqlExpression.Column(sourceAlias, relationStorage.sourceKeyColumn())));
            }
            if (metricWindow.eventTimeField() != null) {
                if (storage.eventTimeColumn() == null) {
                    throw mappingMissing("metricEventTimeColumn");
                }
                if (metricWindow.resolvedWindow() instanceof ResolvedWindow.SelectedEvent
                        || metricWindow.resolvedWindow() instanceof ResolvedWindow.RelativeToEvent) {
                    addEventWindowPredicates(
                            metricWindow, dependency, relationStorage, targetStorage,
                            targetAlias, storage.eventTimeColumn(), sourceAlias, predicates);
                } else {
                    predicates.add(windowPredicate(
                            metricWindow, targetAlias, storage.eventTimeColumn(), sourceAlias, entity, version));
                }
            }
            for (MetadataFilterEntry entry : dependency.filter().entries()) {
                SqlIdentifier filterColumn = storage.filterColumns().get(entry.key());
                if (filterColumn == null) {
                    throw mappingMissing("metricFilterField");
                }
                predicates.add(new SqlExpression.InList(
                        new SqlExpression.Column(targetAlias, filterColumn),
                        textParameters(entry.values()),
                        false));
            }
            for (CanonicalMetricFilter filter : metric.scopeFilters()) {
                predicates.add(compileMetricScopeFilter(filter, targetAlias, targetVersion));
            }
            addSingleCurrencyMetricPredicate(definition, metric, storage, targetAlias, predicates);

            SqlExpression existence = compileCountExistence(
                    definition, metric, targetStorage, targetAlias, predicates);
            if (existence != null) {
                return existence;
            }

            SqlExpression aggregate = compileAggregate(
                    definition, dependency, storage, targetStorage, targetAlias);
            aggregate = applyEmptySet(aggregate, definition);

            SqlSelect subquery = new SqlSelect(
                    aggregate, targetStorage.table(), targetAlias, new SqlExpression.And(List.copyOf(predicates)));
            return metricComparison(new SqlExpression.ScalarSubquery(subquery), definition, metric);
        }

        private void addEventWindowPredicates(
                ResolvedWindowBinding.MetricWindow window,
                ResolvedDependency.MetricDependency metric,
                StorageCatalog.RelationStorage relationStorage,
                StorageCatalog.EntityStorage targetStorage,
                SqlIdentifier targetAlias,
                SqlIdentifier eventTimeColumn,
                SqlIdentifier sourceAlias,
                List<SqlExpression> predicates) {
            com.company.campaign.metadata.domain.EventSelectorDefinition selector = metric.eventSelector();
            if (selector == null) {
                throw planMismatch("eventSelectorDependency");
            }
            com.company.campaign.metadata.domain.EventSelectorBinding binding =
                    selector.bindingFor(metric.relationId());
            if (binding == null || binding.orderBy().isEmpty()) {
                throw planMismatch("eventSelectorBinding");
            }
            List<CanonicalMetricFilter> selectorFilters;
            if (window.resolvedWindow() instanceof ResolvedWindow.SelectedEvent selected) {
                selectorFilters = selected.selectorScopeFilters();
                predicates.add(new SqlExpression.Comparison(
                        selectorColumn(targetAlias, targetStorage, binding.identityAttributeId()),
                        SqlComparisonOperator.EQUAL,
                        selectorValue(binding, binding.identityAttributeId(), selectorFilters,
                                relationStorage, targetStorage, sourceAlias)));
                return;
            }
            ResolvedWindow.RelativeToEvent relative = (ResolvedWindow.RelativeToEvent) window.resolvedWindow();
            selectorFilters = relative.selectorScopeFilters();
            String anchorTimeAttributeId = binding.orderBy().getFirst().attributeId();
            ResolvedDependency.AttributeDependency anchorTime = attributeDependency(anchorTimeAttributeId);
            if (anchorTime.definition().dataType() != SemanticDataType.DATE
                    && anchorTime.definition().dataType() != SemanticDataType.TIMESTAMP) {
                throw planMismatch("eventSelectorTimeOrder");
            }
            SqlExpression eventTime = new SqlExpression.Column(targetAlias, eventTimeColumn);
            predicates.add(new SqlExpression.Comparison(
                    eventTime,
                    window.includeStart() ? SqlComparisonOperator.GREATER_OR_EQUAL
                            : SqlComparisonOperator.GREATER_THAN,
                    addDays(selectorValue(binding, anchorTimeAttributeId, selectorFilters,
                            relationStorage, targetStorage, sourceAlias), relative.startOffsetDays())));
            predicates.add(new SqlExpression.Comparison(
                    eventTime,
                    window.includeEnd() ? SqlComparisonOperator.LESS_OR_EQUAL
                            : SqlComparisonOperator.LESS_THAN,
                    addDays(selectorValue(binding, anchorTimeAttributeId, selectorFilters,
                            relationStorage, targetStorage, sourceAlias), relative.endOffsetDays())));
            if (relative.excludeAnchorEvent()) {
                predicates.add(new SqlExpression.Comparison(
                        selectorColumn(targetAlias, targetStorage, binding.identityAttributeId()),
                        SqlComparisonOperator.NOT_EQUAL,
                        selectorValue(binding, binding.identityAttributeId(), selectorFilters,
                                relationStorage, targetStorage, sourceAlias)));
            }
        }

        private SqlExpression selectorValue(
                com.company.campaign.metadata.domain.EventSelectorBinding binding,
                String projectedAttributeId,
                List<CanonicalMetricFilter> selectorFilters,
                StorageCatalog.RelationStorage relationStorage,
                StorageCatalog.EntityStorage targetStorage,
                SqlIdentifier sourceAlias) {
            SqlIdentifier selectorAlias = nextAlias("s");
            List<SqlExpression> predicates = new ArrayList<>(
                    contextPredicates(selectorAlias, targetStorage.context(), "eventSelectorContext"));
            if (relationStorage.sourceKeyColumn() != null) {
                predicates.add(new SqlExpression.Comparison(
                        new SqlExpression.Column(selectorAlias, relationStorage.targetKeyColumn()),
                        SqlComparisonOperator.EQUAL,
                        new SqlExpression.Column(sourceAlias, relationStorage.sourceKeyColumn())));
            }
            for (com.company.campaign.metadata.domain.MetadataFilterEntry entry
                    : binding.fixedFilter().entries()) {
                predicates.add(new SqlExpression.InList(
                        selectorColumn(selectorAlias, targetStorage, entry.key()),
                        textParameters(entry.values()), false));
            }
            for (CanonicalMetricFilter filter : selectorFilters) {
                predicates.add(compileMetricScopeFilter(
                        filter, selectorAlias, targetStorage.entityVersion()));
            }
            List<SqlSelect.OrderBy> orderBy = new ArrayList<>(binding.orderBy().size());
            for (com.company.campaign.metadata.domain.EventSelectorOrder order : binding.orderBy()) {
                orderBy.add(new SqlSelect.OrderBy(
                        selectorColumn(selectorAlias, targetStorage, order.attributeId()), order.direction()));
            }
            SqlSelect select = new SqlSelect(
                    selectorColumn(selectorAlias, targetStorage, projectedAttributeId),
                    targetStorage.table(), selectorAlias, new SqlExpression.And(List.copyOf(predicates)),
                    orderBy, 1);
            return new SqlExpression.ScalarSubquery(select);
        }

        private SqlExpression selectorColumn(
                SqlIdentifier alias,
                StorageCatalog.EntityStorage targetStorage,
                String attributeId) {
            ResolvedDependency.AttributeDependency dependency = attributeDependency(attributeId);
            if (!dependency.entityVersion().equals(targetStorage.entityVersion())) {
                throw planMismatch("eventSelectorAttribute");
            }
            StorageCatalog.AttributeStorage storage = catalog.attribute(
                    attributeId,
                    targetStorage.entityVersion().entity(),
                    targetStorage.entityVersion().schemaVersion());
            if (storage == null) {
                throw mappingMissing("eventSelectorAttribute");
            }
            return new SqlExpression.Column(alias, storage.valueColumn());
        }

        private SqlExpression compileCountExistence(
                CanonicalMetricDefinition definition,
                QueryAst.Metric metric,
                StorageCatalog.EntityStorage targetStorage,
                SqlIdentifier targetAlias,
                List<SqlExpression> predicates) {
            if (definition.aggregation() != AggregateFunction.COUNT
                    || !(metric.operand() instanceof CanonicalValue.IntegerValue integer)) {
                return null;
            }
            Boolean mustExist = switch (metric.operator()) {
                case GREATER_THAN, NOT_EQUAL -> integer.value() == 0 ? Boolean.TRUE : null;
                case GREATER_OR_EQUAL -> integer.value() == 1 ? Boolean.TRUE : null;
                case EQUAL, LESS_OR_EQUAL -> integer.value() == 0 ? Boolean.FALSE : null;
                case LESS_THAN -> integer.value() == 1 ? Boolean.FALSE : null;
                default -> null;
            };
            if (mustExist == null) {
                return null;
            }
            SqlSelect subquery = new SqlSelect(
                    new SqlExpression.Column(targetAlias, targetStorage.primaryKeyColumn()),
                    targetStorage.table(),
                    targetAlias,
                    new SqlExpression.And(List.copyOf(predicates)));
            SqlExpression exists = new SqlExpression.Exists(subquery);
            return mustExist ? exists : new SqlExpression.Not(exists);
        }

        private SqlExpression compileMetricScopeFilter(
                CanonicalMetricFilter filter,
                SqlIdentifier targetAlias,
                EntityVersionRef targetVersion) {
            return compileAttribute(
                    new QueryAst.Attribute(filter.definition(), filter.operator(), filter.value()),
                    targetAlias,
                    targetVersion.entity(),
                    targetVersion);
        }

        private void addSingleCurrencyMetricPredicate(
                CanonicalMetricDefinition definition,
                QueryAst.Metric metric,
                StorageCatalog.MetricStorage storage,
                SqlIdentifier targetAlias,
                List<SqlExpression> predicates) {
            addSingleCurrencyMetricPredicate(
                    definition, metric.operand(), storage, targetAlias, predicates);
        }

        private void addSingleCurrencyMetricPredicate(
                CanonicalMetricDefinition definition,
                CanonicalValue operand,
                StorageCatalog.MetricStorage storage,
                SqlIdentifier targetAlias,
                List<SqlExpression> predicates) {
            if (definition.resultType() != SemanticDataType.MONEY) {
                return;
            }
            CurrencyBinding binding = currencyBinding(MetadataKind.METRIC, definition.id());
            if (binding.action() != CurrencyBinding.Action.SINGLE_CURRENCY_ONLY) {
                return;
            }
            if (storage.currencyColumn() == null) {
                throw unsupported("metricCurrencyColumn");
            }
            if (!(operand instanceof CanonicalValue.MoneyValue money)) {
                throw planMismatch("moneyOperand");
            }
            predicates.add(new SqlExpression.Comparison(
                    new SqlExpression.Column(targetAlias, storage.currencyColumn()),
                    SqlComparisonOperator.EQUAL,
                    parameter(SqlParameter.currency(money.currency()))));
        }

        /**
         * Builds one correlated scalar aggregate. Non-existence-equivalent {@code COUNT} predicates become
         * {@code COUNT(*)} over the correlated subquery and are never lowered to an inner join; every other
         * aggregate reads the pinned input column, applies the exact refund treatment and then the exact
         * currency conversion.
         */
        private SqlExpression compileAggregate(
                CanonicalMetricDefinition definition,
                ResolvedDependency.MetricDependency dependency,
                StorageCatalog.MetricStorage storage,
                StorageCatalog.EntityStorage targetStorage,
                SqlIdentifier targetAlias) {
            AggregateFunction aggregation = definition.aggregation();
            if (aggregation == AggregateFunction.COUNT) {
                return new SqlExpression.Aggregate(AggregateFunction.COUNT, false, null);
            }
            if (storage.inputColumn() == null) {
                throw mappingMissing("metricInputColumn");
            }
            SqlExpression input = new SqlExpression.Column(targetAlias, storage.inputColumn());
            if (dependency.refundTreatment() == RefundTreatment.SUBTRACT) {
                input = refundAdjusted(input, targetAlias, targetStorage, storage);
            }
            if (definition.resultType() == SemanticDataType.MONEY) {
                input = convertedInput(input, targetAlias, storage);
            }
            return new SqlExpression.Aggregate(
                    aggregation, aggregation == AggregateFunction.COUNT_DISTINCT, input);
        }

        private SqlExpression refundAdjusted(
                SqlExpression input,
                SqlIdentifier targetAlias,
                StorageCatalog.EntityStorage targetStorage,
                StorageCatalog.MetricStorage storage) {
            StorageCatalog.RefundStorage refund = catalog.refundStorage();
            if (!storage.refundSupported() || refund == null) {
                throw unsupported("metricRefundTreatment");
            }
            SqlIdentifier refundAlias = nextAlias("g");
            List<SqlExpression> refundPredicates = new ArrayList<>(
                    contextPredicates(refundAlias, refund.context(), "refundContext"));
            refundPredicates.add(new SqlExpression.Comparison(
                    new SqlExpression.Column(refundAlias, refund.orderIdColumn()),
                    SqlComparisonOperator.EQUAL,
                    new SqlExpression.Column(targetAlias, targetStorage.primaryKeyColumn())));
            SqlExpression refundSum = new SqlExpression.ScalarSubquery(new SqlSelect(
                    new SqlExpression.Aggregate(
                            AggregateFunction.SUM, false, new SqlExpression.Column(refundAlias, refund.amountColumn())),
                    refund.table(),
                    refundAlias,
                    new SqlExpression.And(List.copyOf(refundPredicates))));
            return new SqlExpression.Subtract(
                    input,
                    new SqlExpression.Coalesce(
                            List.of(refundSum, parameter(SqlParameter.decimal(BigDecimal.ZERO)))));
        }

        private SqlExpression convertedInput(
                SqlExpression input, SqlIdentifier targetAlias, StorageCatalog.MetricStorage storage) {
            CurrencyBinding binding = currencyBinding(MetadataKind.METRIC, storage.definitionId());
            return switch (binding.action()) {
                case CONVERT_TO_REPORTING_CURRENCY -> {
                    if (storage.currencyColumn() == null) {
                        throw unsupported("metricCurrencyColumn");
                    }
                    requireFxConversion();
                    yield new SqlExpression.Multiply(
                            input, rateExpression(new SqlExpression.Column(targetAlias, storage.currencyColumn())));
                }
                case SINGLE_CURRENCY_ONLY -> input;
                default -> throw unsupported("metricCurrencyAction");
            };
        }

        /**
         * Applies the declared empty-set behavior exactly: {@code COUNT}/{@code COUNT_DISTINCT} already yield
         * {@code 0} over no rows, an explicit {@code ZERO} wraps the aggregate in {@code COALESCE} with the
         * pinned zero value as a parameter, and {@code NULL}/{@code UNDEFINED} stay bare so a comparison against
         * them is unknown and never a fabricated number.
         */
        private SqlExpression applyEmptySet(SqlExpression aggregate, CanonicalMetricDefinition definition) {
            AggregateFunction aggregation = definition.aggregation();
            if (aggregation == AggregateFunction.COUNT || aggregation == AggregateFunction.COUNT_DISTINCT) {
                if (definition.emptySetBehavior() != EmptySetBehavior.ZERO) {
                    throw unsupported("metricEmptySet");
                }
                return aggregate;
            }
            return switch (definition.emptySetBehavior()) {
                case ZERO -> new SqlExpression.Coalesce(List.of(aggregate, zeroParameter(definition)));
                case NULL, UNDEFINED -> aggregate;
            };
        }

        private SqlExpression zeroParameter(CanonicalMetricDefinition definition) {
            MetadataScalarValue value = definition.emptySetValue();
            if (value == null) {
                throw unsupported("metricEmptySetValue");
            }
            return switch (value.dataType()) {
                case INTEGER -> {
                    try {
                        yield parameter(SqlParameter.integer(Long.parseLong(value.canonicalValue())));
                    } catch (NumberFormatException exception) {
                        throw unsupported("metricEmptySetValue");
                    }
                }
                case DECIMAL, MONEY ->
                        parameter(SqlParameter.decimal(new BigDecimal(value.canonicalValue())));
                default -> throw unsupported("metricEmptySetValue");
            };
        }

        private SqlExpression metricComparison(
                SqlExpression aggregate, CanonicalMetricDefinition definition, QueryAst.Metric metric) {
            ComparisonOperator operator = metric.operator();
            if (operator.isNullOperator()) {
                if (operator == ComparisonOperator.IS_NULL
                        && definition.emptySetBehavior() != EmptySetBehavior.ZERO) {
                    // A NULL empty-set aggregate is unknown, not NULL, so an explicit IS_NULL would differ.
                    throw unsupported("metricNullPredicate");
                }
                return operator == ComparisonOperator.IS_NULL
                        ? new SqlExpression.IsNull(aggregate)
                        : new SqlExpression.IsNotNull(aggregate);
            }
            if (definition.resultType() != SemanticDataType.MONEY) {
                return plainComparison(aggregate, operator, metric.operand());
            }
            if (operator == ComparisonOperator.IN || operator == ComparisonOperator.NOT_IN
                    || operator == ComparisonOperator.BETWEEN) {
                throw unsupported("monetaryOperator");
            }
            if (!(metric.operand() instanceof CanonicalValue.MoneyValue money)) {
                throw planMismatch("moneyOperand");
            }
            CurrencyBinding binding = currencyBinding(MetadataKind.METRIC, definition.id());
            SqlExpression right = switch (binding.action()) {
                case CONVERT_TO_REPORTING_CURRENCY -> {
                    requireFxConversion();
                    yield new SqlExpression.Multiply(
                            parameter(SqlParameter.decimal(new BigDecimal(money.amount()))),
                            rateExpression(parameter(SqlParameter.currency(money.currency()))));
                }
                case SINGLE_CURRENCY_ONLY ->
                        parameter(SqlParameter.decimal(new BigDecimal(money.amount())));
                default -> throw unsupported("metricCurrencyAction");
            };
            return new SqlExpression.Comparison(aggregate, sqlOperator(operator), right);
        }

        private void requireSupportedMetricResult(SemanticDataType resultType) {
            if (resultType != SemanticDataType.INTEGER
                    && resultType != SemanticDataType.DECIMAL
                    && resultType != SemanticDataType.MONEY) {
                throw unsupported("metricResultType");
            }
        }

        // ------------------------------------------------------------------ relations

        private SqlExpression compileExists(
                QueryAst.Exists exists,
                SqlIdentifier sourceAlias,
                SubjectKind entity,
                EntityVersionRef version,
                String path,
                int depth) {
            CanonicalRelationReference reference = exists.relation();
            ResolvedDependency.RelationDependency dependency = relationDependency(reference.id());
            if (!dependency.reference().equals(reference)) {
                throw planMismatch("relationDependency");
            }
            if (reference.sourceEntity() != entity) {
                throw unsupported("existsSourceEntity");
            }
            StorageCatalog.RelationStorage storage = relationStorage(dependency);
            if (storage.joinKind() == RelationJoinKind.TARGET_FILTERED) {
                throw unsupported("relationJoinKind");
            }
            if (!Objects.equals(storage.sourceJoinField(), reference.sourceJoinField())
                    || !Objects.equals(storage.targetJoinField(), reference.targetJoinField())) {
                throw mappingMissing("relationJoinField");
            }

            ResolvedWindowBinding window = windowBinding(path, ResolvedWindowBinding.WindowScope.RELATION);
            if (!(window instanceof ResolvedWindowBinding.RelationWindow relationWindow)) {
                throw planMismatch("relationWindow");
            }

            EntityVersionRef targetVersion = dependency.targetVersion();
            requireDepth(depth + 1);
            StorageCatalog.EntityStorage targetStorage = catalog.entity(targetVersion);
            if (targetStorage == null) {
                throw mappingMissing("targetEntity");
            }
            SqlIdentifier targetAlias = nextAlias("r");

            List<SqlExpression> predicates = new ArrayList<>(
                    contextPredicates(targetAlias, targetStorage.context(), "relationContext"));
            if (reference.sourceJoinField() != null) {
                predicates.add(new SqlExpression.Comparison(
                        new SqlExpression.Column(targetAlias, storage.targetKeyColumn()),
                        SqlComparisonOperator.EQUAL,
                        new SqlExpression.Column(sourceAlias, storage.sourceKeyColumn())));
            }
            if (relationWindow.eventTimeField() != null) {
                if (storage.targetEventTimeColumn() == null) {
                    throw mappingMissing("relationEventTimeColumn");
                }
                if (!relationWindow.eventTimeField().equals(storage.targetEventTimeField())) {
                    throw mappingMissing("relationEventTimeField");
                }
                predicates.add(windowPredicate(
                        relationWindow, targetAlias, storage.targetEventTimeColumn(), sourceAlias, entity, version));
            }
            predicates.add(compileNode(
                    exists.child(), targetAlias, reference.targetEntity(), targetVersion,
                    path + EXISTS_STEP, depth + 1));

            SqlSelect subquery = new SqlSelect(
                    new SqlExpression.Column(targetAlias, targetStorage.primaryKeyColumn()),
                    targetStorage.table(),
                    targetAlias,
                    new SqlExpression.And(List.copyOf(predicates)));
            return new SqlExpression.Exists(subquery);
        }

        // ------------------------------------------------------------------ audience references

        /**
         * Lowers one Existing Audience Reference leaf to a parameterized correlated membership {@code EXISTS}.
         *
         * <p>The statement binds the exact immutable version identity <em>and</em> its content hash as separate
         * parameters against a server-owned, allowlisted table, so a lookup can only ever answer for the one
         * frozen version the reference names. There is no {@code current} or {@code latest} form to emit: the
         * AST leaf has no mutable pointer field to lower, and nothing here consults one.
         *
         * <p>Every physical name comes from the catalog binding, every value is a counted bind parameter, and
         * the row correlation is a column-to-column comparison against the enclosing entity's own primary key.
         * No value is ever interpolated into the statement text and no identifier is ever derived from a
         * payload, so a reference cannot widen, drop or inject anything into the emitted SQL.
         *
         * <p>A catalog that declares no audience membership binding fails closed rather than compiling an
         * unguarded membership test.
         */
        private SqlExpression compileAudienceReference(
                QueryAst.AudienceReference reference,
                SqlIdentifier alias,
                SubjectKind entity,
                EntityVersionRef version,
                int depth) {
            AudienceVersionDependency dependency = audienceDependency(reference.reference());
            if (entity != SubjectKind.MEMBER || dependency.subject() != entity) {
                // The membership table is keyed by member identity, so a reference outside the member subject
                // has no exact allowlisted lowering rather than an approximated one.
                throw unsupported("audienceReferenceSubject");
            }
            StorageCatalog.AudienceMembershipStorage storage = catalog.audienceMembership();
            if (storage == null) {
                throw mappingMissing("audienceMembershipStorage");
            }
            StorageCatalog.EntityStorage current = catalog.entity(version);
            if (current == null) {
                throw mappingMissing("audienceReferenceEntity");
            }
            requireDepth(depth + 1);

            SqlIdentifier audienceAlias = nextAlias("a");
            List<SqlExpression> predicates = new ArrayList<>(
                    contextPredicates(audienceAlias, storage.context(), "audienceMembershipContext"));
            predicates.add(new SqlExpression.Comparison(
                    new SqlExpression.Column(audienceAlias, storage.audienceIdColumn()),
                    SqlComparisonOperator.EQUAL,
                    parameter(SqlParameter.contextId(dependency.reference().audienceId()))));
            predicates.add(new SqlExpression.Comparison(
                    new SqlExpression.Column(audienceAlias, storage.versionIdColumn()),
                    SqlComparisonOperator.EQUAL,
                    parameter(SqlParameter.contextId(dependency.reference().versionId()))));
            predicates.add(new SqlExpression.Comparison(
                    new SqlExpression.Column(audienceAlias, storage.versionHashColumn()),
                    SqlComparisonOperator.EQUAL,
                    parameter(SqlParameter.text(dependency.reference().versionHash().value()))));
            predicates.add(new SqlExpression.Comparison(
                    new SqlExpression.Column(audienceAlias, storage.memberIdColumn()),
                    SqlComparisonOperator.EQUAL,
                    new SqlExpression.Column(alias, current.primaryKeyColumn())));

            SqlSelect subquery = new SqlSelect(
                    new SqlExpression.Column(audienceAlias, storage.memberIdColumn()),
                    storage.table(),
                    audienceAlias,
                    new SqlExpression.And(List.copyOf(predicates)));
            return new SqlExpression.Exists(subquery);
        }

        /** The plan's frozen dependency for one exact pin; a reference without one is contradictory material. */
        private AudienceVersionDependency audienceDependency(CanonicalAudienceVersionRef pin) {
            AudienceVersionDependency dependency = audienceByPin.get(
                    pin.audienceId() + "/" + pin.versionId() + "@" + pin.versionHash().value());
            if (dependency == null || !dependency.reference().versionHash().equals(pin.versionHash())) {
                throw planMismatch("audienceDependency");
            }
            return dependency;
        }

        private StorageCatalog.RelationStorage relationStorage(
                ResolvedDependency.RelationDependency dependency) {
            StorageCatalog.RelationStorage storage = catalog.relation(
                    dependency.reference().id(),
                    dependency.sourceVersion().entity(), dependency.sourceVersion().schemaVersion(),
                    dependency.targetVersion().entity(), dependency.targetVersion().schemaVersion());
            if (storage == null) {
                throw mappingMissing("relationStorage");
            }
            if (storage.joinKind() != dependency.reference().joinKind()) {
                throw mappingMissing("relationJoinKind");
            }
            return storage;
        }

        // ------------------------------------------------------------------ windows

        private ResolvedWindowBinding windowBinding(String path, ResolvedWindowBinding.WindowScope scope) {
            ResolvedWindowBinding binding = windowsByPath.get(path);
            if (binding == null || binding.scope() != scope) {
                throw planMismatch("windowBinding");
            }
            return binding;
        }

        private SqlExpression windowPredicate(
                ResolvedWindowBinding window,
                SqlIdentifier alias,
                SqlIdentifier column,
                SqlIdentifier sourceAlias,
                SubjectKind sourceEntity,
                EntityVersionRef sourceVersion) {
            SqlExpression eventTime = new SqlExpression.Column(alias, column);
            ResolvedWindow resolved = window.resolvedWindow();
            if (resolved instanceof ResolvedWindow.Lifetime lifetime) {
                return new SqlExpression.Comparison(
                        eventTime, SqlComparisonOperator.LESS_THAN,
                        parameter(SqlParameter.timestamp(lifetime.dataWatermark())));
            }
            if (resolved instanceof ResolvedWindow.Rolling rolling) {
                return boundedWindow(eventTime, rolling.start(), rolling.end(), window);
            }
            if (resolved instanceof ResolvedWindow.Fixed fixed) {
                return boundedWindow(eventTime, fixed.start(), fixed.end(), window);
            }
            if (resolved instanceof ResolvedWindow.RelativeToAttribute relative) {
                ResolvedDependency.AttributeDependency dependency =
                        attributeDependency(relative.anchorDefinitionId());
                if (dependency.definition().subject() != sourceEntity
                        || !dependency.entityVersion().equals(sourceVersion)
                        || (dependency.definition().dataType() != SemanticDataType.DATE
                        && dependency.definition().dataType() != SemanticDataType.TIMESTAMP)) {
                    throw planMismatch("relativeWindowAnchor");
                }
                StorageCatalog.AttributeStorage anchorStorage = catalog.attribute(
                        relative.anchorDefinitionId(), sourceEntity, sourceVersion.schemaVersion());
                if (anchorStorage == null
                        || (anchorStorage.dataType() != SemanticDataType.DATE
                        && anchorStorage.dataType() != SemanticDataType.TIMESTAMP)) {
                    throw mappingMissing("relativeWindowAnchor");
                }
                SqlExpression anchor = new SqlExpression.Column(sourceAlias, anchorStorage.valueColumn());
                return new SqlExpression.And(List.of(
                        new SqlExpression.Comparison(
                                eventTime,
                                window.includeStart() ? SqlComparisonOperator.GREATER_OR_EQUAL
                                        : SqlComparisonOperator.GREATER_THAN,
                                addDays(anchor, relative.startOffsetDays())),
                        new SqlExpression.Comparison(
                                eventTime,
                                window.includeEnd() ? SqlComparisonOperator.LESS_OR_EQUAL
                                        : SqlComparisonOperator.LESS_THAN,
                                addDays(anchor, relative.endOffsetDays()))));
            }
            throw planMismatch("windowKind");
        }

        private SqlExpression addDays(SqlExpression anchor, long days) {
            return new SqlExpression.AddDays(
                    anchor, parameter(SqlParameter.integer(days)));
        }

        private SqlExpression boundedWindow(
                SqlExpression eventTime, Instant start, Instant end, ResolvedWindowBinding window) {
            return new SqlExpression.And(List.of(
                    new SqlExpression.Comparison(
                            eventTime,
                            window.includeStart() ? SqlComparisonOperator.GREATER_OR_EQUAL
                                    : SqlComparisonOperator.GREATER_THAN,
                            parameter(SqlParameter.timestamp(start))),
                    new SqlExpression.Comparison(
                            eventTime,
                            window.includeEnd() ? SqlComparisonOperator.LESS_OR_EQUAL
                                    : SqlComparisonOperator.LESS_THAN,
                            parameter(SqlParameter.timestamp(end)))));
        }

        // ------------------------------------------------------------------ currency

        private void requireFxConversion() {
            if (catalog.fxStrategy() == null) {
                throw unsupported("fxStrategy");
            }
            if (fxRateBinding == null) {
                throw new SqlException(SqlErrorCodes.SQL_FX_INVALID, "fxBinding",
                        "A currency conversion requires an explicit immutable FX-rate binding.");
            }
            if (!fxRateBinding.targetCurrency().equals(context.reportingCurrency())) {
                throw new SqlException(SqlErrorCodes.SQL_FX_INVALID, "fxTargetCurrency",
                        "The FX-rate binding does not target the trusted reporting currency.");
            }
            if (fxRateBinding.rateTimestamp().isAfter(context.asOf())) {
                throw new SqlException(SqlErrorCodes.SQL_FX_INVALID, "fxRateTimestamp",
                        "The FX-rate binding's rate timestamp is after the trusted evaluation instant.");
            }
            Set<String> declared = Set.copyOf(catalog.fxStrategy().supportedSourceCurrencies());
            if (!declared.equals(fxRateBinding.sourceCurrencies())) {
                throw new SqlException(SqlErrorCodes.SQL_FX_INVALID, "fxRateCoverage",
                        "The FX-rate binding does not exactly cover the catalog's pinned currency vocabulary.");
            }
            if (fxRateBinding.sourceCurrencies().contains(fxRateBinding.targetCurrency())) {
                // The target currency has exactly one exact rate — the identity arm — so an explicit rate for
                // it would make the lowered CASE ambiguous.
                throw new SqlException(SqlErrorCodes.SQL_FX_INVALID, "fxTargetRate",
                        "The FX-rate binding declares an explicit rate for the reporting currency.");
            }
            fxRequired = true;
        }

        /**
         * The one exact rate lowering: a compiler-owned searched {@code CASE} over the source currency.
         *
         * <p>Its arms are the immutable binding's rates in deterministic source-currency order plus the exact
         * reporting-currency identity arm, and every currency and rate is a typed bind parameter, so the
         * conversion is a pure function of the pinned binding. No rate table is read at all: a mutable or
         * contradictory database rate can never change which members match while the compiled hash stays the
         * same. A source currency outside the pinned vocabulary matches no arm and yields SQL {@code NULL}, so
         * an uncovered row fails the comparison rather than defaulting to a rate or to {@code 1}.
         */
        private SqlExpression rateExpression(SqlExpression currencyExpression) {
            List<FxRateBinding.Rate> rates = fxRateBinding.rates();
            List<SqlExpression> whens = new ArrayList<>(rates.size() + 1);
            List<SqlExpression> thens = new ArrayList<>(rates.size() + 1);
            for (FxRateBinding.Rate rate : rates) {
                whens.add(parameter(SqlParameter.currency(rate.sourceCurrency())));
                thens.add(parameter(SqlParameter.decimal(rate.rate())));
            }
            whens.add(parameter(SqlParameter.currency(fxRateBinding.targetCurrency())));
            thens.add(parameter(SqlParameter.decimal(BigDecimal.ONE)));
            return new SqlExpression.Case(
                    currencyExpression, List.copyOf(whens), List.copyOf(thens), null);
        }

        // ------------------------------------------------------------------ operands

        private SqlExpression scalarParameter(CanonicalValue operand) {
            return switch (operand) {
                case CanonicalValue.TextValue text -> parameter(SqlParameter.text(text.value()));
                case CanonicalValue.BooleanValue bool -> parameter(SqlParameter.booleanValue(bool.value()));
                case CanonicalValue.IntegerValue integer -> parameter(SqlParameter.integer(integer.value()));
                case CanonicalValue.DecimalValue decimal ->
                        parameter(SqlParameter.decimal(new BigDecimal(decimal.value())));
                case CanonicalValue.DateValue date -> parameter(SqlParameter.date(date.value()));
                case CanonicalValue.InstantValue instant ->
                        parameter(SqlParameter.timestamp(Instant.parse(instant.instant())));
                case CanonicalValue.EnumValue code -> parameter(SqlParameter.enumCode(code.code()));
                case CanonicalValue.MoneyValue money ->
                        parameter(SqlParameter.decimal(new BigDecimal(money.amount())));
                default -> throw planMismatch("scalarOperand");
            };
        }

        private List<SqlExpression> listParameters(CanonicalValue operand) {
            if (!(operand instanceof CanonicalValue.ListValue list)) {
                throw planMismatch("listOperand");
            }
            if (list.items().size() > policy.maxInListItems()) {
                throw limitExceeded("inListItems");
            }
            List<SqlExpression> parameters = new ArrayList<>(list.items().size());
            for (CanonicalValue item : list.items()) {
                parameters.add(scalarParameter(item));
            }
            return List.copyOf(parameters);
        }

        private List<SqlExpression> textParameters(List<String> values) {
            if (values.size() > policy.maxInListItems()) {
                throw limitExceeded("inListItems");
            }
            List<SqlExpression> parameters = new ArrayList<>(values.size());
            for (String value : values) {
                parameters.add(parameter(SqlParameter.text(value)));
            }
            return List.copyOf(parameters);
        }

        private static String text(CanonicalValue operand) {
            if (operand instanceof CanonicalValue.TextValue text) {
                return text.value();
            }
            throw planMismatch("textOperand");
        }

        /**
         * Escapes the compiler-owned LIKE escape character and then both wildcards, so a caller-supplied
         * {@code %}, {@code _} or escape character is matched as literal data rather than as a pattern.
         */
        private static String escapeLike(String value) {
            StringBuilder escaped = new StringBuilder(value.length());
            for (int index = 0; index < value.length(); index++) {
                char character = value.charAt(index);
                if (character == LIKE_ESCAPE || character == '%' || character == '_') {
                    escaped.append(LIKE_ESCAPE);
                }
                escaped.append(character);
            }
            return escaped.toString();
        }

        private void requireIdentityStringSemantics(StringSemantics semantics) {
            if (semantics == null
                    || semantics.normalization() != StringNormalization.NONE
                    || semantics.caseSensitivity() != StringCaseSensitivity.CASE_SENSITIVE) {
                // PostgreSQL cannot reproduce this build's Unicode/locale folding exactly, so fail closed
                // rather than silently returning a different member set.
                throw unsupported("stringSemantics");
            }
        }

        private static SqlComparisonOperator sqlOperator(ComparisonOperator operator) {
            return switch (operator) {
                case EQUAL -> SqlComparisonOperator.EQUAL;
                case NOT_EQUAL -> SqlComparisonOperator.NOT_EQUAL;
                case GREATER_THAN -> SqlComparisonOperator.GREATER_THAN;
                case GREATER_OR_EQUAL -> SqlComparisonOperator.GREATER_OR_EQUAL;
                case LESS_THAN -> SqlComparisonOperator.LESS_THAN;
                case LESS_OR_EQUAL -> SqlComparisonOperator.LESS_OR_EQUAL;
                default -> throw unsupported("operator");
            };
        }

        // ------------------------------------------------------------------ lookups and counters

        private ResolvedDependency.AttributeDependency attributeDependency(String definitionId) {
            ResolvedDependency.AttributeDependency dependency = attributesById.get(definitionId);
            if (dependency == null) {
                throw planMismatch("attributeDependency");
            }
            return dependency;
        }

        private ResolvedDependency.MetricDependency metricDependency(String definitionId) {
            ResolvedDependency.MetricDependency dependency = metricsById.get(definitionId);
            if (dependency == null) {
                throw planMismatch("metricDependency");
            }
            return dependency;
        }

        private ResolvedDependency.RelationDependency relationDependency(String relationId) {
            ResolvedDependency.RelationDependency dependency = relationsById.get(relationId);
            if (dependency == null) {
                throw planMismatch("relationDependency");
            }
            return dependency;
        }

        private CurrencyBinding currencyBinding(MetadataKind kind, String definitionId) {
            CurrencyBinding binding = currencyByKey.get(currencyBindingKey(kind, definitionId));
            if (binding == null) {
                throw planMismatch("currencyBinding");
            }
            return binding;
        }

        private static String currencyBindingKey(MetadataKind kind, String definitionId) {
            return kind.name() + "|" + definitionId;
        }

        private SqlIdentifier nextAlias(String prefix) {
            return SqlIdentifier.of(prefix + aliasCounter++);
        }

        private SqlExpression parameter(SqlParameter sqlParameter) {
            parameterCount++;
            if (parameterCount > policy.maxParameters()) {
                throw limitExceeded("parameters");
            }
            return new SqlExpression.BindParameter(sqlParameter);
        }

        private void requireDepth(int depth) {
            if (depth > policy.maxSubqueryDepth()) {
                throw limitExceeded("subqueryDepth");
            }
        }

        private static SqlException planMismatch(String invariant) {
            return new SqlException(SqlErrorCodes.SQL_PLAN_MISMATCH, invariant,
                    "The plan, its trusted context and its own resolved facts contradict one another.");
        }

        private static SqlException mappingMissing(String invariant) {
            return new SqlException(SqlErrorCodes.SQL_MAPPING_MISSING, invariant,
                    "A plan dependency has no exact server-owned storage catalog entry.");
        }

        private static SqlException unsupported(String invariant) {
            return new SqlException(SqlErrorCodes.SQL_UNSUPPORTED_SEMANTIC, invariant,
                    "An accepted plan combination has no exact allowlisted PostgreSQL lowering.");
        }

        private static SqlException limitExceeded(String invariant) {
            return new SqlException(SqlErrorCodes.SQL_LIMIT_EXCEEDED, invariant,
                    "The compiled statement would exceed a bounded compile-policy limit.");
        }
    }
}
