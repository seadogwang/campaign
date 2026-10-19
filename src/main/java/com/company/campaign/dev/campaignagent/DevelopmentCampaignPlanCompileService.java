package com.company.campaign.dev.campaignagent;

import com.company.campaign.builder.authoring.SegmentAuthoringVersions;
import com.company.campaign.builder.campaignagent.CampaignCompileEvidence;
import com.company.campaign.builder.campaignagent.CampaignCompileEvidenceRegistry;
import com.company.campaign.builder.campaignagent.http.CampaignPlanCompileService;
import com.company.campaign.builder.campaignagent.http.CampaignPlanHttpErrorCodes;
import com.company.campaign.builder.campaignagent.http.CampaignPlanHttpException;
import com.company.campaign.builder.campaignagent.http.CampaignPlanSummaryResponse;
import com.company.campaign.builder.capability.composition.T19PackIndex;
import com.company.campaign.builder.compiler.CampaignCompiler;
import com.company.campaign.builder.compiler.CampaignPlan;
import com.company.campaign.builder.compiler.CampaignPlanStore;
import com.company.campaign.builder.compiler.CapabilityCompileContext;
import com.company.campaign.builder.compiler.CompileInstants;
import com.company.campaign.builder.compiler.CompileRequest;
import com.company.campaign.builder.compiler.CompilerErrorCodes;
import com.company.campaign.builder.compiler.CompilerException;
import com.company.campaign.builder.compiler.FrozenAuthoringPolicy;
import com.company.campaign.builder.compiler.FrozenCapabilityCatalog;
import com.company.campaign.persistence.PostgresCampaignPlanStore;
import com.company.campaign.persistence.PostgresCampaignPlanStore.IdempotencyBinding;
import com.company.campaign.persistence.PostgresCampaignPlanStore.IdempotencyResult;
import com.company.campaign.builder.proposal.CampaignProposalCodec;
import com.company.campaign.builder.proposal.CampaignProposalPatch;
import com.company.campaign.builder.proposal.ProposalErrorCodes;
import com.company.campaign.builder.proposal.ProposalException;
import com.company.campaign.builder.proposal.ProposalOperation;
import com.company.campaign.security.context.TrustedExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * The development-only compile service of the local Campaign Agent host.
 *
 * <p>It decodes the raw body through the strict {@link CampaignProposalCodec}, resolves the trusted
 * context from the development identity provider, freezes the compile instants from the host clock,
 * compiles through the genuine {@link CampaignCompiler} pinned to the frozen development
 * catalog/policy, and stores the resulting Plan through the durable
 * {@link PostgresCampaignPlanStore}.
 *
 * <p>There is no in-memory idempotency or in-memory compile path: the service is only wired when
 * PostgreSQL mode is explicitly enabled and the shared {@code campaignBusinessSqlSessionFactory}
 * is available.
 *
 * <p>Idempotency is enforced with a read-before-compile check: an existing key with the same
 * proposal hash replays the stored plan from the database without re-compiling; the same key with
 * a different proposal hash is a durable conflict reported before any compilation work.
 *
 * <p>Plan storage and idempotency binding are atomic: when a new key is compiled, the immutable
 * plan and the idempotency-key record are persisted within one {@code SqlSession}/transaction
 * boundary, so a partial success cannot leave an orphan plan row.
 *
 * <p>This service is <strong>development evidence only</strong>: it delivers no production identity,
 * no production Plan persistence and no production compile readiness level.
 */
public final class DevelopmentCampaignPlanCompileService implements CampaignPlanCompileService {

    private static final Logger LOG = LoggerFactory.getLogger(
            DevelopmentCampaignPlanCompileService.class);

    private static final CampaignProposalCodec CODEC = new CampaignProposalCodec();

    /** The longest a Plan may live after compilation in dev mode. */
    private static final Duration DEV_PLAN_LIFETIME = Duration.ofHours(1);

