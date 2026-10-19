package com.company.campaign.segment.validation;

import com.company.campaign.metadata.MetadataKind;
import com.company.campaign.metadata.domain.AttributeDefinition;
import com.company.campaign.metadata.domain.ComparisonOperator;
import com.company.campaign.metadata.domain.CurrencySemantics;
import com.company.campaign.metadata.domain.EmptySetBehavior;
import com.company.campaign.metadata.domain.EnumValueDefinition;
import com.company.campaign.metadata.domain.EventTimeSemantics;
import com.company.campaign.metadata.domain.EventSelectorBinding;
import com.company.campaign.metadata.domain.EventSelectorDefinition;
import com.company.campaign.metadata.domain.MetadataDefinition;
import com.company.campaign.metadata.domain.MetadataSnapshot;
import com.company.campaign.metadata.domain.MetricDefinition;
import com.company.campaign.metadata.domain.MetricFilterDimension;
import com.company.campaign.metadata.domain.RelationDefinition;
import com.company.campaign.metadata.domain.SemanticDataType;
import com.company.campaign.metadata.domain.StringSemantics;
import com.company.campaign.metadata.domain.SubjectKind;
import com.company.campaign.metadata.domain.TimeWindowKind;
import com.company.campaign.segment.canonical.CanonicalNode;
import com.company.campaign.segment.canonical.CanonicalSegment;
import com.company.campaign.segment.canonical.CanonicalSegmentCodec;
import com.company.campaign.segment.canonical.CanonicalValue;
import com.company.campaign.segment.canonical.CanonicalizerVersions;
import com.company.campaign.segment.reference.AudienceVersionRef;
import com.company.campaign.segment.reference.ReferenceCandidate;
import com.company.campaign.segment.reference.ReferenceErrorCodes;
import com.company.campaign.segment.reference.ReferenceGraph;
import com.company.campaign.segment.reference.ReferenceGraphIssue;
import com.company.campaign.segment.reference.ReferenceGraphOutcome;
import com.company.campaign.segment.reference.ReferenceGraphResolver;
import com.company.campaign.segment.reference.ReferenceValidationContext;
import com.company.campaign.segment.dsl.contract.MetadataBinding;
import com.company.campaign.segment.dsl.contract.SegmentDsl;
import com.company.campaign.segment.dsl.contract.SegmentDslSchemaVersions;
import com.company.campaign.segment.dsl.codec.SegmentDslHasher;
import com.company.campaign.segment.dsl.predicate.AttributePredicate;
import com.company.campaign.segment.dsl.predicate.AudienceReferencePredicate;
import com.company.campaign.segment.dsl.predicate.ConditionValue;
import com.company.campaign.segment.dsl.predicate.ExistsPredicate;
import com.company.campaign.segment.dsl.predicate.GroupPredicate;
import com.company.campaign.segment.dsl.predicate.MetricPredicate;
import com.company.campaign.segment.dsl.predicate.MetricExpressionPredicate;
import com.company.campaign.segment.dsl.predicate.MetricScopeFilter;
import com.company.campaign.segment.dsl.predicate.NotPredicate;
import com.company.campaign.segment.dsl.predicate.Predicate;
import com.company.campaign.segment.dsl.predicate.PredicateType;
import com.company.campaign.segment.dsl.predicate.TypedValueCondition;
import com.company.campaign.segment.dsl.value.EnumValue;
import com.company.campaign.segment.dsl.value.ListValue;
import com.company.campaign.segment.dsl.value.MoneyValue;
import com.company.campaign.segment.dsl.value.RangeValue;
import com.company.campaign.segment.dsl.value.ScalarValue;
import com.company.campaign.segment.dsl.value.TypedValue;
import com.company.campaign.segment.dsl.value.ValueKind;
import com.company.campaign.segment.dsl.window.FixedWindow;
import com.company.campaign.segment.dsl.window.LifetimeWindow;
import com.company.campaign.segment.dsl.window.RollingWindow;
import com.company.campaign.segment.dsl.window.RelativeToAttributeWindow;
import com.company.campaign.segment.dsl.window.TimeWindow;
import com.company.campaign.segment.dsl.metric.MetricExpression;
import com.company.campaign.segment.canonical.CanonicalMetricExpressionType;
import com.company.campaign.security.authorization.AuthorizationDecision;
import com.company.campaign.security.authorization.AuthorizationRequest;
import com.company.campaign.security.authorization.AuthorizationService;
import com.company.campaign.security.context.TrustedExecutionContext;
import com.company.campaign.shared.canonical.ContentHash;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The deterministic Segment DSL validator and canonicalizer.
 *
 * <p>It runs the v1.3 pipeline exactly once per call, in order — Schema, Metadata binding, definition/type/
 * operator, time/currency/NULL/aggregate, relation scope, authorization, complexity — and returns one
 * immutable {@link SegmentValidationResult}. The stage order is observable in the issue ordering.
 *
 * <p>Every fact it needs is an explicit input: an already parsed {@link SegmentDsl}, the exact
 * {@link MetadataSnapshot} it claims to bind, a host-created {@link TrustedExecutionContext}, the injected
 * fail-closed {@link AuthorizationService}, and an immutable {@link SegmentValidationPolicy}. Tenant,
 * program, permissions, DataScope, ownership and authorization flags are never read from the document.
 *
 * <p>The boundary is null-hostile and fail-closed. A missing document, snapshot, context or policy produces a
 * stable blocking issue rather than a runtime exception; a missing authorization service denies every
 * definition. The canonical AST and its hash are returned only when no blocking issue exists, so a partial
 * AST is never published as a success.
 */
public final class SegmentValidator {

    private static final CanonicalSegmentCodec CODEC = new CanonicalSegmentCodec();

    private final AuthorizationService authorizationService;

    /** Creates a validator backed by the supplied T02 boundary; a null service denies everything. */
    public SegmentValidator(AuthorizationService authorizationService) {
        this.authorizationService = authorizationService;
    }

    /**
     * Validates one document and returns its ordered issues plus, on success, its canonical semantic AST.
     *
     * <p>This is the accepted non-reference entry point and its behaviour is unchanged. A document that
     * contains an Existing Audience Reference is validated through
     * {@link #validate(SegmentDsl, MetadataSnapshot, TrustedExecutionContext, SegmentValidationPolicy,
     * ReferenceValidationContext)} instead: without the trusted reference context there is no way to resolve
     * the exact version, so this overload fails such a document closed rather than assuming anything about it.
     *
     * @param document an already parsed, structurally valid T05 document; null yields a stable blocking issue
     * @param snapshot the exact immutable snapshot the document claims to bind; null fails closed
     * @param context the host-created trusted execution context; null fails closed
     * @param policy the immutable complexity policy; null fails closed
     */
    public SegmentValidationResult validate(
            SegmentDsl document,
            MetadataSnapshot snapshot,
            TrustedExecutionContext context,
            SegmentValidationPolicy policy) {
        return new Run(document, snapshot, context, policy, null).execute();
    }

    /**
     * Validates one document that may contain Existing Audience References.
     *
     * <p>Every trusted reference fact — the exact catalog snapshot, the program relation, the effective
     * DataScope intersection and the reference policy bounds — comes from {@code referenceContext}, which only
     * the host can build. Nothing about a reference is read from the document. A document with no reference at
     * all takes exactly the same path through the pipeline as the four-argument overload, so its issues,
     * canonical AST and hash are unchanged.
     *
     * @param referenceContext the host-owned reference-resolution context, or null to fail references closed
     */
    public SegmentValidationResult validate(
            SegmentDsl document,
            MetadataSnapshot snapshot,
            TrustedExecutionContext context,
            SegmentValidationPolicy policy,
            ReferenceValidationContext referenceContext) {
        return new Run(document, snapshot, context, policy, referenceContext).execute();
    }

