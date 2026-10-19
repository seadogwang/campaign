package com.company.campaign.builder.refine.testsupport;

import com.company.campaign.builder.agent.SegmentAuditToolCode;
import com.company.campaign.builder.agent.SegmentPreviewCard;
import com.company.campaign.builder.agent.SegmentRunTimings;
import com.company.campaign.builder.agent.testsupport.AgentTestFixtures;
import com.company.campaign.builder.refine.RefinementField;
import com.company.campaign.builder.refine.RefinementReplacement;
import com.company.campaign.builder.refine.SegmentRefinementAdapter;
import com.company.campaign.builder.refine.SegmentRefinementAuditSink;
import com.company.campaign.builder.refine.SegmentRefinementEnvironment;
import com.company.campaign.builder.refine.SegmentRefinementOperation;
import com.company.campaign.builder.refine.SegmentRefinementPatch;
import com.company.campaign.builder.refine.SegmentRefinementPatchCodec;
import com.company.campaign.builder.refine.SegmentRefinementRequest;
import com.company.campaign.builder.refine.SegmentRefinementResult;
import com.company.campaign.builder.segment.testsupport.SliceTestFixtures;
import com.company.campaign.builder.skill.SegmentSkillDefinition;
import com.company.campaign.builder.skill.SegmentSkillLoader;
import com.company.campaign.builder.tool.SegmentPreviewPort;
import com.company.campaign.builder.tool.SegmentToolCatalog;
import com.company.campaign.builder.tool.SegmentToolDescriptor;
import com.company.campaign.builder.tool.SegmentToolHandler;
import com.company.campaign.builder.tool.SegmentToolIds;
import com.company.campaign.builder.tool.SegmentToolRunContext;
import com.company.campaign.builder.tool.StandardSegmentToolHandlers;
import com.company.campaign.contracts.AgentEnvelope;
import com.company.campaign.contracts.ContractVersions;
import com.company.campaign.metadata.domain.ComparisonOperator;
import com.company.campaign.metadata.domain.MetadataSnapshot;
import com.company.campaign.metadata.domain.SubjectKind;
import com.company.campaign.metadata.service.MetadataQueryService;
import com.company.campaign.preview.PreviewExecutionBackend;
import com.company.campaign.preview.PreviewPresentation;
import com.company.campaign.preview.PreviewResponse;
import com.company.campaign.query.plan.SegmentQueryPlanner;
import com.company.campaign.query.plan.SemanticQueryPlan;
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
import com.company.campaign.segment.dsl.value.EnumValue;
import com.company.campaign.segment.dsl.value.IntegerValue;
import com.company.campaign.segment.dsl.value.MoneyValue;
import com.company.campaign.segment.dsl.value.StringValue;
import com.company.campaign.segment.dsl.value.TypedValue;
import com.company.campaign.segment.dsl.window.RollingWindow;
import com.company.campaign.segment.dsl.window.WindowUnit;
import com.company.campaign.segment.resolution.ResolutionResult;
import com.company.campaign.segment.validation.SegmentValidationPolicy;
import com.company.campaign.segment.validation.SegmentValidationResult;
import com.company.campaign.segment.validation.SegmentValidator;
import com.company.campaign.segment.validation.testsupport.SegmentValidationFixtures;
import com.company.campaign.security.authorization.AuthorizationService;
import com.company.campaign.security.context.TrustedExecutionContext;
import com.company.campaign.preview.PreviewConditionStatistic;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Deterministic T12 fixtures built only from accepted public contracts and the accepted component test support.
 *
 * <p>No clock, network, database, credential, random identifier or corpus asset is used: the read-only Preview
 * backend is the only stub, which the task explicitly allows.
 */
public final class RefinementTestFixtures {

    public static final String RUN_ID = "refinement-run-1";
    public static final String CONVERSATION_ID = "conversation-1";

    private static final SegmentRefinementPatchCodec CODEC = new SegmentRefinementPatchCodec();

    private RefinementTestFixtures() {
    }

