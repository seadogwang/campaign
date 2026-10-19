package com.company.campaign.builder.segment;

import com.company.campaign.builder.segment.DevelopmentAiToSqlResult.ParameterBinding;
import com.company.campaign.contracts.Disposition;
import com.company.campaign.query.QueryException;
import com.company.campaign.query.plan.SegmentQueryPlanner;
import com.company.campaign.query.plan.SemanticQueryPlan;
import com.company.campaign.query.sql.CompiledSql;
import com.company.campaign.query.sql.SqlCompiler;
import com.company.campaign.query.sql.SqlException;
import com.company.campaign.query.sql.SqlParameter;
import com.company.campaign.query.sql.SqlParameterType;
import com.company.campaign.segment.intent.SegmentIntent;
import com.company.campaign.segment.intent.SegmentIntentHasher;
import com.company.campaign.segment.resolution.ClarificationChoiceService;
import com.company.campaign.segment.resolution.ClarificationSelectionPort;
import com.company.campaign.segment.resolution.IntentResolver;
import com.company.campaign.segment.resolution.ResolutionException;
import com.company.campaign.segment.resolution.ResolutionResult;
import com.company.campaign.segment.validation.SegmentValidationErrorCodes;
import com.company.campaign.segment.validation.SegmentValidationResult;
import com.company.campaign.segment.validation.SegmentValidator;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The framework-independent, development-only AI -> Segment -> PostgreSQL SQL validation harness.
 *
 * <p>It is <em>not</em> the production Campaign Builder Agent and not a production capability. It composes only
 * accepted components in one fixed order: build a bounded safe model request from the utterance and the
 * host-supplied permitted snapshot; call the injected {@link SegmentIntentModelPort} exactly once; strict-parse
 * and ground the untrusted output against the exact utterance; resolve through T10; compile the resolved Intent
 * to the T05 DSL deterministically; validate/canonicalize through T07 with host-supplied context and
 * authorization; plan through T08; compile allowlisted parameterized PostgreSQL through T09B with the exact
 * server-owned catalog, compile policy and optional FX binding; and return one redacted
 * {@code DEVELOPMENT_ONLY} result.
 *
 * <p>It never executes SQL and owns no database handle, connection pool, repository, Web endpoint or
 * persistence. It terminates without a DSL/SQL artifact on {@code NEEDS_INPUT}, {@code UNSUPPORTED},
 * {@code DENIED} or {@code INVALID}.
 */
public final class DevelopmentAiToSqlService {

    private final SegmentIntentModelPort modelPort;
    private final SegmentValidator validator;
    private final SegmentQueryPlanner planner;
    private final SqlCompiler sqlCompiler;
    private final SegmentModelRequestFactory requestFactory = new SegmentModelRequestFactory();
    private final SegmentModelOutputGuard outputGuard = new SegmentModelOutputGuard();

    public DevelopmentAiToSqlService(
            SegmentIntentModelPort modelPort,
            com.company.campaign.security.authorization.AuthorizationService authorizationService) {
        this.modelPort = Objects.requireNonNull(modelPort, "modelPort");
        this.validator = new SegmentValidator(authorizationService);
        this.planner = new SegmentQueryPlanner(authorizationService);
        this.sqlCompiler = new SqlCompiler();
    }

    /** Runs the complete deterministic slice for one request and returns a redacted result. */
    public DevelopmentAiToSqlResult invoke(DevelopmentAiToSqlRequest request) {
        return invoke(request, null);
    }