    /** One validation run; all mutable state is confined here so the validator itself stays thread-safe. */
    private final class Run {

        private final SegmentDsl document;
        private final MetadataSnapshot snapshot;
        private final TrustedExecutionContext context;
        private final SegmentValidationPolicy policy;
        private final ReferenceValidationContext referenceContext;

        private final IssueCollector issues = new IssueCollector();
        private final Map<String, MetadataDefinition> resolved = new LinkedHashMap<>();
        private final Set<String> inaccessibleNodes = new HashSet<>();
        private final List<ReferenceCandidate> referenceCandidates = new ArrayList<>();

        private TreeIndex index;
        private SnapshotDefinitions definitions;
        private ReferenceGraph referenceGraph;

        private Run(
                SegmentDsl document,
                MetadataSnapshot snapshot,
                TrustedExecutionContext context,
                SegmentValidationPolicy policy,
                ReferenceValidationContext referenceContext) {
            this.document = document;
            this.snapshot = snapshot;
            this.context = context;
            this.policy = policy;
            this.referenceContext = referenceContext;
        }

        SegmentValidationResult execute() {
            if (document == null) {
                issues.error(SegmentValidationStage.SCHEMA, SegmentValidationErrorCodes.INPUT_MISSING,
                        "A parsed Segment DSL document is required.", null, null, -1, Map.of());
                return rejected(null, null, null);
            }
            schemaStage();
            if (!bindingStage()) {
                return rejected(document.schemaVersion(), document.metadata(), document.editableDslHash());
            }

            index = TreeIndex.of(document.predicate());
            definitions = new SnapshotDefinitions(snapshot);
            authorizeReferencedDefinitions();
            resolve(document.predicate(), document.subject(), 0, false);
            referenceStage();
            complexityStage();

            if (!issues.isEmpty()) {
                return rejected(document.schemaVersion(), document.metadata(), document.editableDslHash());
            }
            try {
                CanonicalSegment canonical = SegmentCanonicalizer.canonicalize(document, resolved);
                ContentHash hash = CODEC.hash(canonical);
                return SegmentValidationResult.success(
                        CanonicalizerVersions.CANONICALIZER_VERSION,
                        document.schemaVersion(),
                        document.metadata(),
                        document.editableDslHash(),
                        canonical,
                        hash,
                        referenceGraph);
            } catch (RuntimeException exception) {
                // A canonicalization failure must fail closed: no partial AST and no thrown cause escape.
                issues.error(SegmentValidationStage.SCHEMA, SegmentValidationErrorCodes.CANONICALIZATION_FAILED,
                        "The validated document could not be canonicalized.", null, null, -1, Map.of());
                return rejected(document.schemaVersion(), document.metadata(), document.editableDslHash());
            }
        }

        // ---------------------------------------------------------------- stage 1: schema

        private void schemaStage() {
            if (!SegmentDslSchemaVersions.isSupported(document.schemaVersion())) {
                issues.error(SegmentValidationStage.SCHEMA, SegmentValidationErrorCodes.DSL_SCHEMA_UNSUPPORTED,
                        "The declared Segment DSL schema version is not supported.", null, null, -1, Map.of());
            }
            ContentHash recomputed = SegmentDslHasher.hash(document.hashMaterial());
            if (!recomputed.equals(document.editableDslHash())) {
                issues.error(SegmentValidationStage.SCHEMA, SegmentValidationErrorCodes.EDITABLE_HASH_MISMATCH,
                        "The declared editable hash is not the canonical hash of the editable document.",
                        null, null, -1, Map.of());
            }
        }

        // ---------------------------------------------------------------- stage 2: metadata binding

        /** @return true when the binding is proven and later stages may run. */
        private boolean bindingStage() {
            if (snapshot == null) {
                issues.error(SegmentValidationStage.METADATA_BINDING,
                        SegmentValidationErrorCodes.METADATA_SNAPSHOT_MISSING,
                        "A bound Metadata Snapshot is required.", null, null, -1, Map.of());
                return false;
            }
            MetadataBinding binding = document.metadata();
            String mismatchedPin = mismatchedPin(binding, snapshot);
            if (mismatchedPin != null) {
                issues.error(SegmentValidationStage.METADATA_BINDING,
                        SegmentValidationErrorCodes.METADATA_BINDING_MISMATCH,
                        "The Metadata binding does not match the supplied snapshot.",
                        null, null, -1, Map.of("pin", mismatchedPin));
            }
            if (context == null) {
                issues.error(SegmentValidationStage.METADATA_BINDING,
                        SegmentValidationErrorCodes.TRUSTED_CONTEXT_MISSING,
                        "A trusted execution context is required.", null, null, -1, Map.of());
                return false;
            }
            boolean ownerMatches = context.tenantId().equals(snapshot.tenantId())
                    && context.programId().equals(snapshot.programId());
            if (!ownerMatches) {
                issues.error(SegmentValidationStage.METADATA_BINDING,
                        SegmentValidationErrorCodes.METADATA_OWNER_MISMATCH,
                        "The Metadata Snapshot owner does not match the trusted execution context.",
                        null, null, -1, Map.of());
            }
            return mismatchedPin == null && ownerMatches;
        }

        private String mismatchedPin(MetadataBinding binding, MetadataSnapshot snapshot) {
            if (!binding.snapshotId().equals(snapshot.snapshotId())) {
                return "SNAPSHOT_ID";
            }
            if (!binding.snapshotVersion().equals(snapshot.snapshotVersion())) {
                return "SNAPSHOT_VERSION";
            }
            if (!binding.metadataSchemaVersion().equals(snapshot.schemaVersion())) {
                return "METADATA_SCHEMA_VERSION";
            }
            if (!binding.contentHash().equals(snapshot.contentHash())) {
                return "CONTENT_HASH";
            }
            return null;
        }

        // ---------------------------------------------------------------- authorization pre-pass

        private void authorizeReferencedDefinitions() {
            // The cache is keyed by resource kind and ID, so a metric and a relation that happened to share
            // an ID could never conflate their decisions.
            Map<String, Boolean> decisionByResource = new HashMap<>();
            for (Reference reference : collectReferences(document.predicate())) {
                MetadataDefinition definition = definitions.find(reference.definitionId());
                if (definition == null) {
                    // An unknown ID is reported by the definition stage; there is no snapshot-owned resource.
                    continue;
                }
                boolean allowed = allowed(definition, decisionByResource);
                if (allowed && definition instanceof MetricDefinition metric) {
                    // A metric's relation is an operational dependency: reaching the metric is not enough if
                    // the relation it must evaluate is denied or cannot be resolved. Authorizing it here,
                    // before any semantic fact is reported, keeps the failure non-disclosing.
                    RelationDefinition relation = relationOf(metric);
                    if (relation != null) {
                        allowed = allowed(relation, decisionByResource);
                    }
                }
                if (!allowed) {
                    inaccessibleNodes.add(reference.nodeId());
                }
            }
        }

        private boolean allowed(MetadataDefinition definition, Map<String, Boolean> decisionByResource) {
            String key = definition.kind().name() + "|" + definition.id();
            return decisionByResource.computeIfAbsent(key, ignored -> authorized(definition));
        }

        private boolean authorized(MetadataDefinition definition) {
            if (authorizationService == null) {
                return false;
            }
            try {
                AuthorizationDecision decision = authorizationService.authorize(AuthorizationRequest.of(
                        context,
                        definition.kind().resourceKind(),
                        definition.id(),
                        snapshot.tenantId(),
                        snapshot.programId()));
                return decision != null && decision.allowed();
            } catch (RuntimeException exception) {
                // Any failure of the boundary, including a thrown cause, is an unavailable decision.
                return false;
            }
        }