    private final CampaignCompiler compiler;
    private final PostgresCampaignPlanStore planStore;
    private final DevelopmentCampaignAgentIdentity identity;
    private final FrozenCapabilityCatalog catalog;
    private final FrozenAuthoringPolicy policy;
    private final Clock clock;
    private final CampaignCompileEvidenceRegistry evidenceRegistry;
    private final T19PackIndex journeyPackIndex;

    public DevelopmentCampaignPlanCompileService(
            CampaignCompiler compiler,
            CampaignPlanStore planStore,
            DevelopmentCampaignAgentIdentity identity,
            FrozenCapabilityCatalog catalog,
            FrozenAuthoringPolicy policy,
            Clock clock) {
        this(compiler, planStore, identity, catalog, policy, clock, null);
    }

    public DevelopmentCampaignPlanCompileService(
            CampaignCompiler compiler,
            CampaignPlanStore planStore,
            DevelopmentCampaignAgentIdentity identity,
            FrozenCapabilityCatalog catalog,
            FrozenAuthoringPolicy policy,
            Clock clock,
            CampaignCompileEvidenceRegistry evidenceRegistry) {
        this(compiler, planStore, identity, catalog, policy, clock, evidenceRegistry,
                DevelopmentCampaignAgentAssets.journeyPackIndex());
    }

    public DevelopmentCampaignPlanCompileService(
            CampaignCompiler compiler,
            CampaignPlanStore planStore,
            DevelopmentCampaignAgentIdentity identity,
            FrozenCapabilityCatalog catalog,
            FrozenAuthoringPolicy policy,
            Clock clock,
            CampaignCompileEvidenceRegistry evidenceRegistry,
            T19PackIndex journeyPackIndex) {
        this.compiler = Objects.requireNonNull(compiler, "compiler");
        if (!(planStore instanceof PostgresCampaignPlanStore pgStore)) {
            throw new CampaignPlanHttpException(
                    CampaignPlanHttpErrorCodes.INTERNAL_FAILURE,
                    "The Campaign Plan compile service requires the durable PostgreSQL store.");
        }
        this.planStore = pgStore;
        this.identity = Objects.requireNonNull(identity, "identity");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.evidenceRegistry = evidenceRegistry;
        this.journeyPackIndex = Objects.requireNonNull(journeyPackIndex, "journeyPackIndex");
    }