    public static SegmentSkillDefinition skill() {
        return new SegmentSkillLoader().loadDefault();
    }

    public static SegmentToolCatalog catalog() {
        return SegmentToolCatalog.fromSkill(skill());
    }

    public static MetadataSnapshot snapshot() {
        return SliceTestFixtures.snapshot();
    }

    public static TrustedExecutionContext context() {
        return SliceTestFixtures.context();
    }

    public static MetadataBinding binding() {
        return MetadataBinding.fromSnapshot(snapshot());
    }

    public static SegmentValidationPolicy validationPolicy() {
        return SliceTestFixtures.validationPolicy();
    }

    // ------------------------------------------------------------------ documents

    /** A two-condition {@code ALL} base document that genuinely validates, plans and previews. */
    public static SegmentDsl baseDocument() {
        return publish(new GroupPredicate("n-root", PredicateType.ALL, List.of(
                tierEqualsGold("n-tier"),
                rollingSpend("n-spend", 90))));
    }

    /** A nested {@code ALL}/{@code ANY}/{@code NOT}/{@code EXISTS} document covering every T05 leaf kind. */
    public static SegmentDsl nestedDocument() {
        return publish(new GroupPredicate("n-root", PredicateType.ALL, List.of(
                tierEqualsGold("n-tier"),
                new GroupPredicate("n-any", PredicateType.ANY, List.of(
                        new AttributePredicate(
                                "n-city", "member.code", ComparisonOperator.EQUAL,
                                ConditionValue.of(new StringValue("SH"))),
                        new NotPredicate(
                                "n-not", new AttributePredicate(
                                        "n-code", "member.code", ComparisonOperator.IS_NOT_NULL,
                                        ConditionValue.none())))),
                new ExistsPredicate(
                        "n-exists", "member.orders", new RollingWindow(30, WindowUnit.DAY),
                        new AttributePredicate(
                                "n-amount", "member.code", ComparisonOperator.EQUAL,
                                ConditionValue.of(new StringValue("A")))))));
    }

    /** A three-child {@code ALL} document, for same-parent ordering proofs. */
    public static SegmentDsl threeChildDocument() {
        return publish(new GroupPredicate("n-root", PredicateType.ALL, List.of(
                tierEqualsGold("n-tier"),
                rollingSpend("n-spend", 90),
                new AttributePredicate(
                        "n-city", "member.code", ComparisonOperator.EQUAL,
                        ConditionValue.of(new StringValue("SH"))))));
    }

    /** A document whose only leaf is a closed metric expression, covering the last T05 leaf kind. */
    public static SegmentDsl expressionDocument() {
        return publish(new GroupPredicate("n-root", PredicateType.ALL, List.of(
                new com.company.campaign.segment.dsl.predicate.MetricExpressionPredicate(
                        "n-expr",
                        new com.company.campaign.segment.dsl.metric.MetricExpression.MetricRef("order.count"),
                        new com.company.campaign.segment.dsl.window.LifetimeWindow(),
                        ComparisonOperator.GREATER_THAN,
                        ConditionValue.of(new IntegerValue(3L))))));
    }

    /**
     * A document where two distinct nodes bind the same Metadata definition, so a structural edit can leave a
     * clause's definition referenced elsewhere even when the clause's own node is gone.
     */
    public static SegmentDsl duplicateDefinitionDocument() {
        return publish(new GroupPredicate("n-root", PredicateType.ALL, List.of(
                tierEqualsGold("n-tier"),
                new AttributePredicate(
                        "n-tier-alt", "member.tier", ComparisonOperator.EQUAL,
                        ConditionValue.of(new EnumValue("SILVER"))),
                rollingSpend("n-spend", 90))));
    }

    /** A document with a single-child ANY group, so a removal or detach can leave it empty. */
    public static SegmentDsl singleChildGroupDocument() {
        return publish(new GroupPredicate("n-root", PredicateType.ALL, List.of(
                tierEqualsGold("n-tier"),
                new GroupPredicate("n-any", PredicateType.ANY, List.of(
                        new AttributePredicate(
                                "n-city", "member.code", ComparisonOperator.EQUAL,
                                ConditionValue.of(new StringValue("SH"))))))));
    }