        /**
         * The exact relation a metric requires, or null when the bound snapshot does not hold it.
         *
         * <p>A normally constructed T04 snapshot always holds it and pins subject/event-time compatibility;
         * a snapshot that does not is rejected defensively by the metric definition stage instead.
         */
        private RelationDefinition relationOf(MetricDefinition metric) {
            MetadataDefinition found = definitions.find(metric.relationId());
            return found instanceof RelationDefinition relation ? relation : null;
        }

        private List<Reference> collectReferences(Predicate node) {
            List<Reference> references = new ArrayList<>();
            collect(node, references);
            return references;
        }

        private void collect(Predicate node, List<Reference> references) {
            switch (node) {
                case GroupPredicate group -> group.children().forEach(child -> collect(child, references));
                case NotPredicate not -> collect(not.child(), references);
                case AttributePredicate attribute ->
                        references.add(new Reference(attribute.nodeId(), MetadataKind.ATTRIBUTE, attribute.attributeId()));
                case MetricPredicate metric -> {
                        references.add(new Reference(metric.nodeId(), MetadataKind.METRIC, metric.metricId()));
                    if (metric.window() instanceof RelativeToAttributeWindow relative) {
                        references.add(new Reference(
                                relativeAnchorKey(metric.nodeId()), MetadataKind.ATTRIBUTE,
                                relative.anchorDefinitionId()));
                    }
                    for (MetricScopeFilter filter : metric.scopeFilters()) {
                        references.add(new Reference(
                                TreeIndex.scopeFilterKey(metric.nodeId(), filter.filterId()),
                                MetadataKind.ATTRIBUTE,
                                filter.attributeId()));
                    }
                    for (MetricScopeFilter filter : selectorFilters(metric.window())) {
                        references.add(new Reference(
                                TreeIndex.scopeFilterKey(metric.nodeId(), filter.filterId()),
                                MetadataKind.ATTRIBUTE,
                                filter.attributeId()));
                    }
                }
                case MetricExpressionPredicate calculation ->
                        collectMetricExpressionReferences(
                                calculation.nodeId(), calculation.expression(), "root", references);
                case ExistsPredicate exists -> {
                    references.add(new Reference(exists.nodeId(), MetadataKind.RELATION, exists.relationId()));
                    collect(exists.predicate(), references);
                }
                // A reference binds an immutable Audience Version rather than a Metadata definition, so it is
                // authorized as a SEGMENT by the reference stage instead of through the Metadata pre-pass.
                case AudienceReferencePredicate ignored -> {
                }
            }
        }

        private void collectMetricExpressionReferences(
                String nodeId,
                MetricExpression expression,
                String path,
                List<Reference> references) {
            switch (expression) {
                case MetricExpression.MetricRef reference -> references.add(new Reference(
                        TreeIndex.metricExpressionRefKey(nodeId, path),
                        MetadataKind.METRIC,
                        reference.metricId()));
                case MetricExpression.Add add -> {
                    for (int index = 0; index < add.operands().size(); index++) {
                        collectMetricExpressionReferences(
                                nodeId, add.operands().get(index), path + "." + index, references);
                    }
                }
                case MetricExpression.Subtract subtract -> {
                    collectMetricExpressionReferences(nodeId, subtract.left(), path + ".left", references);
                    collectMetricExpressionReferences(nodeId, subtract.right(), path + ".right", references);
                }
                case MetricExpression.Multiply multiply -> {
                    collectMetricExpressionReferences(nodeId, multiply.left(), path + ".left", references);
                    collectMetricExpressionReferences(nodeId, multiply.right(), path + ".right", references);
                }
                case MetricExpression.Divide divide -> {
                    collectMetricExpressionReferences(nodeId, divide.left(), path + ".left", references);
                    collectMetricExpressionReferences(nodeId, divide.right(), path + ".right", references);
                }
            }
        }

        // ------------------------------------------------------- stages 3-5: resolution of the tree

        private void resolve(Predicate node, SubjectKind scope, int relationDepth, boolean insideExists) {
            switch (node) {
                case GroupPredicate group -> {
                    group.children().forEach(child -> resolve(child, scope, relationDepth, insideExists));
                    rejectSemanticDuplicateSiblings(group);
                }
                case NotPredicate not -> resolve(not.child(), scope, relationDepth, insideExists);
                case AttributePredicate attribute -> resolveAttribute(attribute, scope);
                case MetricPredicate metric -> resolveMetric(metric, scope, insideExists);
                case MetricExpressionPredicate calculation ->
                        resolveMetricExpression(calculation, scope, insideExists);
                case ExistsPredicate exists -> resolveExists(exists, scope);
                case AudienceReferencePredicate reference -> referenceCandidates.add(new ReferenceCandidate(
                        reference.nodeId(),
                        new AudienceVersionRef(reference.audienceId(), reference.versionId(),
                                reference.versionHash()),
                        scope));
            }
        }

        // ---------------------------------------------------------------- trusted reference graph

        /**
         * Resolves the trusted reference graph and records its findings in the appended reference stage.
         *
         * <p>A document with no reference never enters this method's body, so its issues, ordering and result
         * are byte-for-byte what they were before T18. A document with a reference and no trusted context fails
         * closed with one bounded finding per site: there is no trusted fact to fall back on, and inventing one
         * would be strictly worse than refusing.
         *
         * <p>The scope subject each site is judged against comes from this traversal, never from the DSL node,
         * so a reference can never declare the subject or owner it wishes to be measured by.
         */
        private void referenceStage() {
            if (referenceCandidates.isEmpty()) {
                return;
            }
            if (referenceContext == null) {
                for (ReferenceCandidate candidate : referenceCandidates) {
                    TreeIndex.Location location = index.location(candidate.nodeId());
                    issues.error(
                            SegmentValidationStage.REFERENCE_GRAPH,
                            ReferenceErrorCodes.CONTEXT_MISSING,
                            "An Existing Audience Reference requires the host's trusted reference context.",
                            location.path(), candidate.nodeId(), location.index(), Map.of());
                }
                return;
            }
            ReferenceGraphOutcome outcome = new ReferenceGraphResolver().resolve(
                    referenceCandidates, context, authorizationService, referenceContext);
            for (ReferenceGraphIssue finding : outcome.issues()) {
                TreeIndex.Location location = index.location(finding.nodeId());
                issues.error(
                        SegmentValidationStage.REFERENCE_GRAPH,
                        finding.code(),
                        finding.message(),
                        location.path(),
                        finding.nodeId(),
                        location.index(),
                        Map.of("depth", finding.depth()));
            }
            referenceGraph = outcome.graph();
        }

        private void resolveMetricExpression(
                MetricExpressionPredicate calculation, SubjectKind scope, boolean insideExists) {
            TreeIndex.Location location = index.location(calculation.nodeId());
            if (insideExists) {
                emit(SegmentValidationStage.DEFINITION_TYPE_OPERATOR,
                        SegmentValidationErrorCodes.NESTED_METRIC_UNSUPPORTED,
                        "A metric expression nested inside an EXISTS cannot be given an unambiguous scope.",
                        location);
                return;
            }
            if (calculation.window() instanceof RelativeToAttributeWindow
                    || calculation.window() instanceof com.company.campaign.segment.dsl.window.SelectedEventWindow
                    || calculation.window() instanceof com.company.campaign.segment.dsl.window.RelativeToEventWindow) {
                emit(SegmentValidationStage.TIME_CURRENCY_NULL,
                        SegmentValidationErrorCodes.WINDOW_UNSUPPORTED,
                        "A Metric expression supports only a common rolling, fixed or lifetime window.",
                        location,
                        index.windowPath(calculation.nodeId()));
                return;
            }
            CanonicalMetricExpressionType resultType = resolveMetricExpressionNode(
                    calculation, calculation.expression(), "root", scope);
            if (resultType == null) {
                return;
            }
            if (!isMetricExpressionOperator(calculation.operator())) {
                emit(SegmentValidationStage.DEFINITION_TYPE_OPERATOR,
                        SegmentValidationErrorCodes.OPERATOR_NOT_ALLOWED,
                        "A Metric expression allows only scalar numeric comparison operators.",
                        location);
                return;
            }
            checkOperand(
                    resultType.dataType(),
                    null,
                    List.of(),
                    resultType.currencySemantics(),
                    calculation.operator(),
                    calculation.condition(),
                    calculation.nodeId(),
                    location);
        }