    @Override
    public CampaignPlanSummaryResponse compile(byte[] body, String idempotencyKey) {
        if (body == null || body.length == 0) {
            throw new CampaignPlanHttpException(
                    CampaignPlanHttpErrorCodes.REQUEST_MALFORMED, "A proposal body is required.");
        }
        requireIdempotencyKey(idempotencyKey);

        CampaignProposalPatch patch;
        try {
            patch = CODEC.parse(body);
        } catch (ProposalException failure) {
            throw mapProposalFailure(failure);
        }

        String proposalHash = patch.proposalHash().value();

        // 1. Read-only idempotency check — never inserts a missing key.
        Optional<IdempotencyBinding> existingBinding;
        try {
            existingBinding = planStore.lookupIdempotency(idempotencyKey);
        } catch (CampaignPlanHttpException e) {
            throw e;
        } catch (RuntimeException failure) {
            throw boundedDiagnostic("idempotency-lookup", failure);
        }
        if (existingBinding.isPresent()) {
            IdempotencyBinding binding = existingBinding.get();
            if (proposalHash.equals(binding.proposalHash())) {
                // Same key + same hash: replay — return the exact already-bound plan.
                CampaignPlan existingPlan;
                try {
                    existingPlan = planStore.find(binding.planId()).orElse(null);
                } catch (CampaignPlanHttpException e) {
                    throw e;
                } catch (RuntimeException failure) {
                    throw boundedDiagnostic("stored-plan-find", failure);
                }
                if (existingPlan != null) {
                    try {
                        return CampaignPlanSummaryResponse.from(existingPlan);
                    } catch (RuntimeException failure) {
                        throw boundedDiagnostic("campaign-plan-summary-response.from", failure);
                    }
                }
                // The binding exists but the plan row is missing (should not happen with FK).
                throw new CampaignPlanHttpException(
                        CampaignPlanHttpErrorCodes.INTERNAL_FAILURE,
                        "The stored idempotency binding refers to a plan that no longer exists.");
            }
            // Same key + different hash: durable conflict.
            throw new CampaignPlanHttpException(
                    CampaignPlanHttpErrorCodes.CONFLICT,
                    "A different proposal was already compiled under this idempotency key.");
        }

        // 2. Compile the immutable plan without storing it.
        TrustedExecutionContext context = identity.context();
        Instant now = clock.instant();
        CompileInstants instants = new CompileInstants(now, now.plus(DEV_PLAN_LIFETIME));

        CampaignPlan plan;
        try {
            CompileRequest request;
            if (evidenceRegistry != null) {
                CampaignCompileEvidence evidence =
                        evidenceRegistry.lookup(proposalHash).orElse(null);
                if (evidence == null && hasSegmentOperation(patch)) {
                    throw new CompilerException(
                            CompilerErrorCodes.COMPILER_CAPABILITY_CONTRACT_INVALID,
                            "segmentEvidenceMissing",
                            "The proposal carries a segment.rule-based operation but no "
                                    + "host-owned Segment compile evidence is registered.");
                }
                CapabilityCompileContext capabilityContext = evidence != null
                        ? evidence.capabilityContext()
                        : CapabilityCompileContext.empty();
                request = CompileRequest.create(patch, catalog, policy, instants, context,
                        capabilityContext, journeyPackIndex);
            } else {
                request = CompileRequest.create(patch, catalog, policy, instants, context,
                        CapabilityCompileContext.empty(), journeyPackIndex);
            }
            plan = compiler.compile(request);
        } catch (CompilerException failure) {
            throw mapCompilerFailure(failure);
        } catch (RuntimeException failure) {
            // Bounded diagnostic: stable exception class only, never payload/secrets.
            LOG.error("Non-compiler failure during compile: exceptionClass={}",
                    failure.getClass().getName());
            throw new CampaignPlanHttpException(
                    CampaignPlanHttpErrorCodes.INTERNAL_FAILURE,
                    "The compile produced an unexpected failure.");
        }

        // 3. Atomically store plan + idempotency binding in one transaction.
        IdempotencyResult result;
        try {
            result = planStore.storePlanWithBindingAtomically(plan, idempotencyKey, proposalHash);
        } catch (CampaignPlanHttpException e) {
            throw e;
        } catch (RuntimeException failure) {
            throw boundedDiagnostic("postgresql-atomic-store", failure);
        }
        if (result.isConflict()) {
            throw new CampaignPlanHttpException(
                    CampaignPlanHttpErrorCodes.CONFLICT,
                    "A concurrent compile was already stored under this idempotency key.");
        }

        // 4. Retrieve the stored plan (may be a replay from a concurrent insert).
        String storedPlanId = result.planId();
        CampaignPlan storedPlan;
        try {
            storedPlan = planStore.find(storedPlanId)
                    .orElseThrow(() -> new CampaignPlanHttpException(
                            CampaignPlanHttpErrorCodes.INTERNAL_FAILURE,
                            "The compiled Plan is not retrievable from the store."));
        } catch (CampaignPlanHttpException e) {
            throw e;
        } catch (RuntimeException failure) {
            throw boundedDiagnostic("stored-plan-find", failure);
        }

        try {
            return CampaignPlanSummaryResponse.from(storedPlan);
        } catch (RuntimeException failure) {
            throw boundedDiagnostic("campaign-plan-summary-response.from", failure);
        }
    }

    private static void requireIdempotencyKey(String key) {
        if (key == null || key.isBlank()) {
            throw new CampaignPlanHttpException(
                    CampaignPlanHttpErrorCodes.IDEMPOTENCY_KEY_REQUIRED,
                    "A compile request requires an Idempotency-Key header.");
        }
        if (key.length() > 256) {
            throw new CampaignPlanHttpException(
                    CampaignPlanHttpErrorCodes.IDEMPOTENCY_KEY_INVALID,
                    "The Idempotency-Key exceeds its declared bound.");
        }
    }

