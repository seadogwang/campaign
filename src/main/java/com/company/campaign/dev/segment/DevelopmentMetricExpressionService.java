package com.company.campaign.dev.segment;

import com.company.campaign.builder.segment.DevelopmentAiToSqlRequest;
import com.company.campaign.builder.segment.MetricExpressionPromptContract;
import com.company.campaign.builder.segment.SegmentModelRequest;
import com.company.campaign.builder.segment.SegmentModelRequestFactory;
import com.company.campaign.contracts.Disposition;
import com.company.campaign.metadata.MetadataKind;
import com.company.campaign.metadata.domain.SubjectKind;
import com.company.campaign.query.QueryException;
import com.company.campaign.query.plan.SegmentQueryPlanner;
import com.company.campaign.query.sql.CompiledSql;
import com.company.campaign.query.sql.SqlCompiler;
import com.company.campaign.query.sql.SqlException;
import com.company.campaign.query.sql.SqlParameter;
import com.company.campaign.query.sql.SqlParameterType;
import com.company.campaign.security.authorization.AuthorizationService;
import com.company.campaign.segment.dsl.codec.SegmentDslCodec;
import com.company.campaign.segment.dsl.contract.MetadataBinding;
import com.company.campaign.segment.dsl.contract.SegmentDsl;
import com.company.campaign.segment.dsl.contract.SegmentDslException;
import com.company.campaign.segment.dsl.contract.SegmentDslSchemaVersions;
import com.company.campaign.segment.dsl.predicate.MetricExpressionPredicate;
import com.company.campaign.segment.dsl.predicate.Predicate;
import com.company.campaign.segment.validation.SegmentValidationResult;
import com.company.campaign.segment.validation.SegmentValidator;
import com.company.campaign.shared.canonical.CanonicalJson;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Development-only model-to-typed-expression-to-SQL slice after the user clarifies a metric definition. */
public final class DevelopmentMetricExpressionService {

    private static final CanonicalJson JSON = new CanonicalJson();

    private final DevelopmentStructuredModelPort modelPort;
    private final AuthorizationService authorizationService;
    private final SegmentModelRequestFactory requestFactory = new SegmentModelRequestFactory();
    private final SegmentDslCodec dslCodec = new SegmentDslCodec();

    public DevelopmentMetricExpressionService(
            DevelopmentStructuredModelPort modelPort,
            AuthorizationService authorizationService) {
        this.modelPort = Objects.requireNonNull(modelPort, "modelPort");
        this.authorizationService = authorizationService;
    }

    public DevelopmentMetricExpressionResult compile(
            DevelopmentAiToSqlRequest request,
            String clarifiedCalculation) {
        Objects.requireNonNull(request, "request");
        if (clarifiedCalculation == null || clarifiedCalculation.isBlank()) {
            return failed("METRIC_EXPRESSION_DESCRIPTION_MISSING");
        }

        Predicate predicate;
        String predicateJson;
        try {
            predicateJson = decode(modelPort.invokeStructured(prompt(request, clarifiedCalculation)));
            predicate = dslCodec.readPredicate(predicateJson);
        } catch (RuntimeException failure) {
            return failed("METRIC_EXPRESSION_MODEL_OUTPUT_INVALID");
        }
        if (!(predicate instanceof MetricExpressionPredicate)) {
            return failed("METRIC_EXPRESSION_MODEL_OUTPUT_INVALID");
        }

        SegmentDsl document = SegmentDsl.publish(
                SegmentDslSchemaVersions.SUPPORTED,
                SubjectKind.MEMBER,
                MetadataBinding.fromSnapshot(request.permittedSnapshot()),
                predicate);
        SegmentValidationResult validation = new SegmentValidator(authorizationService).validate(
                document,
                request.permittedSnapshot(),
                request.trustedContext(),
                request.validationPolicy());
        if (!validation.valid()) {
            return failed("METRIC_EXPRESSION_VALIDATION_FAILED");
        }

        try {
            var plan = new SegmentQueryPlanner(authorizationService).plan(
                    validation, request.permittedSnapshot(), request.trustedContext());
            CompiledSql compiled = new SqlCompiler().compile(
                    plan, request.storageCatalog(), request.sqlCompilePolicy(), request.fxRateBinding());
            return new DevelopmentMetricExpressionResult(
                    Disposition.RESOLVED,
                    null,
                    dslCodec.writePredicateString(predicate),
                    document.editableDslHash(),
                    validation.canonicalAstHash(),
                    plan.planHash(),
                    compiled.sql(),
                    redact(compiled.parameters()),
                    compiled.compiledHash(),
                    MetricExpressionPromptContract.VERSION,
                    MetricExpressionPromptContract.HASH.value());
        } catch (QueryException | SqlException failure) {
            return failed("METRIC_EXPRESSION_COMPILATION_FAILED");
        }
    }

    String prompt(DevelopmentAiToSqlRequest request, String clarifiedCalculation) {
        SegmentModelRequest projection = requestFactory.build(
                request.utterance(), request.localeTag(), request.permittedSnapshot(),
                request.resolutionPolicy(), request.trustedContext().snapshotRef().asOf(),
                request.trustedContext().snapshotRef().timezone(), request.registeredDefaults());
        List<SegmentModelRequest.MetadataProjection> metrics = projection.permittedDefinitions().stream()
                .filter(definition -> definition.kind() == MetadataKind.METRIC)
                .toList();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("utterance", request.utterance());
        payload.put("clarifiedCalculation", clarifiedCalculation);
        payload.put("asOf", request.trustedContext().snapshotRef().asOf().toString());
        payload.put("timezone", request.trustedContext().snapshotRef().timezone().getId());
        payload.put("reportingCurrency", request.trustedContext().snapshotRef().reportingCurrency());
        payload.put("metadataSnapshot", projection.metadataSnapshot());
        payload.put("permittedMetrics", metrics);
        return MetricExpressionPromptContract.TEXT
                + "\nPrompt contract version: " + MetricExpressionPromptContract.VERSION
                + "\nPrompt contract hash: " + MetricExpressionPromptContract.HASH.value()
                + "\nInput: " + JSON.writeString(payload);
    }

    private DevelopmentMetricExpressionResult failed(String code) {
        return new DevelopmentMetricExpressionResult(
                Disposition.INVALID,
                code,
                null,
                null,
                null,
                null,
                null,
                List.of(),
                null,
                MetricExpressionPromptContract.VERSION,
                MetricExpressionPromptContract.HASH.value());
    }

    private static List<DevelopmentMetricExpressionResult.ParameterBinding> redact(List<SqlParameter> parameters) {
        List<DevelopmentMetricExpressionResult.ParameterBinding> result = new ArrayList<>(parameters.size());
        for (SqlParameter parameter : parameters) {
            boolean redacted = parameter.type() == SqlParameterType.CONTEXT_ID;
            result.add(new DevelopmentMetricExpressionResult.ParameterBinding(
                    parameter.type(), redacted ? null : parameter.canonicalValue(), redacted));
        }
        return List.copyOf(result);
    }

    private static String decode(byte[] bytes) {
        if (bytes == null) {
            throw new SegmentDslException("SEGMENT_DSL_JSON_MALFORMED", "A model response is required");
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException failure) {
            throw new SegmentDslException("SEGMENT_DSL_JSON_MALFORMED", "A model response must be UTF-8");
        }
    }
}