        private CanonicalMetricExpressionType resolveMetricExpressionNode(
                MetricExpressionPredicate calculation,
                MetricExpression expression,
                String path,
                SubjectKind scope) {
            try {
                return switch (expression) {
                    case MetricExpression.MetricRef reference -> resolveMetricExpressionReference(
                            calculation, reference, path, scope);
                    case MetricExpression.Add add -> {
                        CanonicalMetricExpressionType result = null;
                        for (int index = 0; index < add.operands().size(); index++) {
                            CanonicalMetricExpressionType operand = resolveMetricExpressionNode(
                                    calculation, add.operands().get(index), path + "." + index, scope);
                            if (operand == null) {
                                yield null;
                            }
                            result = result == null ? operand : MetricExpressionTypes.addOrSubtract(result, operand);
                        }
                        yield result;
                    }
                    case MetricExpression.Subtract subtract -> combineExpressionTypes(
                            resolveMetricExpressionNode(calculation, subtract.left(), path + ".left", scope),
                            resolveMetricExpressionNode(calculation, subtract.right(), path + ".right", scope),
                            MetricExpressionTypes::addOrSubtract);
                    case MetricExpression.Multiply multiply -> combineExpressionTypes(
                            resolveMetricExpressionNode(calculation, multiply.left(), path + ".left", scope),
                            resolveMetricExpressionNode(calculation, multiply.right(), path + ".right", scope),
                            MetricExpressionTypes::multiply);
                    case MetricExpression.Divide divide -> combineExpressionTypes(
                            resolveMetricExpressionNode(calculation, divide.left(), path + ".left", scope),
                            resolveMetricExpressionNode(calculation, divide.right(), path + ".right", scope),
                            MetricExpressionTypes::divide);
                };
            } catch (IllegalArgumentException exception) {
                TreeIndex.Location location = index.location(calculation.nodeId());
                emit(SegmentValidationStage.DEFINITION_TYPE_OPERATOR,
                        SegmentValidationErrorCodes.METRIC_EXPRESSION_TYPE_INCOMPATIBLE,
                        "A Metric expression combines incompatible semantic types, units or currency policies.",
                        location,
                        location.path() + ".expression");
                return null;
            }
        }

        private CanonicalMetricExpressionType resolveMetricExpressionReference(
                MetricExpressionPredicate calculation,
                MetricExpression.MetricRef reference,
                String path,
                SubjectKind scope) {
            String key = TreeIndex.metricExpressionRefKey(calculation.nodeId(), path);
            TreeIndex.Location location = index.location(key);
            MetadataDefinition found = definitions.find(reference.metricId());
            if (found == null) {
                emit(SegmentValidationStage.DEFINITION_TYPE_OPERATOR,
                        SegmentValidationErrorCodes.DEFINITION_UNKNOWN,
                        "A referenced Metric definition is unknown.", location);
                return null;
            }
            if (inaccessibleNodes.contains(key)) {
                emit(SegmentValidationStage.AUTHORIZATION,
                        SegmentValidationErrorCodes.RESOURCE_NOT_ACCESSIBLE,
                        "A referenced definition is not accessible.", location);
                return null;
            }
            if (!(found instanceof MetricDefinition definition)) {
                emit(SegmentValidationStage.DEFINITION_TYPE_OPERATOR,
                        SegmentValidationErrorCodes.DEFINITION_KIND_MISMATCH,
                        "A Metric expression leaf must reference a Metric.", location);
                return null;
            }
            if (definition.subject() != scope) {
                emit(SegmentValidationStage.DEFINITION_TYPE_OPERATOR,
                        SegmentValidationErrorCodes.SUBJECT_MISMATCH,
                        "The Metric expression leaf subject does not match its scope.", location);
                return null;
            }
            if (relationOf(definition) == null) {
                emit(SegmentValidationStage.DEFINITION_TYPE_OPERATOR,
                        SegmentValidationErrorCodes.METADATA_DECLARATION_INCONSISTENT,
                        "A Metric expression leaf references an unavailable relation.", location);
                return null;
            }
            checkMetricTimeAndAggregate(
                    definition,
                    calculation.window(),
                    calculation.nodeId(),
                    index.location(calculation.nodeId()));
            resolved.put(key, definition);
            return MetricExpressionTypes.reference(definition);
        }

        private CanonicalMetricExpressionType combineExpressionTypes(
                CanonicalMetricExpressionType left,
                CanonicalMetricExpressionType right,
                java.util.function.BiFunction<CanonicalMetricExpressionType,
                        CanonicalMetricExpressionType, CanonicalMetricExpressionType> operation) {
            return left == null || right == null ? null : operation.apply(left, right);
        }

        private boolean isMetricExpressionOperator(ComparisonOperator operator) {
            return operator == ComparisonOperator.EQUAL
                    || operator == ComparisonOperator.NOT_EQUAL
                    || operator == ComparisonOperator.GREATER_THAN
                    || operator == ComparisonOperator.GREATER_OR_EQUAL
                    || operator == ComparisonOperator.LESS_THAN
                    || operator == ComparisonOperator.LESS_OR_EQUAL;
        }

        private void resolveAttribute(AttributePredicate attribute, SubjectKind scope) {
            TreeIndex.Location location = index.location(attribute.nodeId());
            MetadataDefinition found = definitions.find(attribute.attributeId());
            if (found == null) {
                emit(SegmentValidationStage.DEFINITION_TYPE_OPERATOR, SegmentValidationErrorCodes.DEFINITION_UNKNOWN,
                        "A referenced attribute definition is unknown.", location);
                return;
            }
            if (inaccessibleNodes.contains(attribute.nodeId())) {
                // Authorization is decided before any semantic fact is reported, so an inaccessible
                // definition never reveals even its kind through a mismatch code.
                emit(SegmentValidationStage.AUTHORIZATION, SegmentValidationErrorCodes.RESOURCE_NOT_ACCESSIBLE,
                        "A referenced definition is not accessible.", location);
                return;
            }
            if (!(found instanceof AttributeDefinition definition)) {
                emit(SegmentValidationStage.DEFINITION_TYPE_OPERATOR,
                        SegmentValidationErrorCodes.DEFINITION_KIND_MISMATCH,
                        "A referenced definition is not an attribute.", location);
                return;
            }
            if (definition.subject() != scope) {
                emit(SegmentValidationStage.DEFINITION_TYPE_OPERATOR, SegmentValidationErrorCodes.SUBJECT_MISMATCH,
                        "The attribute subject does not match the scope in which it is referenced.", location);
                return;
            }
            if (!definition.allowedOperators().contains(attribute.operator())) {
                emit(SegmentValidationStage.DEFINITION_TYPE_OPERATOR,
                        SegmentValidationErrorCodes.OPERATOR_NOT_ALLOWED,
                        "The attribute does not allow the declared comparison operator.", location);
                return;
            }
            checkOperand(
                    definition.dataType(),
                    definition.stringSemantics(),
                    definition.enumValues(),
                    definition.currencySemantics(),
                    attribute.operator(),
                    attribute.condition(),
                    attribute.nodeId(),
                    location);
            resolved.put(attribute.nodeId(), definition);
        }