    /** A document with an explicit NULL-operator leaf, for operator/value shape proofs. */
    public static SegmentDsl nullOperatorDocument() {
        return publish(new GroupPredicate("n-root", PredicateType.ALL, List.of(
                tierEqualsGold("n-tier"),
                new AttributePredicate(
                        "n-null", "member.code", ComparisonOperator.IS_NULL, ConditionValue.none()))));
    }

    public static SegmentDsl publish(Predicate predicate) {
        return SegmentDsl.publish(
                SegmentDslSchemaVersions.SUPPORTED, SubjectKind.MEMBER, binding(), predicate);
    }

    public static AttributePredicate tierEqualsGold(String nodeId) {
        return new AttributePredicate(
                nodeId, "member.tier", ComparisonOperator.EQUAL, ConditionValue.of(new EnumValue("GOLD")));
    }

    public static MetricPredicate rollingSpend(String nodeId, int days) {
        return new MetricPredicate(
                nodeId, "order.net_paid_amount", new RollingWindow(days, WindowUnit.DAY),
                ComparisonOperator.GREATER_THAN, ConditionValue.of(new MoneyValue("1000.00", "CNY")));
    }

    // ------------------------------------------------------------------ patches

    public static SegmentRefinementPatch patch(
            SegmentDsl base, SegmentRefinementOperation... operations) {
        return SegmentRefinementPatch.publish(
                SegmentDslSchemaVersions.SUPPORTED, base.editableDslHash(), List.of(operations));
    }

    public static SegmentRefinementOperation replaceWindow(
            String operationId, String nodeId, int days) {
        return new SegmentRefinementOperation.ReplaceField(
                operationId, nodeId, RefinementField.WINDOW,
                new RefinementReplacement.WindowReplacement(new RollingWindow(days, WindowUnit.DAY)));
    }

    public static SegmentRefinementOperation replaceValue(
            String operationId, String nodeId, TypedValue value) {
        return new SegmentRefinementOperation.ReplaceField(
                operationId, nodeId, RefinementField.VALUE,
                new RefinementReplacement.ConditionReplacement(value));
    }

    public static SegmentRefinementOperation replaceOperator(
            String operationId, String nodeId, ComparisonOperator operator) {
        return new SegmentRefinementOperation.ReplaceField(
                operationId, nodeId, RefinementField.OPERATOR,
                new RefinementReplacement.OperatorReplacement(operator));
    }

    public static String patchJson(SegmentDsl base, String operationsJson) {
        return "{\"schemaVersion\":\"1.3\",\"currentDslHash\":\"" + base.editableDslHash().value()
                + "\",\"operations\":" + operationsJson + "}";
    }

    public static SegmentRefinementPatch parse(String json) {
        return CODEC.parse(json.getBytes(StandardCharsets.UTF_8));
    }

    /** The exact canonical wire string of a patch, the write counterpart of {@link #parse(String)}. */
    public static String write(SegmentRefinementPatch patch) {
        return CODEC.writeString(patch);
    }

    // ------------------------------------------------------------------ environment

    public static SegmentRefinementEnvironment environment() {
        return environment(SegmentValidationFixtures.allowAll(), AgentTestFixtures.previewPort(
                snapshot(), context(), SegmentValidationFixtures.allowAll()));
    }

    public static SegmentRefinementEnvironment environment(
            AuthorizationService authorizationService, SegmentPreviewPort previewPort) {
        return environment(authorizationService, previewPort, null, null);
    }

    public static SegmentRefinementEnvironment environment(
            AuthorizationService authorizationService,
            SegmentPreviewPort previewPort,
            SegmentRefinementAuditSink auditSink,
            SegmentRunTimings timings) {
        return SegmentRefinementEnvironment.of(
                skill(),
                com.company.campaign.preview.testsupport.RecordingTrustedContextProvider.of(context()),
                runContext -> snapshot(),
                new MetadataQueryService(authorizationService, AgentTestFixtures.repository(snapshot())),
                authorizationService,
                validationPolicy(),
                previewPort,
                auditSink,
                timings);
    }