    private static CampaignPlanHttpException mapProposalFailure(ProposalException failure) {
        String code = failure.code();
        if (code == null) {
            return new CampaignPlanHttpException(
                    CampaignPlanHttpErrorCodes.REQUEST_MALFORMED, "The proposal body is malformed.");
        }
        return switch (code) {
            case ProposalErrorCodes.PROPOSAL_MISSING,
                    ProposalErrorCodes.PROPOSAL_JSON_MALFORMED,
                    ProposalErrorCodes.PROPOSAL_DUPLICATE_KEY,
                    ProposalErrorCodes.PROPOSAL_ENCODING_INVALID ->
                    new CampaignPlanHttpException(
                            CampaignPlanHttpErrorCodes.REQUEST_MALFORMED,
                            "The proposal body is malformed or not valid UTF-8 JSON.", failure);
            case ProposalErrorCodes.PROPOSAL_LIMIT_EXCEEDED ->
                    new CampaignPlanHttpException(
                            CampaignPlanHttpErrorCodes.REQUEST_LIMIT_EXCEEDED,
                            "The proposal body exceeds its declared byte bound.", failure);
            case ProposalErrorCodes.PROPOSAL_SHAPE_INVALID,
                    ProposalErrorCodes.PROPOSAL_MISSING_FIELD,
                    ProposalErrorCodes.PROPOSAL_NULL_FIELD,
                    ProposalErrorCodes.PROPOSAL_MODE_INVALID,
                    ProposalErrorCodes.PROPOSAL_CAMPAIGN_ID_INVALID,
                    ProposalErrorCodes.PROPOSAL_BASE_REVISIONS_INVALID,
                    ProposalErrorCodes.PROPOSAL_IDENTITY_INVALID,
                    ProposalErrorCodes.PROPOSAL_ANCHOR_INVALID,
                    ProposalErrorCodes.PROPOSAL_OPERATIONS_INVALID,
                    ProposalErrorCodes.PROPOSAL_RESERVED_FIELD,
                    ProposalErrorCodes.PROPOSAL_PAYLOAD_INVALID,
                    ProposalErrorCodes.PROPOSAL_CLIENT_CONTEXT_INVALID,
                    ProposalErrorCodes.PROPOSAL_CLIENT_REF_CONFLICT,
                    ProposalErrorCodes.PROPOSAL_ORDER_CYCLE ->
                    new CampaignPlanHttpException(
                            CampaignPlanHttpErrorCodes.REQUEST_INVALID,
                            "The proposal is not a valid CampaignProposalPatch.", failure);
            case ProposalErrorCodes.PROPOSAL_SCHEMA_VERSION_UNSUPPORTED,
                    ProposalErrorCodes.PROPOSAL_TOPOLOGY_INVALID,
                    ProposalErrorCodes.PROPOSAL_TOPOLOGY_VERSION_INVALID,
                    ProposalErrorCodes.PROPOSAL_TOPOLOGY_LIMIT_EXCEEDED,
                    ProposalErrorCodes.PROPOSAL_TOPOLOGY_DUPLICATE_EDGE,
                    ProposalErrorCodes.PROPOSAL_TOPOLOGY_SELF_EDGE,
                    ProposalErrorCodes.PROPOSAL_TOPOLOGY_ENDPOINT_INVALID,
                    ProposalErrorCodes.PROPOSAL_TOPOLOGY_SCHEMA_VERSION_UNSUPPORTED ->
                    new CampaignPlanHttpException(
                            CampaignPlanHttpErrorCodes.REQUEST_INVALID,
                            "The proposal carries an invalid Journey topology.", failure);
            default -> new CampaignPlanHttpException(
                    CampaignPlanHttpErrorCodes.REQUEST_INVALID,
                    "The proposal was refused by the strict codec.", failure);
        };
    }