        private void resolveMetric(MetricPredicate metric, SubjectKind scope, boolean insideExists) {
            TreeIndex.Location location = index.location(metric.nodeId());
            if (insideExists) {
                emit(SegmentValidationStage.DEFINITION_TYPE_OPERATOR,
                        SegmentValidationErrorCodes.NESTED_METRIC_UNSUPPORTED,
                        "A metric nested inside an EXISTS cannot be given an unambiguous scope.", location);
                return;
            }
            MetadataDefinition found = definitions.find(metric.metricId());
            if (found == null) {
                emit(SegmentValidationStage.DEFINITION_TYPE_OPERATOR, SegmentValidationErrorCodes.DEFINITION_UNKNOWN,
                        "A referenced metric definition is unknown.", location);
                return;
            }
            if (inaccessibleNodes.contains(metric.nodeId())) {
                emit(SegmentValidationStage.AUTHORIZATION, SegmentValidationErrorCodes.RESOURCE_NOT_ACCESSIBLE,
                        "A referenced definition is not accessible.", location);
                return;
            }
            if (!(found instanceof MetricDefinition definition)) {
                emit(SegmentValidationStage.DEFINITION_TYPE_OPERATOR,
                        SegmentValidationErrorCodes.DEFINITION_KIND_MISMATCH,
                        "A referenced definition is not a metric.", location);
                return;
            }
            if (definition.subject() != scope) {
                emit(SegmentValidationStage.DEFINITION_TYPE_OPERATOR, SegmentValidationErrorCodes.SUBJECT_MISMATCH,
                        "The metric subject does not match the scope in which it is referenced.", location);
                return;
            }
            if (relationOf(definition) == null) {
                // A T04 snapshot that is normally constructed cannot reach this: the snapshot validator
                // requires every metric's relation to exist and to be subject/event-time compatible. This
                // stays as a defensive, non-disclosing semantic rejection for an inconsistent snapshot.
                emit(SegmentValidationStage.DEFINITION_TYPE_OPERATOR,
                        SegmentValidationErrorCodes.METADATA_DECLARATION_INCONSISTENT,
                        "The metric references a relation that is not part of the bound snapshot.", location);
                return;
            }
            if (!definition.allowedOperators().contains(metric.operator())) {
                emit(SegmentValidationStage.DEFINITION_TYPE_OPERATOR,
                        SegmentValidationErrorCodes.OPERATOR_NOT_ALLOWED,
                        "The metric does not allow the declared comparison operator.", location);
                return;
            }
            if (!resolveRelativeAnchor(metric, definition, scope, location)) {
                return;
            }
            if (!resolveEventSelector(metric, definition, relationOf(definition), location)) {
                return;
            }
            checkMetricTimeAndAggregate(definition, metric);
            checkOperand(
                    definition.resultType(),
                    definition.stringSemantics(),
                    List.of(),
                    definition.currencySemantics(),
                    metric.operator(),
                    metric.condition(),
                    metric.nodeId(),
                    location);
            resolveMetricScopeFilters(metric, definition, relationOf(definition));
            resolved.put(metric.nodeId(), definition);
        }

        private boolean resolveRelativeAnchor(
                MetricPredicate metric,
                MetricDefinition metricDefinition,
                SubjectKind scope,
                TreeIndex.Location location) {
            if (!(metric.window() instanceof RelativeToAttributeWindow relative)) {
                return true;
            }
            String key = relativeAnchorKey(metric.nodeId());
            if (inaccessibleNodes.contains(key)) {
                emit(SegmentValidationStage.AUTHORIZATION, SegmentValidationErrorCodes.RESOURCE_NOT_ACCESSIBLE,
                        "A relative-window anchor is not accessible.", location, index.windowPath(metric.nodeId()));
                return false;
            }
            MetadataDefinition found = definitions.find(relative.anchorDefinitionId());
            if (!(found instanceof AttributeDefinition anchor)) {
                emit(SegmentValidationStage.DEFINITION_TYPE_OPERATOR,
                        SegmentValidationErrorCodes.DEFINITION_KIND_MISMATCH,
                        "A relative-window anchor must be a temporal Attribute.",
                        location, index.windowPath(metric.nodeId()));
                return false;
            }
            if (anchor.subject() != scope || !anchor.entityVersion().equals(metricDefinition.entityVersion())
                    || (anchor.dataType() != SemanticDataType.DATE
                    && anchor.dataType() != SemanticDataType.TIMESTAMP)) {
                emit(SegmentValidationStage.DEFINITION_TYPE_OPERATOR,
                        SegmentValidationErrorCodes.SUBJECT_MISMATCH,
                        "A relative-window anchor must be a temporal Attribute on the metric subject.",
                        location, index.windowPath(metric.nodeId()));
                return false;
            }
            resolved.put(key, anchor);
            return true;
        }

        private boolean resolveEventSelector(
                MetricPredicate metric,
                MetricDefinition metricDefinition,
                RelationDefinition relation,
                TreeIndex.Location location) {
            String selectorId;
            List<MetricScopeFilter> filters;
            if (metric.window() instanceof com.company.campaign.segment.dsl.window.SelectedEventWindow selected) {
                selectorId = selected.selectorId();
                filters = selected.selectorScopeFilters();
            } else if (metric.window() instanceof com.company.campaign.segment.dsl.window.RelativeToEventWindow relative) {
                selectorId = relative.selectorId();
                filters = relative.selectorScopeFilters();
            } else {
                return true;
            }
            EventSelectorDefinition selector = snapshot.eventSelectors().stream()
                    .filter(candidate -> candidate.id().equals(selectorId))
                    .findFirst()
                    .orElse(null);
            EventSelectorBinding binding = selector == null || relation == null
                    ? null
                    : selector.bindingFor(metricDefinition.relationId());
            if (selector == null || selector.subject() != metricDefinition.subject() || binding == null) {
                emit(SegmentValidationStage.DEFINITION_TYPE_OPERATOR,
                        SegmentValidationErrorCodes.WINDOW_UNSUPPORTED,
                        "The event selector is not available for this Metric relation.",
                        location, index.windowPath(metric.nodeId()));
                return false;
            }
            if (filters.isEmpty() && !selector.unscopedByDefault()) {
                emit(SegmentValidationStage.DEFINITION_TYPE_OPERATOR,
                        SegmentValidationErrorCodes.WINDOW_UNSUPPORTED,
                        "The event selector requires an explicit scope.",
                        location, index.windowPath(metric.nodeId()));
                return false;
            }
            resolveSelectorScopeFilters(metric, filters, binding, relation);
            return true;
        }

        private void resolveSelectorScopeFilters(
                MetricPredicate metric,
                List<MetricScopeFilter> filters,
                EventSelectorBinding binding,
                RelationDefinition relation) {
            for (MetricScopeFilter filter : filters) {
                String key = TreeIndex.scopeFilterKey(metric.nodeId(), filter.filterId());
                TreeIndex.Location location = index.location(key);
                MetadataDefinition found = definitions.find(filter.attributeId());
                MetricFilterDimension dimension = binding.scopeFilterDimensions().stream()
                        .filter(candidate -> candidate.attributeId().equals(filter.attributeId()))
                        .findFirst().orElse(null);
                if (!(found instanceof AttributeDefinition attribute)
                        || dimension == null
                        || attribute.subject() != relation.targetEntity()
                        || !attribute.entityVersion().equals(relation.targetVersion())) {
                    emit(SegmentValidationStage.RELATION_SCOPE,
                            SegmentValidationErrorCodes.METRIC_SCOPE_FILTER_NOT_ALLOWED,
                            "The Attribute is not an allowlisted event-selector scope filter.", location);
                    continue;
                }
                if (inaccessibleNodes.contains(key)) {
                    emit(SegmentValidationStage.AUTHORIZATION,
                            SegmentValidationErrorCodes.RESOURCE_NOT_ACCESSIBLE,
                            "A referenced definition is not accessible.", location);
                    continue;
                }
                if (!dimension.allowedOperators().contains(filter.operator())
                        || !attribute.allowedOperators().contains(filter.operator())) {
                    emit(SegmentValidationStage.DEFINITION_TYPE_OPERATOR,
                            SegmentValidationErrorCodes.OPERATOR_NOT_ALLOWED,
                            "The event-selector filter does not allow the declared operator.", location);
                    continue;
                }
                checkOperand(attribute.dataType(), attribute.stringSemantics(), attribute.enumValues(),
                        attribute.currencySemantics(), filter.operator(), filter.condition(), key, location);
                resolved.put(key, attribute);
            }
        }