    public static AgentEnvelope<SegmentRefinementRequest> envelope(SegmentRefinementRequest request) {
        return new AgentEnvelope<>(ContractVersions.CURRENT, RUN_ID, CONVERSATION_ID, request);
    }

    public static SegmentRefinementResult run(SegmentRefinementEnvironment environment, SegmentDsl base,
            SegmentRefinementPatch patch) {
        return new SegmentRefinementAdapter(environment)
                .invoke(envelope(new SegmentRefinementRequest(base, patch)));
    }

    public static SegmentRefinementResult run(SegmentRefinementEnvironment environment, SegmentDsl base,
            SegmentRefinementPatch patch, ResolutionResult priorResolution) {
        return new SegmentRefinementAdapter(environment)
                .invoke(envelope(new SegmentRefinementRequest(base, patch, priorResolution)));
    }

    // ------------------------------------------------------------------ Tool-boundary support

    /** The server-owned handler map with one Tool deliberately left unimplemented. */
    public static Map<String, SegmentToolHandler> handlersWithout(String toolId) {
        Map<String, SegmentToolHandler> handlers = new LinkedHashMap<>(StandardSegmentToolHandlers.all());
        handlers.remove(toolId);
        return Map.copyOf(handlers);
    }

    /** A catalog whose Skill declares an explicitly reduced Tool-call budget. */
    public static SegmentToolCatalog catalogWithToolCallBudget(int maxToolCalls) {
        SegmentSkillDefinition base = skill();
        SegmentSkillDefinition.Bounds bounds = new SegmentSkillDefinition.Bounds(
                base.limits().maxSteps(), maxToolCalls, base.limits().maxToolPayloadBytes(),
                base.limits().maxModelOutputBytes(), base.limits().maxUtteranceLength(),
                base.limits().maxConversationTurns());
        return SegmentToolCatalog.fromSkill(SegmentSkillDefinition.publish(
                base.schemaVersion(), base.skillId(), base.skillVersion(), base.capabilityState(),
                base.evidenceClass(), base.contractPins(), base.tools(), base.inputDispositions(),
                base.outputDispositions(), base.terminalStates(), bounds, base.dataClassification(),
                base.sampleOutputPermitted(), base.ambiguityStopsAt(), base.failureBehavior()));
    }

    /** The refinement-required Tool path, for invoker-level ordering proofs. */
    public static List<String> refinementToolPath() {
        return List.of(
                SegmentToolIds.METADATA_DISCOVER, SegmentToolIds.DSL_VALIDATE, SegmentToolIds.PLAN_BUILD,
                SegmentToolIds.PREVIEW_SAFE);
    }

    // ------------------------------------------------------------------ Preview support

    /** A read-only Preview backend that counts its executions. */
    public static com.company.campaign.preview.testsupport.StubPreviewBackend countingBackend() {
        return new com.company.campaign.preview.testsupport.StubPreviewBackend((plan, runContext) ->
                com.company.campaign.preview.testsupport.StubPreviewBackend.coherent(
                        plan, runContext, AgentTestFixtures.MEMBERS, "refinement-stub-backend",
                        com.company.campaign.preview.testsupport.StubPreviewBackend.DEFAULT_VERSION));
    }

    public static SegmentPreviewPort previewPortWithBackend(PreviewExecutionBackend backend) {
        return AgentTestFixtures.previewPortWithBackend(
                snapshot(), context(), SegmentValidationFixtures.allowAll(), backend);
    }

    /** A typed Preview port that always returns one fixed response, for incoherence tests. */
    public static SegmentPreviewPort previewPortReturning(PreviewResponse response) {
        return AgentTestFixtures.previewPortReturning(response);
    }

    /** A typed Preview port that fails, so the protected implementation is entered and refuses. */
    public static SegmentPreviewPort failingPreviewPort() {
        return (document, presentation) -> {
            throw com.company.campaign.preview.PreviewException.of(
                    com.company.campaign.preview.PreviewErrorCodes.BACKEND_FAILED, "stub");
        };
    }