    /**
     * Runs the slice and optionally continues through exact host-selected Metadata clarification options.
     *
     * <p>The selector is never the model. It receives only the current permitted option set and may return
     * one opaque option id. With no answer, or for a non-definition ambiguity, execution stops safely at
     * {@code NEEDS_INPUT} and returns that clarification without a DSL or SQL artifact.
     */
    public DevelopmentAiToSqlResult invoke(
            DevelopmentAiToSqlRequest request,
            ClarificationSelectionPort selectionPort) {
        Objects.requireNonNull(request, "request");

        SegmentModelRequest modelRequest;
        try {
            modelRequest = requestFactory.build(
                    request.utterance(), request.localeTag(), request.permittedSnapshot(),
                    request.resolutionPolicy(), request.trustedContext().snapshotRef().asOf(),
                    request.trustedContext().snapshotRef().timezone(), request.registeredDefaults());
        } catch (SegmentAdapterException failure) {
            return DevelopmentAiToSqlResult.failed(Disposition.INVALID, failure.code(), null);
        }

        byte[] raw;
        try {
            raw = modelPort.invoke(modelRequest);
        } catch (RuntimeException failure) {
            return DevelopmentAiToSqlResult.failed(
                    Disposition.INVALID, SegmentAdapterErrorCodes.PROVIDER_FAILED, null);
        }

        SegmentIntent intent;
        try {
            intent = outputGuard.parse(raw, request.utterance(), request.registeredDefaults());
        } catch (SegmentAdapterException failure) {
            return DevelopmentAiToSqlResult.failed(Disposition.INVALID, failure.code(), null);
        }

        var intentHash = SegmentIntentHasher.hash(intent);

        ResolutionResult resolution;
        try {
            IntentResolver resolver = new IntentResolver(
                    request.permittedSnapshot(), request.resolutionPolicy(), request.registeredDefaults());
            resolution = selectionPort == null
                    ? resolver.resolve(intent)
                    : new ClarificationChoiceService(resolver).continueResolution(intent, selectionPort);
        } catch (ResolutionException failure) {
            return DevelopmentAiToSqlResult.failed(
                    Disposition.INVALID, DevelopmentAiToSqlErrorCodes.MODEL_OUTPUT_REJECTED, intentHash);
        }
        if (!resolution.isResolved()) {
            return DevelopmentAiToSqlResult.failed(
                    resolution.disposition(), DevelopmentAiToSqlErrorCodes.RESOLUTION_INCOMPLETE,
                    intentHash, resolution.clarification());
        }

        var contextRef = request.trustedContext().snapshotRef();
        DslCompilationResult compilation = new ResolvedIntentDslCompiler(
                request.resolutionPolicy(), contextRef.asOf(), contextRef.timezone())
                .compile(intent, resolution, request.permittedSnapshot(), request.registeredDefaults());
        if (!compilation.isResolved()) {
            return DevelopmentAiToSqlResult.failed(
                    compilation.disposition(), compilation.failureCode(), intentHash);
        }

        SegmentValidationResult validation;
        try {
            validation = validator.validate(
                    compilation.document(), request.permittedSnapshot(), request.trustedContext(),
                    request.validationPolicy());
        } catch (RuntimeException failure) {
            return DevelopmentAiToSqlResult.failed(
                    Disposition.INVALID, DevelopmentAiToSqlErrorCodes.VALIDATION_REJECTED, intentHash);
        }
        if (!validation.valid()) {
            boolean denied = validation.issues().stream()
                    .anyMatch(issue -> SegmentValidationErrorCodes.RESOURCE_NOT_ACCESSIBLE.equals(issue.code()));
            return DevelopmentAiToSqlResult.failed(
                    denied ? Disposition.DENIED : Disposition.INVALID,
                    denied
                            ? DevelopmentAiToSqlErrorCodes.AUTHORIZATION_DENIED
                            : DevelopmentAiToSqlErrorCodes.VALIDATION_REJECTED,
                    intentHash);
        }

        SemanticQueryPlan plan;
        try {
            plan = planner.plan(validation, request.permittedSnapshot(), request.trustedContext());
        } catch (QueryException failure) {
            return DevelopmentAiToSqlResult.failed(
                    Disposition.INVALID, DevelopmentAiToSqlErrorCodes.PLANNING_FAILED, intentHash);
        }

        CompiledSql compiled;
        try {
            compiled = sqlCompiler.compile(
                    plan, request.storageCatalog(), request.sqlCompilePolicy(), request.fxRateBinding());
        } catch (SqlException failure) {
            return DevelopmentAiToSqlResult.failed(
                    Disposition.INVALID, DevelopmentAiToSqlErrorCodes.SQL_COMPILATION_FAILED, intentHash);
        }

        return DevelopmentAiToSqlResult.compiled(
                intentHash,
                compilation.document().editableDslHash(),
                validation.canonicalAstHash(),
                plan.planHash(),
                plan.dataScopePredicate() != null,
                compiled.sql(),
                redact(compiled.parameters()),
                compiled.catalogVersion(),
                compiled.catalogHash(),
                compiled.compiledHash(),
                compiled.compilerVersion(),
                compiled.astVersion(),
                compiled.renderFormatVersion());
    }

    private static List<ParameterBinding> redact(List<SqlParameter> parameters) {
        List<ParameterBinding> bindings = new ArrayList<>(parameters.size());
        for (SqlParameter parameter : parameters) {
            if (parameter.type() == SqlParameterType.CONTEXT_ID) {
                bindings.add(new ParameterBinding(parameter.type(), null, true));
            } else {
                bindings.add(new ParameterBinding(parameter.type(), parameter.canonicalValue(), false));
            }
        }
        return bindings;
    }
}