        private void resolveMetricScopeFilters(
                MetricPredicate metric, MetricDefinition definition, RelationDefinition relation) {
            for (MetricScopeFilter filter : metric.scopeFilters()) {
                String key = TreeIndex.scopeFilterKey(metric.nodeId(), filter.filterId());
                TreeIndex.Location location = index.location(key);
                MetadataDefinition found = definitions.find(filter.attributeId());
                if (found == null) {
                    emit(SegmentValidationStage.DEFINITION_TYPE_OPERATOR,
                            SegmentValidationErrorCodes.DEFINITION_UNKNOWN,
                            "A referenced Metric filter Attribute is unknown.", location);
                    continue;
                }
                if (inaccessibleNodes.contains(key)) {
                    emit(SegmentValidationStage.AUTHORIZATION,
                            SegmentValidationErrorCodes.RESOURCE_NOT_ACCESSIBLE,
                            "A referenced definition is not accessible.", location);
                    continue;
                }
                if (!(found instanceof AttributeDefinition attribute)) {
                    emit(SegmentValidationStage.DEFINITION_TYPE_OPERATOR,
                            SegmentValidationErrorCodes.DEFINITION_KIND_MISMATCH,
                            "A Metric scope filter must reference an Attribute.", location);
                    continue;
                }
                MetricFilterDimension dimension = definition.scopeFilterDimensions().stream()
                        .filter(candidate -> candidate.attributeId().equals(attribute.id()))
                        .findFirst()
                        .orElse(null);
                if (dimension == null || relation == null
                        || attribute.subject() != relation.targetEntity()
                        || !attribute.entityVersion().equals(relation.targetVersion())) {
                    emit(SegmentValidationStage.RELATION_SCOPE,
                            SegmentValidationErrorCodes.METRIC_SCOPE_FILTER_NOT_ALLOWED,
                            "The Attribute is not an allowlisted row filter for this Metric.", location);
                    continue;
                }
                if (!dimension.allowedOperators().contains(filter.operator())
                        || !attribute.allowedOperators().contains(filter.operator())) {
                    emit(SegmentValidationStage.DEFINITION_TYPE_OPERATOR,
                            SegmentValidationErrorCodes.OPERATOR_NOT_ALLOWED,
                            "The Metric filter does not allow the declared comparison operator.", location);
                    continue;
                }
                checkOperand(
                        attribute.dataType(), attribute.stringSemantics(), attribute.enumValues(),
                        attribute.currencySemantics(), filter.operator(), filter.condition(), key, location);
                resolved.put(key, attribute);
            }
        }

        private void resolveExists(ExistsPredicate exists, SubjectKind scope) {
            TreeIndex.Location location = index.location(exists.nodeId());
            if (exists.window() instanceof RelativeToAttributeWindow
                    || exists.window() instanceof com.company.campaign.segment.dsl.window.SelectedEventWindow
                    || exists.window() instanceof com.company.campaign.segment.dsl.window.RelativeToEventWindow) {
                issues.error(SegmentValidationStage.TIME_CURRENCY_NULL,
                        SegmentValidationErrorCodes.WINDOW_UNSUPPORTED,
                        "A relative-to-Attribute window is supported only for a Metric.",
                        index.windowPath(exists.nodeId()), exists.nodeId(), location.index(), Map.of());
                return;
            }
            MetadataDefinition found = definitions.find(exists.relationId());
            if (found == null) {
                emit(SegmentValidationStage.DEFINITION_TYPE_OPERATOR, SegmentValidationErrorCodes.DEFINITION_UNKNOWN,
                        "A referenced relation definition is unknown.", location);
                return;
            }
            if (inaccessibleNodes.contains(exists.nodeId())) {
                emit(SegmentValidationStage.AUTHORIZATION, SegmentValidationErrorCodes.RESOURCE_NOT_ACCESSIBLE,
                        "A referenced definition is not accessible.", location);
                return;
            }
            if (!(found instanceof RelationDefinition relation)) {
                emit(SegmentValidationStage.DEFINITION_TYPE_OPERATOR,
                        SegmentValidationErrorCodes.DEFINITION_KIND_MISMATCH,
                        "A referenced definition is not a relation.", location);
                return;
            }
            if (relation.sourceEntity() != scope) {
                emit(SegmentValidationStage.RELATION_SCOPE, SegmentValidationErrorCodes.SUBJECT_MISMATCH,
                        "The relation source does not match the scope in which it is referenced.", location);
                return;
            }
            if (isBounded(exists.window()) && relation.eventTimeCapability() == null) {
                emit(SegmentValidationStage.RELATION_SCOPE,
                        SegmentValidationErrorCodes.RELATION_EVENT_TIME_REQUIRED,
                        "A bounded relation window requires a declared relation event-time capability.",
                        location, index.windowPath(exists.nodeId()));
            }
            resolved.put(exists.nodeId(), relation);
            resolve(exists.predicate(), relation.targetEntity(), 0, true);
        }

        private void rejectSemanticDuplicateSiblings(GroupPredicate group) {
            if (group.children().size() < 2) {
                return;
            }
            List<String> fingerprints = new ArrayList<>(group.children().size());
            try {
                for (Predicate child : group.children()) {
                    CanonicalNode canonical = SegmentCanonicalizer.node(child, resolved);
                    fingerprints.add(CODEC.hashNode(canonical).value());
                }
            } catch (RuntimeException exception) {
                // A subtree that is not fully resolved cannot be compared; its own issues already block.
                return;
            }
            Set<String> seen = new HashSet<>();
            for (int position = 0; position < fingerprints.size(); position++) {
                if (!seen.add(fingerprints.get(position))) {
                    Predicate child = group.children().get(position);
                    emit(SegmentValidationStage.DEFINITION_TYPE_OPERATOR,
                            SegmentValidationErrorCodes.SEMANTIC_DUPLICATE_SIBLING,
                            "Two siblings in one group have the same canonical meaning.",
                            index.location(child.nodeId()));
                }
            }
        }

        // ---------------------------------------------------------------- operand compatibility

        private void checkOperand(
                SemanticDataType dataType,
                StringSemantics stringSemantics,
                List<EnumValueDefinition> enumValues,
                CurrencySemantics currencySemantics,
                ComparisonOperator operator,
                ConditionValue condition,
                String nodeId,
                TreeIndex.Location location) {
            if (operator.isNullOperator() || condition instanceof com.company.campaign.segment.dsl.predicate.NoValueCondition) {
                return;
            }
            TypedValue value = ((TypedValueCondition) condition).value();
            ValueKind expected = SegmentValueRules.expectedKind(dataType);
            switch (value) {
                case ScalarValue scalar -> {
                    if (scalar.kind() != expected) {
                        emit(SegmentValidationStage.DEFINITION_TYPE_OPERATOR,
                                SegmentValidationErrorCodes.VALUE_TYPE_INCOMPATIBLE,
                                "The operand value kind is not compatible with the definition's data type.",
                                location, index.valuePath(nodeId));
                        return;
                    }
                    checkScalarSemantics(dataType, enumValues, currencySemantics, scalar,
                            location, index.valuePath(nodeId));
                }
                case ListValue list -> checkListOperand(
                        dataType, stringSemantics, enumValues, currencySemantics, list, nodeId, location, expected);
                case RangeValue range -> checkRangeOperand(
                        dataType, stringSemantics, enumValues, currencySemantics, range, nodeId, location, expected);
            }
        }