    // ------------------------------------------------------------------ counting run context

    /**
     * A host-owned run context for the invoker-level boundary tests.
     *
     * <p>It exposes the genuine T07 validator, T08 planner and an explicitly injected T09C Preview port, so a
     * refusal at the Tool boundary can be proved to have happened before the protected implementation ran: the
     * authorization boundary, the planner and the Preview port are all wrapped in counters.
     */
    public static final class ToolRunContext implements SegmentToolRunContext {

        private final MetadataSnapshot snapshot = RefinementTestFixtures.snapshot();
        private final TrustedExecutionContext context = RefinementTestFixtures.context();
        private final MetadataQueryService metadataQueryService;
        private final SegmentValidator validator;
        private final SegmentQueryPlanner planner;
        private final SegmentPreviewPort previewPort;
        private final SegmentDsl document;
        private SegmentValidationResult validation;
        private SemanticQueryPlan plan;
        private SegmentPreviewCard card;

        public ToolRunContext(AuthorizationService authorizationService, SegmentPreviewPort previewPort,
                SegmentDsl document) {
            this.metadataQueryService =
                    new MetadataQueryService(authorizationService, AgentTestFixtures.repository(snapshot));
            this.validator = new SegmentValidator(authorizationService);
            this.planner = new SegmentQueryPlanner(authorizationService);
            this.previewPort = previewPort;
            this.document = document;
        }

        /** Records the accepted validation result an earlier admitted call produced. */
        public void acceptValidation(SegmentValidationResult result) {
            this.validation = result;
        }

        /** Records the accepted plan an earlier admitted call produced. */
        public void acceptPlan(SemanticQueryPlan accepted) {
            this.plan = accepted;
        }

        /** Records the accepted Preview card an earlier admitted call produced. */
        public void acceptCard(SegmentPreviewCard previewCard) {
            this.card = previewCard;
        }

        @Override
        public TrustedExecutionContext trustedContext() {
            return context;
        }

        @Override
        public MetadataSnapshot metadataSnapshot() {
            return snapshot;
        }

        @Override
        public MetadataQueryService metadataQueryService() {
            return metadataQueryService;
        }

        @Override
        public com.company.campaign.segment.intent.SegmentIntent intent() {
            return null;
        }

        @Override
        public SegmentDsl dslDocument() {
            return document;
        }

        @Override
        public SegmentValidationResult validation() {
            return validation;
        }

        @Override
        public SemanticQueryPlan plan() {
            return plan;
        }

        @Override
        public PreviewResponse previewResponse() {
            return null;
        }

        @Override
        public ResolutionResult resolution() {
            return null;
        }

        @Override
        public SegmentPreviewCard previewCard() {
            return card;
        }

        @Override
        public SegmentValidator validator() {
            return validator;
        }

        @Override
        public SegmentValidationPolicy validationPolicy() {
            return RefinementTestFixtures.validationPolicy();
        }

        @Override
        public SegmentQueryPlanner planner() {
            return planner;
        }

        @Override
        public SegmentPreviewPort previewPort() {
            return previewPort;
        }
    }

    /**
     * An authorization boundary that denies exactly one Metadata Attribute and delegates everything else to
     * the accepted allow-all boundary, so a refinement can be proved to be re-authorized after application.
     */
    public static AuthorizationService denyAttribute(String attributeId) {
        AuthorizationService delegate = SegmentValidationFixtures.allowAll();
        return request -> {
            if (request != null && request.resource() != null
                    && request.resource().kind() == com.company.campaign.security.authorization.ResourceKind.ATTRIBUTE
                    && attributeId.equals(request.resource().resourceId())) {
                return com.company.campaign.security.authorization.AuthorizationDecision.deny(
                        com.company.campaign.security.authorization.AuthorizationDisposition.PERMISSION_DENIED,
                        com.company.campaign.security.authorization.AuthorizationReason.MISSING_PERMISSION,
                        request.resource().kind(),
                        com.company.campaign.security.authorization.ResourceKind.ATTRIBUTE.requiredPermission());
            }
            return delegate.authorize(request);
        };
    }

