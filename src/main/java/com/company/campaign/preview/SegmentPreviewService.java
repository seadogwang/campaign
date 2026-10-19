package com.company.campaign.preview;

import com.company.campaign.metadata.domain.MetadataSnapshot;
import com.company.campaign.metadata.repository.MetadataSnapshotRepository;
import com.company.campaign.query.QueryException;
import com.company.campaign.query.plan.SemanticQueryPlan;
import com.company.campaign.query.plan.SegmentQueryPlanner;
import com.company.campaign.segment.dsl.codec.SegmentDslCodec;
import com.company.campaign.segment.dsl.contract.MetadataBinding;
import com.company.campaign.segment.dsl.contract.SegmentDsl;
import com.company.campaign.segment.validation.SegmentValidationPolicy;
import com.company.campaign.segment.validation.SegmentValidationResult;
import com.company.campaign.segment.validation.SegmentValidator;
import com.company.campaign.security.authorization.AuthorizationDecision;
import com.company.campaign.security.authorization.AuthorizationDisposition;
import com.company.campaign.security.authorization.AuthorizationReason;
import com.company.campaign.security.authorization.AuthorizationRequest;
import com.company.campaign.security.authorization.AuthorizationService;
import com.company.campaign.security.authorization.OwnedResourceRef;
import com.company.campaign.security.authorization.ResourceKind;
import com.company.campaign.security.context.CampaignPermission;
import com.company.campaign.security.context.TrustedContextProvider;
import com.company.campaign.security.context.TrustedExecutionContext;
import com.company.campaign.shared.canonical.ContentHash;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * The mandatory trusted orchestration of the v1.3 read-only Segment Preview.
 *
 * <p>It is immutable and thread-safe. Every dependency is constructor-injected and mandatory — there is no
 * no-argument, permissive or default constructor — so a service that cannot resolve trusted context, load the
 * exact Metadata Snapshot, validate, plan, execute read-only or authorize a sample cannot exist.
 *
 * <p>For one call it resolves {@link TrustedContextProvider#trustedContext()} exactly once and retains that
 * immutable result through the whole flow. It never reads a clock, the JVM default timezone or locale, a random
 * source, ambient thread-local state or a global security holder, and it never accepts a trusted fact, plan,
 * SQL, catalog identifier, fixture path, FX input, authorization result, snapshot override or backend
 * selection from the request.
 *
 * <p>The required order is enforced literally: resolve context; decode the strict request through the accepted
 * T05 codec; require the DSL snapshot identifier to equal the context pin and resolve exactly one immutable
 * snapshot through the repository's exact-pin lookup; validate through the genuine T07 validator; reject an
 * invalid result with no partial AST or plan; plan through the genuine T08 planner; capture the one immutable
 * backend identity; execute through the injected read-only backend; re-prove backend result coherence
 * including both identity values; enforce freshness and response limits; and only then perform the separate
 * sample authorization and project the safe response.
 *
 * <p>Every dependency failure is translated into one stable bounded non-disclosing {@link PreviewException}
 * that echoes no raw identifier, operand, SQL fragment, bind parameter, member identifier, fixture fact, path,
 * exception message or cause.
 */
public final class SegmentPreviewService {

    private static final String STATUS_SUCCESS = "SUCCESS";
    private static final String PLAN_ID_PREFIX = "qp-";
    private static final String SAMPLE_RESOURCE_PREFIX = "preview-sample-";

    private final TrustedContextProvider trustedContextProvider;
    private final MetadataSnapshotRepository metadataSnapshotRepository;
    private final SegmentDslCodec segmentDslCodec;
    private final SegmentValidator segmentValidator;
    private final SegmentValidationPolicy validationPolicy;
    private final SegmentQueryPlanner segmentQueryPlanner;
    private final PreviewExecutionBackend executionBackend;
    private final AuthorizationService authorizationService;
    private final PreviewPolicy previewPolicy;
    private final PreviewSampleHasher sampleHasher;
    private final PreviewRequestCodec requestCodec;

    /**
     * Creates the one Preview orchestrator.
     *
     * <p>Every argument is mandatory; a null dependency fails construction with a stable bounded code rather
     * than producing a service that could skip a boundary at call time.
     */
    public SegmentPreviewService(
            TrustedContextProvider trustedContextProvider,
            MetadataSnapshotRepository metadataSnapshotRepository,
            SegmentDslCodec segmentDslCodec,
            SegmentValidator segmentValidator,
            SegmentValidationPolicy validationPolicy,
            SegmentQueryPlanner segmentQueryPlanner,
            PreviewExecutionBackend executionBackend,
            AuthorizationService authorizationService,
            PreviewPolicy previewPolicy,
            PreviewSampleHasher sampleHasher) {
        this.trustedContextProvider = require(trustedContextProvider, "trustedContextProvider");
        this.metadataSnapshotRepository = require(metadataSnapshotRepository, "metadataSnapshotRepository");
        this.segmentDslCodec = require(segmentDslCodec, "segmentDslCodec");
        this.segmentValidator = require(segmentValidator, "segmentValidator");
        this.validationPolicy = require(validationPolicy, "validationPolicy");
        this.segmentQueryPlanner = require(segmentQueryPlanner, "segmentQueryPlanner");
        this.executionBackend = require(executionBackend, "executionBackend");
        this.authorizationService = require(authorizationService, "authorizationService");
        this.previewPolicy = require(previewPolicy, "previewPolicy");
        this.sampleHasher = require(sampleHasher, "sampleHasher");
        this.requestCodec = new PreviewRequestCodec(previewPolicy, segmentDslCodec);
    }

    /** Runs one complete read-only Preview call over the exact request bytes. */
    public PreviewResponse preview(byte[] requestBytes) {
        // 1. Resolve the trusted context exactly once and retain the immutable result for the whole flow.
        TrustedExecutionContext context = resolveTrustedContext();

        // 2. Decode the strict request and the accepted Segment DSL document.
        PreviewRequest request = requestCodec.parse(requestBytes);

        // 3. Resolve the one exact snapshot the DSL immutably pins, proven against the trusted context.
        MetadataSnapshot snapshot = loadPinnedSnapshot(request.segmentDsl(), context);

        // 4-5. Validate through the genuine T07 validator; an invalid result has no AST and no plan.
        SegmentValidationResult validation = validate(request.segmentDsl(), snapshot, context);

        // 6. Plan through the genuine T08 planner.
        SemanticQueryPlan plan = plan(validation, snapshot, context);

        // 7. Capture the one immutable backend identity, then execute through the injected read-only backend.
        PreviewBackendIdentity backendIdentity = resolveBackendIdentity();
        PreviewBackendResult result = execute(plan, context);

        // 8. Re-prove backend result plan/context/snapshot/time/watermark/identity/hash coherence.
        requireCoherentResult(plan, context, result, backendIdentity);

        // 9. Enforce freshness and the exact matched-count limit before projecting anything.
        requireFreshAndWithinLimits(result);

        // 10. Perform the separate sample authorization only when requested, then project the safe response.
        List<String> sample = request.presentation().includeSample()
                ? authorizedSample(plan, context, result)
                : null;
        return project(validation, plan, result, request.presentation(), sample);
    }

    // ------------------------------------------------------------------ step 1: trusted context

    private TrustedExecutionContext resolveTrustedContext() {
        TrustedExecutionContext context;
        try {
            context = trustedContextProvider.trustedContext();
        } catch (RuntimeException exception) {
            throw PreviewException.of(PreviewErrorCodes.TRUSTED_CONTEXT_UNAVAILABLE, "trustedContext");
        }
        if (context == null || context.snapshotRef() == null) {
            throw PreviewException.of(PreviewErrorCodes.TRUSTED_CONTEXT_UNAVAILABLE, "trustedContext");
        }
        return context;
    }

    // ------------------------------------------------------------------ step 3: snapshot

    /**
     * Resolves the one exact immutable snapshot the DSL pins, using the repository's exact-pin lookup only.
     *
     * <p>The DSL snapshot identifier must first equal the trusted context snapshot identifier, then exactly one
     * {@link MetadataSnapshotRepository#findByPin} call is made with the DSL's complete binding. There is no
     * {@code findById}, latest, content-only fallback or second lookup: a null {@code Optional}, a missing
     * exact pin, a conflicting owner or a returned snapshot that disagrees with the binding fails closed with
     * the existing bounded non-disclosing code and no validator or backend call.
     */
    private MetadataSnapshot loadPinnedSnapshot(SegmentDsl document, TrustedExecutionContext context) {
        MetadataBinding binding = document.metadata();
        if (binding == null || !binding.snapshotId().equals(context.snapshotRef().metadataSnapshotId())) {
            throw PreviewException.of(PreviewErrorCodes.METADATA_BINDING_MISMATCH, "metadataBinding");
        }
        Optional<MetadataSnapshot> found;
        try {
            found = metadataSnapshotRepository.findByPin(
                    binding.snapshotId(), binding.snapshotVersion(), binding.contentHash());
        } catch (RuntimeException exception) {
            throw PreviewException.of(PreviewErrorCodes.METADATA_UNAVAILABLE, "metadataRepository");
        }
        MetadataSnapshot snapshot = found == null ? null : found.orElse(null);
        if (snapshot == null) {
            throw PreviewException.of(PreviewErrorCodes.METADATA_SNAPSHOT_MISSING, "metadataSnapshot");
        }
        if (!snapshot.tenantId().equals(context.tenantId())
                || !snapshot.programId().equals(context.programId())) {
            // Conflicting ownership is outwardly indistinguishable from a missing snapshot.
            throw PreviewException.of(PreviewErrorCodes.METADATA_OWNER_MISMATCH, "metadataOwner");
        }
        if (!snapshot.snapshotId().equals(binding.snapshotId())
                || !snapshot.snapshotVersion().equals(binding.snapshotVersion())
                || !snapshot.schemaVersion().equals(binding.metadataSchemaVersion())
                || !snapshot.contentHash().equals(binding.contentHash())) {
            // A repository that returned a snapshot other than the exact pin must never be resolved against.
            throw PreviewException.of(PreviewErrorCodes.METADATA_BINDING_MISMATCH, "metadataBinding");
        }
        return snapshot;
    }

    // ------------------------------------------------------------------ steps 4-5: validation

    private SegmentValidationResult validate(
            SegmentDsl document, MetadataSnapshot snapshot, TrustedExecutionContext context) {
        SegmentValidationResult result;
        try {
            result = segmentValidator.validate(document, snapshot, context, validationPolicy);
        } catch (RuntimeException exception) {
            throw PreviewException.of(PreviewErrorCodes.VALIDATION_FAILED, "validation");
        }
        if (result == null || !result.valid() || result.canonicalAstHash() == null) {
            // No partial AST, no plan and no backend call are ever produced from an invalid document.
            throw PreviewException.of(PreviewErrorCodes.VALIDATION_REJECTED, "validation");
        }
        return result;
    }

    // ------------------------------------------------------------------ step 6: planning

    private SemanticQueryPlan plan(
            SegmentValidationResult validation, MetadataSnapshot snapshot, TrustedExecutionContext context) {
        SemanticQueryPlan plan;
        try {
            plan = segmentQueryPlanner.plan(validation, snapshot, context);
        } catch (QueryException exception) {
            throw PreviewException.of(PreviewErrorCodes.PLANNING_REJECTED, "planning");
        } catch (RuntimeException exception) {
            throw PreviewException.of(PreviewErrorCodes.PLANNING_FAILED, "planning");
        }
        if (plan == null) {
            throw PreviewException.of(PreviewErrorCodes.PLANNING_REJECTED, "planning");
        }
        return plan;
    }

    // ------------------------------------------------------------------ step 7: execution

    /**
     * Captures the one immutable server-owned backend identity through a bounded boundary.
     *
     * <p>A throwing identity method or a null identity never escapes as a raw exception: it becomes one stable
     * bounded code before any execution is attempted.
     */
    private PreviewBackendIdentity resolveBackendIdentity() {
        PreviewBackendIdentity identity;
        try {
            identity = executionBackend.identity();
        } catch (RuntimeException exception) {
            throw PreviewException.of(PreviewErrorCodes.BACKEND_FAILED, "backendIdentity");
        }
        if (identity == null) {
            throw PreviewException.of(PreviewErrorCodes.EXECUTION_RESULT_INCOHERENT, "backendIdentity");
        }
        return identity;
    }

    private PreviewBackendResult execute(SemanticQueryPlan plan, TrustedExecutionContext context) {
        PreviewBackendResult result;
        try {
            result = executionBackend.execute(plan, context);
        } catch (PreviewBackendException exception) {
            throw mapBackendFailure(exception.kind());
        } catch (RuntimeException exception) {
            throw PreviewException.of(PreviewErrorCodes.BACKEND_FAILED, "backend");
        }
        if (result == null) {
            throw PreviewException.of(PreviewErrorCodes.BACKEND_FAILED, "backend");
        }
        return result;
    }

    private static PreviewException mapBackendFailure(PreviewBackendException.Kind kind) {
        return switch (kind) {
            case UNAVAILABLE -> PreviewException.of(PreviewErrorCodes.BACKEND_UNAVAILABLE, "backend");
            case TIMEOUT -> PreviewException.of(PreviewErrorCodes.BACKEND_TIMEOUT, "backend");
            case INVALID_RESULT -> PreviewException.of(
                    PreviewErrorCodes.EXECUTION_RESULT_INCOHERENT, "backendResult");
        };
    }

    // ------------------------------------------------------------------ step 8: coherence

    private void requireCoherentResult(
            SemanticQueryPlan plan,
            TrustedExecutionContext context,
            PreviewBackendResult result,
            PreviewBackendIdentity backendIdentity) {
        if (!result.planHash().equals(plan.planHash())) {
            throw PreviewException.of(PreviewErrorCodes.EXECUTION_RESULT_INCOHERENT, "planHash");
        }
        if (!result.metadataSnapshotId().equals(plan.metadataSnapshotId())
                || !result.metadataSnapshotId().equals(context.snapshotRef().metadataSnapshotId())) {
            throw PreviewException.of(PreviewErrorCodes.EXECUTION_RESULT_INCOHERENT, "metadataSnapshotId");
        }
        if (!result.dataScopeId().equals(plan.dataScopePredicate().dataScopeId())) {
            throw PreviewException.of(PreviewErrorCodes.EXECUTION_RESULT_INCOHERENT, "dataScopeId");
        }
        // A reference-bearing plan is deliberately evaluated under a narrower effective intersection than the
        // caller's own scope, so only a reference-free plan must report exactly the caller's scope back.
        if (!plan.dataScopePredicate().isEffectiveIntersection()
                && !result.dataScopeId().equals(context.dataScopeId())) {
            throw PreviewException.of(PreviewErrorCodes.EXECUTION_RESULT_INCOHERENT, "dataScopeId");
        }
        if (!result.asOf().equals(context.snapshotRef().asOf())
                || !result.dataWatermark().equals(context.snapshotRef().dataWatermark())
                || !result.timezone().equals(context.snapshotRef().timezone().getId())) {
            throw PreviewException.of(PreviewErrorCodes.EXECUTION_RESULT_INCOHERENT, "timeContext");
        }
        if (!result.backendId().equals(backendIdentity.id())
                || !result.backendVersion().equals(backendIdentity.version())) {
            // Both the identity and the exact version must equal the captured server-owned backend identity.
            throw PreviewException.of(PreviewErrorCodes.EXECUTION_RESULT_INCOHERENT, "backendIdentity");
        }
        for (PreviewConditionStatistic statistic : result.conditionStatistics()) {
            if (statistic == null) {
                throw PreviewException.of(PreviewErrorCodes.EXECUTION_RESULT_INCOHERENT, "conditionStatistic");
            }
            // Re-prove the exact structural-path grammar here too: a backend that bypassed the record's own
            // constructor must not make a member identifier, Metadata identifier, SQL fragment or secret-like
            // value response-visible merely because its result hash stays self-consistent.
            PreviewConditionStatistic.requireValidStructuralPath(statistic.path());
        }
        if (!result.resultHash().equals(ContentHash.sha256(result.canonicalBytes()))) {
            // The declared result hash must be the canonical hash of the result's own material.
            throw PreviewException.of(PreviewErrorCodes.EXECUTION_RESULT_INCOHERENT, "resultHash");
        }
    }

    // ------------------------------------------------------------------ step 9: freshness and limits

    private void requireFreshAndWithinLimits(PreviewBackendResult result) {
        Duration age = Duration.between(result.dataWatermark(), result.asOf());
        if (age.compareTo(previewPolicy.maxDataAge()) > 0) {
            // Never fabricate a count for stale data: the whole call fails with one stable code.
            throw PreviewException.of(PreviewErrorCodes.DATA_STALE, "freshness");
        }
        if (result.matchedCount() > previewPolicy.maxMatchedCount()) {
            throw PreviewException.of(PreviewErrorCodes.RESULT_LIMIT_EXCEEDED, "matchedCount");
        }
    }

    // ------------------------------------------------------------------ step 10: sample authorization

    /**
     * Authorizes exactly one server-created {@code PREVIEW_SAMPLE} reference and returns the bounded sample.
     *
     * <p>A null, malformed, wrong-kind, wrong-permission or non-{@code ALLOWED} decision — and any thrown
     * boundary failure — fails closed and returns no sample. The reference is owned by the trusted
     * tenant/program and its identifier is derived server-side from the plan hash, so no client input reaches
     * the authorization boundary.
     */
    private List<String> authorizedSample(
            SemanticQueryPlan plan, TrustedExecutionContext context, PreviewBackendResult result) {
        OwnedResourceRef resource = new OwnedResourceRef(
                ResourceKind.PREVIEW_SAMPLE,
                SAMPLE_RESOURCE_PREFIX + hashHex(plan.planHash()),
                context.tenantId(),
                context.programId());
        AuthorizationDecision decision;
        try {
            decision = authorizationService.authorize(AuthorizationRequest.of(context, resource));
        } catch (RuntimeException exception) {
            return null;
        }
        if (!isExactSampleApproval(decision)) {
            return null;
        }

        List<String> hashes = new ArrayList<>(result.memberIdSet().size());
        for (String memberId : result.memberIdSet().memberIds()) {
            hashes.add(sampleHasher
                    .hash(context.tenantId(), context.programId(), context.dataScopeId(), memberId)
                    .value());
        }
        hashes.sort(Comparator.naturalOrder());
        if (hashes.size() > previewPolicy.maxSampleSize()) {
            hashes = new ArrayList<>(hashes.subList(0, previewPolicy.maxSampleSize()));
        }
        return List.copyOf(hashes);
    }

    /** True only for a well-formed approval of exactly PREVIEW_SAMPLE with AUDIENCE_PREVIEW. */
    private static boolean isExactSampleApproval(AuthorizationDecision decision) {
        if (decision == null
                || decision.disposition() != AuthorizationDisposition.ALLOWED
                || !decision.allowed()
                || decision.reason() != AuthorizationReason.ALLOWED
                || decision.resourceKind() != ResourceKind.PREVIEW_SAMPLE) {
            return false;
        }
        return decision.requiredPermission() == CampaignPermission.AUDIENCE_PREVIEW;
    }

    // ------------------------------------------------------------------ projection

    private PreviewResponse project(
            SegmentValidationResult validation,
            SemanticQueryPlan plan,
            PreviewBackendResult result,
            PreviewPresentation presentation,
            List<String> sample) {
        List<PreviewConditionStatistic> statistics = presentation.includeConditionStats()
                ? boundedStatistics(result)
                : null;
        PreviewExecutionContext executionContext = new PreviewExecutionContext(
                result.metadataSnapshotId(),
                result.asOf().toString(),
                result.timezone(),
                result.dataWatermark().toString());
        return new PreviewResponse(
                STATUS_SUCCESS,
                validation.canonicalAstHash().value(),
                PLAN_ID_PREFIX + hashHex(plan.planHash()),
                plan.planHash().value(),
                result.matchedCount(),
                statistics,
                sample,
                executionContext);
    }

    private List<PreviewConditionStatistic> boundedStatistics(PreviewBackendResult result) {
        List<PreviewConditionStatistic> statistics = new ArrayList<>(result.conditionStatistics());
        statistics.sort(Comparator.comparing(PreviewConditionStatistic::path));
        // The server-owned policy bound is applied here; the client flag can only select inclusion.
        if (statistics.size() > previewPolicy.maxConditionStats()) {
            statistics = new ArrayList<>(statistics.subList(0, previewPolicy.maxConditionStats()));
        }
        return List.copyOf(statistics);
    }

    private static String hashHex(ContentHash hash) {
        return hash.value().substring(ContentHashPrefix.LENGTH);
    }

    private static <T> T require(T dependency, String invariant) {
        if (dependency == null) {
            throw PreviewException.of(PreviewErrorCodes.DEPENDENCY_MISSING, invariant);
        }
        return dependency;
    }

    /** The one canonical hash prefix length, so a plan id is the full 64-character digest. */
    private static final class ContentHashPrefix {
        private static final int LENGTH = "sha256:".length();

        private ContentHashPrefix() {
        }
    }
}