        private void checkListOperand(
                SemanticDataType dataType,
                StringSemantics stringSemantics,
                List<EnumValueDefinition> enumValues,
                CurrencySemantics currencySemantics,
                ListValue list,
                String nodeId,
                TreeIndex.Location location,
                ValueKind expected) {
            boolean allCompatible = true;
            for (int position = 0; position < list.items().size(); position++) {
                ScalarValue item = list.items().get(position);
                if (item.kind() != expected) {
                    emit(SegmentValidationStage.DEFINITION_TYPE_OPERATOR,
                            SegmentValidationErrorCodes.VALUE_TYPE_INCOMPATIBLE,
                            "A LIST item value kind is not compatible with the definition's data type.",
                            location, index.itemPath(nodeId, position));
                    allCompatible = false;
                }
            }
            if (!allCompatible) {
                return;
            }
            for (int position = 0; position < list.items().size(); position++) {
                checkScalarSemantics(dataType, enumValues, currencySemantics, list.items().get(position),
                        location, index.itemPath(nodeId, position));
            }
            checkDuplicateListItems(dataType, stringSemantics, list, nodeId, location);
            if (dataType == SemanticDataType.MONEY) {
                checkListCurrency(list, nodeId, location);
            }
        }

        private void checkRangeOperand(
                SemanticDataType dataType,
                StringSemantics stringSemantics,
                List<EnumValueDefinition> enumValues,
                CurrencySemantics currencySemantics,
                RangeValue range,
                String nodeId,
                TreeIndex.Location location,
                ValueKind expected) {
            boolean lowerCompatible = range.lower().kind() == expected;
            boolean upperCompatible = range.upper().kind() == expected;
            if (!lowerCompatible) {
                emit(SegmentValidationStage.DEFINITION_TYPE_OPERATOR,
                        SegmentValidationErrorCodes.VALUE_TYPE_INCOMPATIBLE,
                        "The RANGE lower bound is not compatible with the definition's data type.",
                        location, index.boundPath(nodeId, "lower"));
            }
            if (!upperCompatible) {
                emit(SegmentValidationStage.DEFINITION_TYPE_OPERATOR,
                        SegmentValidationErrorCodes.VALUE_TYPE_INCOMPATIBLE,
                        "The RANGE upper bound is not compatible with the definition's data type.",
                        location, index.boundPath(nodeId, "upper"));
            }
            if (range.lower().kind() != range.upper().kind()) {
                emit(SegmentValidationStage.DEFINITION_TYPE_OPERATOR,
                        SegmentValidationErrorCodes.RANGE_BOUNDS_INCOMPATIBLE,
                        "The RANGE bounds are not semantically compatible with each other.",
                        location, index.valuePath(nodeId));
            }
            if (!lowerCompatible || !upperCompatible || range.lower().kind() != range.upper().kind()) {
                // A bound this definition cannot admit is never inspected semantically or for currency.
                return;
            }
            checkScalarSemantics(dataType, enumValues, currencySemantics, range.lower(),
                    location, index.boundPath(nodeId, "lower"));
            checkScalarSemantics(dataType, enumValues, currencySemantics, range.upper(),
                    location, index.boundPath(nodeId, "upper"));
            if (dataType == SemanticDataType.MONEY) {
                checkRangeCurrency(range, nodeId, location);
            }
        }

        private void checkScalarSemantics(
                SemanticDataType dataType,
                List<EnumValueDefinition> enumValues,
                CurrencySemantics currencySemantics,
                ScalarValue scalar,
                TreeIndex.Location location,
                String path) {
            if (dataType == SemanticDataType.ENUM || dataType == SemanticDataType.ORDERED_ENUM) {
                String code = ((EnumValue) scalar).value();
                boolean member = enumValues.stream().anyMatch(value -> value.code().equals(code));
                if (!member) {
                    issues.error(SegmentValidationStage.DEFINITION_TYPE_OPERATOR,
                            SegmentValidationErrorCodes.ENUM_CODE_UNKNOWN,
                            "The enum operand code is not a member of the referenced definition.",
                            path, location.nodeId(), location.index(), Map.of());
                }
                return;
            }
            if (dataType == SemanticDataType.MONEY && !currencySemantics.isExplicitPolicy()) {
                issues.error(SegmentValidationStage.TIME_CURRENCY_NULL,
                        SegmentValidationErrorCodes.METADATA_DECLARATION_INCONSISTENT,
                        "A monetary definition must declare an explicit currency policy.",
                        path, location.nodeId(), location.index(), Map.of());
            }
        }

        private void checkDuplicateListItems(
                SemanticDataType dataType,
                StringSemantics stringSemantics,
                ListValue list,
                String nodeId,
                TreeIndex.Location location) {
            Set<String> seen = new HashSet<>();
            for (int position = 0; position < list.items().size(); position++) {
                CanonicalValue canonical =
                        SegmentValueRules.canonicalScalar(dataType, stringSemantics, list.items().get(position));
                String fingerprint = CODEC.hashValue(canonical).value() + CODEC.writeValueString(canonical);
                if (!seen.add(fingerprint)) {
                    emit(SegmentValidationStage.DEFINITION_TYPE_OPERATOR,
                            SegmentValidationErrorCodes.SEMANTIC_DUPLICATE_LIST_ITEM,
                            "Two items of one LIST operand have the same canonical meaning.",
                            location, index.itemPath(nodeId, position));
                }
            }
        }

        private void checkListCurrency(ListValue list, String nodeId, TreeIndex.Location location) {
            String currency = null;
            for (int position = 0; position < list.items().size(); position++) {
                String itemCurrency = ((MoneyValue) list.items().get(position)).currency();
                if (currency == null) {
                    currency = itemCurrency;
                } else if (!currency.equals(itemCurrency)) {
                    emit(SegmentValidationStage.TIME_CURRENCY_NULL,
                            SegmentValidationErrorCodes.CURRENCY_MISMATCH,
                            "MONEY values inside one operand do not share a currency.",
                            location, index.itemPath(nodeId, position));
                }
            }
        }

        private void checkRangeCurrency(RangeValue range, String nodeId, TreeIndex.Location location) {
            String lower = ((MoneyValue) range.lower()).currency();
            String upper = ((MoneyValue) range.upper()).currency();
            if (!lower.equals(upper)) {
                emit(SegmentValidationStage.TIME_CURRENCY_NULL, SegmentValidationErrorCodes.CURRENCY_MISMATCH,
                        "MONEY values inside one operand do not share a currency.",
                        location, index.valuePath(nodeId));
            }
        }

        // ---------------------------------------------------------------- metric time/aggregate rules

        private void checkMetricTimeAndAggregate(MetricDefinition definition, MetricPredicate metric) {
            checkMetricTimeAndAggregate(
                    definition, metric.window(), metric.nodeId(), index.location(metric.nodeId()));
        }