    /**
     * A typed Preview port that records the exact document and presentation of every run, so a continuation
     * test can prove which successive document each Preview actually saw.
     */
    public static final class RecordingDocumentPreviewPort implements SegmentPreviewPort {

        private final SegmentPreviewPort delegate;
        private final List<SegmentDsl> documents = new ArrayList<>();
        private final List<PreviewPresentation> presentations = new ArrayList<>();

        public RecordingDocumentPreviewPort(SegmentPreviewPort delegate) {
            this.delegate = delegate;
        }

        @Override
        public PreviewResponse preview(SegmentDsl document, PreviewPresentation presentation) {
            documents.add(document);
            presentations.add(presentation);
            return delegate.preview(document, presentation);
        }

        /** The documents this port was invoked with, in invocation order. */
        public List<SegmentDsl> documents() {
            return List.copyOf(documents);
        }

        /** The presentations this port was invoked with, in invocation order. */
        public List<PreviewPresentation> presentations() {
            return List.copyOf(presentations);
        }
    }

    /** A typed Preview port that counts how many times the protected implementation was entered. */
    public static final class CountingPreviewPort implements SegmentPreviewPort {

        private final SegmentPreviewPort delegate;
        private int calls;

        public CountingPreviewPort(SegmentPreviewPort delegate) {
            this.delegate = delegate;
        }

        @Override
        public PreviewResponse preview(SegmentDsl document, PreviewPresentation presentation) {
            calls++;
            return delegate.preview(document, presentation);
        }

        /** How many times this port was entered. */
        public int calls() {
            return calls;
        }
    }

    /** A bounded condition-statistic helper for incoherent-response tests. */
    public static PreviewConditionStatistic statistic(String path) {
        return new PreviewConditionStatistic(path, 1, 0, 0);
    }

    /** The ordered Tool identifiers of one Tool trace, for assertions. */
    public static List<String> toolIds(List<com.company.campaign.builder.tool.SegmentToolCallRecord> trace) {
        List<String> ids = new ArrayList<>(trace.size());
        trace.forEach(call -> ids.add(call.toolId()));
        return ids;
    }

    /** The ordered audit Tool codes of one audit record, for assertions. */
    public static List<String> auditToolIds(List<SegmentAuditToolCode> codes) {
        List<String> ids = new ArrayList<>(codes.size());
        codes.forEach(code -> ids.add(code.toolId()));
        return ids;
    }

    /** A recording audit sink, so a test can count and inspect exactly what was published. */
    public static final class RecordingAuditSink implements SegmentRefinementAuditSink {

        private final List<com.company.campaign.builder.refine.SegmentRefinementAuditRecord> records =
                new ArrayList<>();
        private final boolean failing;

        public RecordingAuditSink() {
            this(false);
        }

        public RecordingAuditSink(boolean failing) {
            this.failing = failing;
        }

        @Override
        public void record(com.company.campaign.builder.refine.SegmentRefinementAuditRecord record) {
            records.add(record);
            if (failing) {
                throw new IllegalStateException("audit sink unavailable");
            }
        }

        public List<com.company.campaign.builder.refine.SegmentRefinementAuditRecord> records() {
            return List.copyOf(records);
        }
    }

    /** The declared rank of one Tool in the committed catalog, or -1. */
    public static int rankOf(String toolId) {
        SegmentToolDescriptor descriptor = catalog().descriptor(toolId);
        return descriptor == null ? -1 : descriptor.rank();
    }

    /** A bounded integer operand, for threshold-replacement cases. */
    public static TypedValue integerOperand(long value) {
        return new IntegerValue(value);
    }

    /** Presentation flags captured by the recording Preview port of the accepted Agent fixtures. */
    public static List<PreviewPresentation> presentationsOf(AgentTestFixtures.RecordingPreviewPort port) {
        return port.presentations();
    }
}