    /** True when the proposal carries at least one {@code segment.rule-based} operation. */
    private static boolean hasSegmentOperation(CampaignProposalPatch patch) {
        for (ProposalOperation operation : patch.operations()) {
            if (SegmentAuthoringVersions.CAPABILITY_CODE.equals(operation.capabilityCode())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Bounded post-compile diagnostic: logs only the stage name, the exception class, and
     * (when the failure is a {@link CompilerException}) the stable code/invariant.
     * Never logs message, payload, SQL, IDs, proposal hashes, or secrets.
     *
     * <p>Returns a {@link CampaignPlanHttpException} with {@code INTERNAL_FAILURE} to
     * preserve the existing bounded HTTP behavior.
     */
    private static CampaignPlanHttpException boundedDiagnostic(
            String stage, RuntimeException failure) {
        if (failure instanceof CompilerException ce) {
            Object invariant = ce.details() != null
                    ? ce.details().get("invariant") : "none";
            LOG.error("stage={} code={} invariant={} exceptionClass={}",
                    stage, ce.code(), invariant, failure.getClass().getName());
        } else {
            LOG.error("stage={} exceptionClass={}", stage, failure.getClass().getName());
        }
        return new CampaignPlanHttpException(
                CampaignPlanHttpErrorCodes.INTERNAL_FAILURE,
                "An internal failure occurred.");
    }

    private static CampaignPlanHttpException mapCompilerFailure(CompilerException failure) {
        String code = failure.code();
        if (code == null) {
            return new CampaignPlanHttpException(
                    CampaignPlanHttpErrorCodes.INTERNAL_FAILURE,
                    "The compiler produced a bounded failure.");
        }
        CampaignPlanHttpException mapped = switch (code) {
            case CompilerErrorCodes.COMPILER_BASE_REVISIONS_STALE ->
                    new CampaignPlanHttpException(
                            CampaignPlanHttpErrorCodes.COMPILER_BASE_REVISIONS_STALE,
                            "The proposal's base revisions do not match the frozen catalog or policy.",
                            failure);
            case CompilerErrorCodes.COMPILER_TARGET_UNAVAILABLE ->
                    new CampaignPlanHttpException(
                            CampaignPlanHttpErrorCodes.COMPILER_TARGET_UNAVAILABLE,
                            "The campaign target is unavailable to the trusted context.", failure);
            case CompilerErrorCodes.COMPILER_AUTHORIZATION_DENIED ->
                    new CampaignPlanHttpException(
                            CampaignPlanHttpErrorCodes.COMPILER_AUTHORIZATION_DENIED,
                            "Campaign Draft authoring is not authorized for this trusted context.",
                            failure);
            case CompilerErrorCodes.COMPILER_CAPABILITY_CONTRACT_INVALID ->
                    new CampaignPlanHttpException(
                            CampaignPlanHttpErrorCodes.COMPILER_CONTRACT_INVALID,
                            "A capability contract did not produce a usable resolution.", failure);
            case CompilerErrorCodes.COMPILER_CAPABILITY_UNKNOWN,
                    CompilerErrorCodes.COMPILER_CAPABILITY_DISABLED,
                    CompilerErrorCodes.COMPILER_CAPABILITY_VERSION_MISMATCH,
                    CompilerErrorCodes.COMPILER_REFERENCE_UNRESOLVED ->
                    new CampaignPlanHttpException(
                            CampaignPlanHttpErrorCodes.REQUEST_INVALID,
                            "The proposal references a capability that is not in the frozen catalog.",
                            failure);
            case CompilerErrorCodes.COMPILER_FROZEN_INPUT_INVALID ->
                    new CampaignPlanHttpException(
                            CampaignPlanHttpErrorCodes.INTERNAL_FAILURE,
                            "The host-supplied compile evidence is inconsistent.", failure);
            case CompilerErrorCodes.COMPILER_COMPOSITION_SLOT_VIOLATION ->
                    new CampaignPlanHttpException(
                            CampaignPlanHttpErrorCodes.REQUEST_INVALID,
                            "The proposal violates a composition slot rule.", failure);
            case CompilerErrorCodes.COMPILER_POLICY_VIOLATION ->
                    new CampaignPlanHttpException(
                            CampaignPlanHttpErrorCodes.REQUEST_INVALID,
                            "The compiled Campaign violates the frozen authoring policy.", failure);
            case CompilerErrorCodes.COMPILER_OUTPUT_INCONSISTENT ->
                    new CampaignPlanHttpException(
                            CampaignPlanHttpErrorCodes.INTERNAL_FAILURE,
                            "The compiler produced an inconsistent output.", failure);
            case CompilerErrorCodes.COMPILER_LOGICAL_ID_UNKNOWN,
                    CompilerErrorCodes.COMPILER_LOGICAL_ID_COLLISION ->
                    new CampaignPlanHttpException(
                            CampaignPlanHttpErrorCodes.REQUEST_INVALID,
                            "The proposal names an unknown or conflicting component identity.",
                            failure);
            case CompilerErrorCodes.COMPILER_ANCHOR_UNRESOLVED ->
                    new CampaignPlanHttpException(
                            CampaignPlanHttpErrorCodes.REQUEST_INVALID,
                            "A MOVE anchor does not name a component the campaign owns.", failure);
            case CompilerErrorCodes.COMPILER_TOPOLOGY_INPUT_MISSING ->
                    new CampaignPlanHttpException(
                            CampaignPlanHttpErrorCodes.INTERNAL_FAILURE,
                            "A Journey topology replacement requires the frozen pack index.", failure);
            case CompilerErrorCodes.COMPILER_TOPOLOGY_ENDPOINT_UNRESOLVED ->
                    new CampaignPlanHttpException(
                            CampaignPlanHttpErrorCodes.REQUEST_INVALID,
                            "A Journey topology endpoint does not name a valid component.", failure);
            case CompilerErrorCodes.COMPILER_TOPOLOGY_INVALID,
                    CompilerErrorCodes.COMPILER_TOPOLOGY_GRAPH_INVALID,
                    CompilerErrorCodes.COMPILER_TOPOLOGY_REMOVE_INVALID ->
                    new CampaignPlanHttpException(
                            CampaignPlanHttpErrorCodes.REQUEST_INVALID,
                            "The Journey topology is invalid.", failure);
            case CompilerErrorCodes.COMPILER_INPUT_MISSING,
                    CompilerErrorCodes.COMPILER_INSTANTS_INVALID ->
                    new CampaignPlanHttpException(
                            CampaignPlanHttpErrorCodes.INTERNAL_FAILURE,
                            "A required compiler input is missing or invalid.", failure);
            case CompilerErrorCodes.COMPILER_PLAN_STORE_CONFLICT ->
                    new CampaignPlanHttpException(
                            CampaignPlanHttpErrorCodes.PLAN_STORE_REJECTED,
                            "The compiled Plan was already stored under a different identity.", failure);
            case CompilerErrorCodes.COMPILER_PLAN_STORE_INVALID ->
                    new CampaignPlanHttpException(
                            CampaignPlanHttpErrorCodes.INTERNAL_FAILURE,
                            "The Plan store rejected the compiled Plan.", failure);
            default -> {
                // Bounded diagnostic: stable code + invariant + exception class, never payload/secrets.
                Object invariant = failure.details() != null
                        ? failure.details().get("invariant") : "none";
                LOG.error("Unhandled compiler failure: code={} invariant={} exceptionClass={}",
                        code, invariant, failure.getClass().getName());
                yield new CampaignPlanHttpException(
                        CampaignPlanHttpErrorCodes.INTERNAL_FAILURE,
                        "The compiler refused the proposal with a bounded code " + code + '.',
                        failure);
            }
        };
        return mapped;
    }
}