        private void checkMetricTimeAndAggregate(
                MetricDefinition definition,
                TimeWindow window,
                String nodeId,
                TreeIndex.Location location) {
            String windowPath = index.windowPath(nodeId);
            TimeWindowKind windowKind = windowKind(window);
            if (!definition.supportedWindows().contains(windowKind)) {
                issues.error(SegmentValidationStage.TIME_CURRENCY_NULL,
                        SegmentValidationErrorCodes.WINDOW_UNSUPPORTED,
                        "The metric does not support the declared window kind.", windowPath, nodeId,
                        location.index(), Map.of("window", windowKind.name()));
            } else if (windowKind.isBounded()
                    && (definition.eventTimeField() == null
                    || definition.eventTimeSemantics() == null
                    || definition.eventTimeSemantics() == EventTimeSemantics.NOT_APPLICABLE)) {
                issues.error(SegmentValidationStage.TIME_CURRENCY_NULL,
                        SegmentValidationErrorCodes.METADATA_DECLARATION_INCONSISTENT,
                        "A bounded-window metric must pin an event-time field and its semantics.",
                        windowPath, nodeId, location.index(), Map.of());
            }
            if (definition.emptySet().behavior() == EmptySetBehavior.UNDEFINED) {
                issues.error(SegmentValidationStage.TIME_CURRENCY_NULL,
                        SegmentValidationErrorCodes.METADATA_DECLARATION_INCONSISTENT,
                        "A metric must not publish an undefined empty-set behavior.",
                        location.path(), nodeId, location.index(), Map.of());
            }
            if (!definition.aggregation().supports(definition.resultType())) {
                issues.error(SegmentValidationStage.TIME_CURRENCY_NULL,
                        SegmentValidationErrorCodes.METADATA_DECLARATION_INCONSISTENT,
                        "The metric aggregate cannot produce its declared result type.",
                        location.path(), nodeId, location.index(), Map.of());
            }
        }

        // ---------------------------------------------------------------- stage 7: complexity

        private void complexityStage() {
            if (policy == null) {
                issues.error(SegmentValidationStage.COMPLEXITY, SegmentValidationErrorCodes.POLICY_MISSING,
                        "A complexity policy is required.", null, null, -1, Map.of());
                return;
            }
            Stats stats = new Stats();
            walkComplexity(document.predicate(), 1, 0, stats);
            if (stats.nodes > policy.maxPredicateNodes()) {
                TreeIndex.Location root = index.location(document.predicate().nodeId());
                issues.error(SegmentValidationStage.COMPLEXITY,
                        SegmentValidationErrorCodes.COMPLEXITY_NODES_EXCEEDED,
                        "The predicate tree exceeds the policy's total node budget.", root.path(),
                        document.predicate().nodeId(), root.index(),
                        Map.of("limit", policy.maxPredicateNodes(), "actual", stats.nodes));
            }
        }

        private void walkComplexity(Predicate node, int depth, int relationDepth, Stats stats) {
            stats.nodes++;
            TreeIndex.Location location = index.location(node.nodeId());
            if (depth == policy.maxPredicateDepth() + 1) {
                issues.error(SegmentValidationStage.COMPLEXITY,
                        SegmentValidationErrorCodes.COMPLEXITY_DEPTH_EXCEEDED,
                        "The predicate tree exceeds the policy's maximum depth.", location.path(), node.nodeId(),
                        location.index(), Map.of("limit", policy.maxPredicateDepth(), "actual", depth));
            }
            switch (node) {
                case GroupPredicate group -> {
                    if (group.type() == PredicateType.ANY && group.children().size() > policy.maxAnyWidth()) {
                        issues.error(SegmentValidationStage.COMPLEXITY,
                                SegmentValidationErrorCodes.COMPLEXITY_ANY_WIDTH_EXCEEDED,
                                "An ANY group exceeds the policy's maximum direct width.", location.path(),
                                node.nodeId(), location.index(),
                                Map.of("limit", policy.maxAnyWidth(), "actual", group.children().size()));
                    }
                    if (group.type() == PredicateType.ALL && group.children().size() > policy.maxAllWidth()) {
                        issues.error(SegmentValidationStage.COMPLEXITY,
                                SegmentValidationErrorCodes.COMPLEXITY_ALL_WIDTH_EXCEEDED,
                                "An ALL group exceeds the policy's maximum direct width.", location.path(),
                                node.nodeId(), location.index(),
                                Map.of("limit", policy.maxAllWidth(), "actual", group.children().size()));
                    }
                    group.children().forEach(child -> walkComplexity(child, depth + 1, relationDepth, stats));
                }
                case NotPredicate not -> walkComplexity(not.child(), depth + 1, relationDepth, stats);
                case ExistsPredicate exists -> {
                    if (relationDepth + 1 > policy.maxRelationNestingDepth()) {
                        issues.error(SegmentValidationStage.COMPLEXITY,
                                SegmentValidationErrorCodes.COMPLEXITY_RELATION_DEPTH_EXCEEDED,
                                "The relation nesting exceeds the policy's maximum depth.", location.path(),
                                node.nodeId(), location.index(),
                                Map.of("limit", policy.maxRelationNestingDepth(), "actual", relationDepth + 1));
                    }
                    walkComplexity(exists.predicate(), depth + 1, relationDepth + 1, stats);
                }
                case AttributePredicate ignored -> {
                }
                case MetricPredicate ignored -> {
                }
                case MetricExpressionPredicate ignored -> {
                }
                // A reference is one node of this document. The complexity it *expands* to is bounded
                // separately by the reference policy, not by the caller's predicate-node budget.
                case AudienceReferencePredicate ignored -> {
                }
            }
        }

        // ---------------------------------------------------------------- helpers

        private void emit(
                SegmentValidationStage stage, String code, String message, TreeIndex.Location location) {
            issues.error(stage, code, message, location.path(), location.nodeId(), location.index(), Map.of());
        }

        private void emit(
                SegmentValidationStage stage,
                String code,
                String message,
                TreeIndex.Location location,
                String path) {
            issues.error(stage, code, message, path, location.nodeId(), location.index(), Map.of());
        }

        private SegmentValidationResult rejected(
                com.company.campaign.contracts.SchemaVersion schemaVersion,
                MetadataBinding binding,
                ContentHash editableHash) {
            return SegmentValidationResult.rejected(
                    CanonicalizerVersions.CANONICALIZER_VERSION,
                    schemaVersion,
                    binding,
                    editableHash,
                    issues.ordered());
        }

        private boolean isBounded(TimeWindow window) {
            return windowKind(window).isBounded();
        }

        private TimeWindowKind windowKind(TimeWindow window) {
            return switch (window) {
                case RollingWindow ignored -> TimeWindowKind.ROLLING;
                case LifetimeWindow ignored -> TimeWindowKind.LIFETIME;
                case FixedWindow ignored -> TimeWindowKind.FIXED;
                case RelativeToAttributeWindow ignored -> TimeWindowKind.RELATIVE_TO_ATTRIBUTE;
                case com.company.campaign.segment.dsl.window.SelectedEventWindow ignored ->
                        TimeWindowKind.SELECTED_EVENT;
                case com.company.campaign.segment.dsl.window.RelativeToEventWindow ignored ->
                        TimeWindowKind.RELATIVE_TO_EVENT;
            };
        }

        private static String relativeAnchorKey(String metricNodeId) {
            return metricNodeId + "/window/anchor";
        }

        private static List<MetricScopeFilter> selectorFilters(TimeWindow window) {
            if (window instanceof com.company.campaign.segment.dsl.window.SelectedEventWindow selected) {
                return selected.selectorScopeFilters();
            }
            if (window instanceof com.company.campaign.segment.dsl.window.RelativeToEventWindow relative) {
                return relative.selectorScopeFilters();
            }
            return List.of();
        }
    }

    private record Reference(String nodeId, MetadataKind kind, String definitionId) {
    }

    private static final class Stats {
        private int nodes;
    }
}
