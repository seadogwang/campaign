package com.company.campaign.builder.authoring;

import com.company.campaign.builder.agent.SegmentPreviewCard;
import com.company.campaign.builder.compiler.CapabilityContract;
import com.company.campaign.builder.compiler.CapabilityPayload;
import com.company.campaign.builder.compiler.CapabilityRequest;
import com.company.campaign.builder.compiler.CapabilityReadiness;
import com.company.campaign.builder.compiler.CapabilityResolution;
import com.company.campaign.builder.compiler.CompositionSlot;
import com.company.campaign.builder.compiler.PlanPreviewCard;
import com.company.campaign.builder.compiler.PlanPreviewStatistic;
import com.company.campaign.builder.compiler.ReferencePlanEvidence;
import com.company.campaign.builder.compiler.ResolvedCatalogReference;
import com.company.campaign.builder.compiler.RuntimeCompatibility;
import com.company.campaign.contracts.Issue;
import com.company.campaign.metadata.domain.MetadataSnapshot;
import com.company.campaign.preview.PreviewConditionStatistic;
import com.company.campaign.query.plan.ResolvedDependency;
import com.company.campaign.query.plan.SegmentQueryPlanner;
import com.company.campaign.query.plan.SemanticQueryPlan;
import com.company.campaign.segment.dsl.codec.SegmentDslCodec;
import com.company.campaign.segment.dsl.contract.MetadataBinding;
import com.company.campaign.segment.dsl.contract.SegmentDsl;
import com.company.campaign.segment.dsl.predicate.AttributePredicate;
import com.company.campaign.segment.dsl.predicate.AudienceReferencePredicate;
import com.company.campaign.segment.dsl.predicate.ExistsPredicate;
import com.company.campaign.segment.dsl.predicate.GroupPredicate;
import com.company.campaign.segment.dsl.predicate.MetricExpressionPredicate;
import com.company.campaign.segment.dsl.predicate.MetricPredicate;
import com.company.campaign.segment.dsl.predicate.NotPredicate;
import com.company.campaign.segment.dsl.predicate.Predicate;
import com.company.campaign.segment.validation.SegmentValidationPolicy;
import com.company.campaign.segment.validation.SegmentValidationResult;
import com.company.campaign.segment.validation.SegmentValidator;
import com.company.campaign.shared.canonical.ContentHash;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The concrete, production {@code segment.rule-based} capability contract.
 *
 * <p>It is the single place a resolved Segment becomes a T15 component. It accepts one closed, versioned
 * capability payload envelope, decodes the whole strict T05 Segment DSL from it through the accepted codec
 * only, and then re-proves the artifact rather than trusting any declared fact: the editable DSL hash, the
 * accepted T07 canonical AST hash and the accepted T08 semantic plan hash are all re-derived by calling the
 * genuine deterministic boundaries through the ports injected at construction, and each declared pin must
 * equal what the re-run derives.
 *
 * <p>Everything a contract must never derive from a proposal, a model or a payload is a frozen host input:
 * the exact Metadata Snapshot, the already-approved safe Preview card and the frozen Skill hash arrive through
 * {@link SegmentAuthoringInputs}; readiness and Gate evidence arrive through the compile context. The contract
 * never loads a snapshot, never queries Metadata, never calls a model or provider, never reads a clock, never
 * executes Preview and never declares a Gate fact of its own. The time pins it reports are the trusted
 * context's own; the readiness and Gate hashes it reports are the host projection's own.
 *
 * <p>On success it emits one deterministic normalized canonical payload, one stable catalog reference per
 * resolved Metadata definition and exactly one bound {@link PlanPreviewCard}. On any malformed, mismatched,
 * foreign, stale, ambiguous, unsupported or unprovable artifact it fails closed with one ordered bounded issue
 * that echoes no payload value. It contributes no card for an ordinary component and never contributes more
 * than one.
 *
 * <p>Its frozen metadata is a fact of this contract version: one unordered singleton Campaign
 * audience/segment slot, authoring enabled, and a Runtime compatibility that reports Preview as supported
 * while honestly declaring the capability not yet publishable, because production Apply, Publish and Runtime
 * do not exist in this task and development evidence is never relabelled as production-ready.
 */
public final class SegmentRuleBasedCapability implements CapabilityContract {

    /** The contract's frozen composition slot: the singleton Campaign audience/segment slot. */
    private static final CompositionSlot SLOT =
            new CompositionSlot(SegmentAuthoringVersions.SLOT_CODE, false, true);

    /**
     * The contract's frozen preview/runtime compatibility facts.
     *
     * <p>{@code previewSupported} is true because the accepted read-only T09 Preview exists. {@code publishable}
     * is false because no Apply, Publish or Runtime path exists in this task; the Gate/readiness of a compile
     * never upgrades this frozen fact, and only a later task may declare production publishability.
     */
    private static final RuntimeCompatibility RUNTIME_COMPATIBILITY =
            new RuntimeCompatibility(SegmentAuthoringVersions.RUNTIME_SCHEMA_VERSION, true, false);

    private static final SegmentDslCodec DSL_CODEC = new SegmentDslCodec();

    private static final String FAILURE_MESSAGE =
            "The rule-based Segment capability resolution failed closed.";

    private final SegmentValidator validator;
    private final SegmentValidationPolicy validationPolicy;
    private final SegmentQueryPlanner planner;

    /**
     * Creates the one production rule-based Segment contract.
     *
     * <p>Each argument is a deterministic acceptance boundary or policy the host owns: the T07 validator, its
     * composition policy and the T08 planner (which is itself constructed with the mandatory T02 authorization
     * boundary). None of them is a repository, a network, a provider, a model or a clock, and the contract
     * holds no mutable state.
     */
    public SegmentRuleBasedCapability(
            SegmentValidator validator,
            SegmentValidationPolicy validationPolicy,
            SegmentQueryPlanner planner) {
        this.validator = Objects.requireNonNull(validator, "validator");
        this.validationPolicy = Objects.requireNonNull(validationPolicy, "validationPolicy");
        this.planner = Objects.requireNonNull(planner, "planner");
    }

    @Override
    public String capabilityCode() {
        return SegmentAuthoringVersions.CAPABILITY_CODE;
    }

    @Override
    public String contractVersion() {
        return SegmentAuthoringVersions.CONTRACT_VERSION;
    }

    @Override
    public CompositionSlot slot() {
        return SLOT;
    }

    @Override
    public boolean enabled() {
        // Development authoring of the rule-based Segment is explicitly unblocked; the frozen catalog is what
        // decides whether it is wired in at all.
        return true;
    }

    @Override
    public RuntimeCompatibility runtimeCompatibility() {
        return RUNTIME_COMPATIBILITY;
    }

    @Override
    public CapabilityResolution resolve(CapabilityRequest request) {
        if (request == null) {
            return failed(SegmentAuthoringErrorCodes.INPUT_MISSING);
        }
        try {
            return resolveOrThrow(request);
        } catch (SegmentAuthoringException rejected) {
            // Every expected rejection is one ordered, bounded issue; the rejected value is never echoed.
            return failed(rejected.code());
        }
    }

    // ------------------------------------------------------------------ resolution

    private CapabilityResolution resolveOrThrow(CapabilityRequest request) {
        SegmentAuthoringInputs inputs = requireInputs(request);
        MetadataSnapshot snapshot = inputs.metadataSnapshot();
        SegmentAuthoringEnvelope envelope = SegmentAuthoringEnvelope.parse(request.payload());
        SegmentDsl document = envelope.decodeDsl(DSL_CODEC);

        requireBinding(document.metadata(), snapshot);
        requireSnapshotOwnership(snapshot, request);
        requireTimePins(request);

        // The document's own shape decides which accepted boundaries this compile may use: a document that
        // depends on referenced immutable Audience Versions is resolvable only through the host's trusted
        // reference context, and a reference-free document is resolvable only without one.
        boolean referenceBearing = declaresReference(document.predicate());
        requireReferenceContextAgrees(referenceBearing, inputs);

        SegmentValidationResult validation = validate(document, snapshot, request, inputs, referenceBearing);
        requireCanonicalHash(validation, envelope);

        SemanticQueryPlan plan = plan(validation, snapshot, request, inputs, referenceBearing);
        requirePlanHash(plan, validation, envelope);

        SegmentPreviewCard card = inputs.previewCard();
        requirePreview(card, envelope, inputs.skillHash(), document, validation, plan, snapshot, request);

        CapabilityReadiness readiness = requireReadiness(request);

        requireReferencePinsAgree(plan, inputs);

        List<ResolvedCatalogReference> references = references(plan);
        PlanPreviewCard planCard = planCard(request.logicalId(), card, document, validation, plan, snapshot,
                request, inputs.skillHash(), readiness, inputs.referencePins());
        CapabilityPayload normalized = normalize(document, validation, plan, card, inputs.skillHash());
        return new CapabilityResolution.Resolved(normalized, references, List.of(planCard));
    }

    // ------------------------------------------------------------------ frozen inputs

    private static SegmentAuthoringInputs requireInputs(CapabilityRequest request) {
        Optional<com.company.campaign.builder.compiler.CapabilityInputs> found =
                request.compileContext().inputsFor(SegmentAuthoringVersions.CAPABILITY_CODE);
        if (found.isEmpty() || !(found.get() instanceof SegmentAuthoringInputs inputs)) {
            throw new SegmentAuthoringException(
                    SegmentAuthoringErrorCodes.INPUT_MISSING,
                    "authoringInputs",
                    "The compile pinned no typed frozen Segment authoring evidence.");
        }
        return inputs;
    }

    /**
     * Requires the host reference context to be present exactly when the document declares a reference.
     *
     * <p>A document that depends on a referenced immutable Audience Version can only be resolved through the
     * host's trusted reference ports: without them there is no exact version to resolve, so the compile fails
     * closed rather than reaching a reference-free boundary that would silently drop the dependency. The
     * failure names only the missing host fact — never the referenced audience or version it refused.
     *
     * <p>The converse is refused for the same reason: a reference-free document that pins a host reference
     * context would never consult its ports, so the pins would describe a capability the compile does not have.
     */
    private static void requireReferenceContextAgrees(boolean referenceBearing, SegmentAuthoringInputs inputs) {
        if (referenceBearing && !inputs.hasReferenceContext()) {
            throw new SegmentAuthoringException(
                    SegmentAuthoringErrorCodes.REFERENCE_CONTEXT_MISSING,
                    "referenceContext",
                    "A Segment that depends on a referenced immutable Audience Version requires the host's trusted reference context.");
        }
        if (!referenceBearing && inputs.hasReferenceContext()) {
            throw new SegmentAuthoringException(
                    SegmentAuthoringErrorCodes.EVIDENCE_MISMATCH,
                    "referenceContext",
                    "A reference-free Segment must not be pinned to a host reference context it never consults.");
        }
    }

    /**
     * True when the editable document declares at least one Existing Audience Reference anywhere in its tree.
     *
     * <p>It is read from the document's own shape, never from a declaration: the whole point of a reference is
     * that the referenced version is resolved server-side, so a document that is about to depend on one must
     * be recognisable before any boundary has been re-run. The traversal is the accepted union order — an ALL
     * or ANY group's children, a NOT's child, an EXISTS's predicate — and every other node is a leaf.
     */
    private static boolean declaresReference(Predicate node) {
        if (node instanceof AudienceReferencePredicate) {
            return true;
        }
        for (Predicate child : childrenOf(node)) {
            if (declaresReference(child)) {
                return true;
            }
        }
        return false;
    }

    /** The direct children of one predicate node in their declared order. */
    private static List<Predicate> childrenOf(Predicate node) {
        return switch (node) {
            case GroupPredicate group -> group.children();
            case NotPredicate not -> List.of(not.child());
            case ExistsPredicate exists -> List.of(exists.predicate());
            case AttributePredicate ignored -> List.of();
            case MetricPredicate ignored -> List.of();
            case MetricExpressionPredicate ignored -> List.of();
            case AudienceReferencePredicate ignored -> List.of();
        };
    }

    /**
     * Requires the reference readiness pins and the plan's own reference dependencies to agree.
     *
     * <p>The two must be present together or absent together. A reference-bearing plan projected onto a card
     * with no reference readiness would hide the fact that the extension is development-only, and a
     * reference-free plan carrying reference pins would claim a dependency it does not have. Either way the
     * card would misstate what the compile actually depended on, so both are refused.
     */
    private static void requireReferencePinsAgree(SemanticQueryPlan plan, SegmentAuthoringInputs inputs) {
        boolean planHasReferences = !plan.audienceDependencies().isEmpty();
        if (planHasReferences != inputs.hasReferencePins()) {
            throw new SegmentAuthoringException(
                    SegmentAuthoringErrorCodes.EVIDENCE_MISMATCH,
                    "referencePins",
                    "A Segment's reference readiness pins must be present exactly when it depends on a referenced version.");
        }
    }

    private static CapabilityReadiness requireReadiness(CapabilityRequest request) {
        Optional<CapabilityReadiness> found =
                request.compileContext().readinessFor(SegmentAuthoringVersions.CAPABILITY_CODE);
        if (found.isEmpty()) {
            throw new SegmentAuthoringException(
                    SegmentAuthoringErrorCodes.READINESS_MISSING,
                    "readiness",
                    "The compile pinned no readiness fact for the rule-based Segment capability.");
        }
        CapabilityReadiness readiness = found.get();
        boolean coherentState = CapabilityReadiness.ACTIVE.equals(readiness.state())
                || CapabilityReadiness.EXPERIMENTAL.equals(readiness.state())
                || CapabilityReadiness.EVALUATION_REQUIRED.equals(readiness.state());
        // A card always binds the Gate inputs its compile ran against, and only an ACTIVE card additionally
        // rests on an approved decision. A fact that cannot supply those binds nothing and fails closed.
        if (!coherentState
                || readiness.activeReady() != readiness.active()
                || readiness.currentInputsHash() == null
                || (readiness.active() && !readiness.hasApprovedDecision())) {
            throw new SegmentAuthoringException(
                    SegmentAuthoringErrorCodes.READINESS_INVALID,
                    "readiness",
                    "The pinned readiness fact is not a coherent, complete capability projection.");
        }
        return readiness;
    }

    // ------------------------------------------------------------------ metadata boundary

    private static void requireBinding(MetadataBinding binding, MetadataSnapshot snapshot) {
        if (binding == null
                || !Objects.equals(binding.snapshotId(), snapshot.snapshotId())
                || !Objects.equals(binding.snapshotVersion(), snapshot.snapshotVersion())
                || !Objects.equals(binding.metadataSchemaVersion(), snapshot.schemaVersion())
                || !Objects.equals(binding.contentHash(), snapshot.contentHash())) {
            throw new SegmentAuthoringException(
                    SegmentAuthoringErrorCodes.METADATA_MISMATCH,
                    "metadataBinding",
                    "The embedded Segment DSL does not bind the exact pinned Metadata Snapshot.");
        }
    }

    private static void requireSnapshotOwnership(MetadataSnapshot snapshot, CapabilityRequest request) {
        if (request.context().snapshotRef() == null
                || !Objects.equals(snapshot.snapshotId(), request.context().snapshotRef().metadataSnapshotId())
                || !Objects.equals(snapshot.tenantId(), request.context().tenantId())
                || !Objects.equals(snapshot.programId(), request.context().programId())) {
            throw new SegmentAuthoringException(
                    SegmentAuthoringErrorCodes.METADATA_MISMATCH,
                    "metadataOwner",
                    "The pinned Metadata Snapshot is not the trusted context's own snapshot.");
        }
    }

    // ------------------------------------------------------------------ trusted time pins

    /**
     * Requires the compile's own trusted context to supply all three time pins, and takes them from nowhere
     * else.
     *
     * <p>The payload deliberately carries no {@code asOf}, {@code timezone} or {@code dataWatermark}: they are
     * trusted compile/context facts, and the active boundary states that server timestamps are never
     * model/client Proposal fields (the names are reserved on the Proposal surface for exactly that reason).
     * The pins therefore come only from {@link com.company.campaign.security.context.TrustedExecutionContext} —
     * the accepted T02 boundary, which already refuses to build a context with an absent pin — and an absent or
     * malformed pin here fails closed rather than being defaulted. The host-pinned Preview card is then
     * re-proved against these same pins in {@link #requirePreview}; no other method may read them.
     */
    private static void requireTimePins(CapabilityRequest request) {
        var reference = request.context().snapshotRef();
        if (reference == null
                || reference.asOf() == null
                || reference.timezone() == null
                || reference.dataWatermark() == null) {
            throw new SegmentAuthoringException(
                    SegmentAuthoringErrorCodes.TIME_PIN_MISMATCH,
                    "trustedTimePins",
                    "The compile must run under a trusted context that carries every trusted time pin.");
        }
    }

    // ------------------------------------------------------------------ deterministic re-proof

    private SegmentValidationResult validate(
            SegmentDsl document,
            MetadataSnapshot snapshot,
            CapabilityRequest request,
            SegmentAuthoringInputs inputs,
            boolean referenceBearing) {
        SegmentValidationResult result;
        try {
            // A reference-bearing document goes through the accepted five-argument reference entry point; a
            // reference-free document keeps the accepted four-argument entry point byte for byte, and the host
            // ports are never even read for it.
            result = referenceBearing
                    ? validator.validate(document, snapshot, request.context(), validationPolicy,
                            inputs.referenceContext().validationContext())
                    : validator.validate(document, snapshot, request.context(), validationPolicy);
        } catch (RuntimeException exception) {
            // The thrown cause and its text never escape; the boundary is re-run, never trusted.
            throw new SegmentAuthoringException(
                    SegmentAuthoringErrorCodes.DSL_INVALID,
                    "validation",
                    "The embedded Segment DSL did not pass the accepted deterministic validation boundary.");
        }
        if (result == null || !result.valid() || result.canonicalAstHash() == null
                || !Objects.equals(result.editableDslHash(), document.editableDslHash())) {
            throw new SegmentAuthoringException(
                    SegmentAuthoringErrorCodes.DSL_INVALID,
                    "validation",
                    "The embedded Segment DSL did not produce an accepted canonical artifact.");
        }
        return result;
    }

    private static void requireCanonicalHash(
            SegmentValidationResult validation, SegmentAuthoringEnvelope envelope) {
        if (!Objects.equals(validation.canonicalAstHash(), envelope.canonicalAstHash())) {
            throw new SegmentAuthoringException(
                    SegmentAuthoringErrorCodes.EVIDENCE_MISMATCH,
                    "canonicalAstHash",
                    "The declared canonical AST hash is not the one the accepted boundary derived.");
        }
    }

    private SemanticQueryPlan plan(
            SegmentValidationResult validation,
            MetadataSnapshot snapshot,
            CapabilityRequest request,
            SegmentAuthoringInputs inputs,
            boolean referenceBearing) {
        SemanticQueryPlan plan;
        try {
            // A reference-bearing validation result is re-resolved through the accepted four-argument entry
            // point, so every referenced version is re-authorized and its effective DataScope re-intersected; a
            // reference-free result keeps the accepted three-argument entry point and consults no port at all.
            plan = referenceBearing
                    ? planner.plan(validation, snapshot, request.context(),
                            inputs.referenceContext().executionContext())
                    : planner.plan(validation, snapshot, request.context());
        } catch (RuntimeException exception) {
            throw new SegmentAuthoringException(
                    SegmentAuthoringErrorCodes.EVIDENCE_MISMATCH,
                    "semanticPlan",
                    "The accepted deterministic planner did not produce a Semantic Query Plan.");
        }
        if (plan == null) {
            throw new SegmentAuthoringException(
                    SegmentAuthoringErrorCodes.EVIDENCE_MISMATCH,
                    "semanticPlan",
                    "The accepted deterministic planner did not produce a Semantic Query Plan.");
        }
        return plan;
    }

    private static void requirePlanHash(
            SemanticQueryPlan plan, SegmentValidationResult validation, SegmentAuthoringEnvelope envelope) {
        if (plan.planHash() == null
                || !Objects.equals(plan.canonicalAstHash(), validation.canonicalAstHash())
                || !Objects.equals(plan.planHash(), envelope.semanticPlanHash())) {
            throw new SegmentAuthoringException(
                    SegmentAuthoringErrorCodes.EVIDENCE_MISMATCH,
                    "semanticPlanHash",
                    "The declared semantic plan hash is not the one the accepted boundary derived.");
        }
    }

    // ------------------------------------------------------------------ safe Preview evidence

    private static void requirePreview(
            SegmentPreviewCard card,
            SegmentAuthoringEnvelope envelope,
            ContentHash skillHash,
            SegmentDsl document,
            SegmentValidationResult validation,
            SemanticQueryPlan plan,
            MetadataSnapshot snapshot,
            CapabilityRequest request) {
        var reference = request.context().snapshotRef();
        if (!Objects.equals(card.dslHash(), document.editableDslHash())
                || !Objects.equals(card.canonicalAstHash(), validation.canonicalAstHash())
                || !Objects.equals(card.planHash(), plan.planHash())
                || !Objects.equals(card.metadataHash(), snapshot.contentHash())
                || !Objects.equals(card.metadataSnapshotId(), snapshot.snapshotId())
                || !Objects.equals(card.skillHash(), skillHash)
                || !Objects.equals(card.skillHash(), envelope.skillHash())
                || !Objects.equals(card.previewHash(), envelope.previewHash())
                || reference == null
                || !Objects.equals(card.asOf(), reference.asOf().toString())
                || !Objects.equals(card.timezone(), reference.timezone().getId())
                || !Objects.equals(card.dataWatermark(), reference.dataWatermark().toString())) {
            // The Preview card is server-owned, but its pins must still be the ones this compile can re-derive,
            // and the payload may only claim the Preview hash the server-owned card already carries.
            throw new SegmentAuthoringException(
                    SegmentAuthoringErrorCodes.PREVIEW_MISMATCH,
                    "previewEvidence",
                    "The pinned safe Preview evidence is absent, stale or not the one this compile resolves.");
        }
    }

    // ------------------------------------------------------------------ stable references

    /**
     * Resolves every Metadata definition the accepted plan depends on to one stable logical/version pair.
     *
     * <p>The plan's dependency set is the exact, duplicate-free, deterministically ordered result of the
     * accepted boundary, so it is the honest source of truth for what the Segment actually references. A
     * definition whose identifier cannot be a stable bounded reference fails closed rather than being
     * silently dropped.
     */
    private static List<ResolvedCatalogReference> references(SemanticQueryPlan plan) {
        List<ResolvedCatalogReference> references = new ArrayList<>(plan.dependencies().size());
        try {
            for (ResolvedDependency dependency : plan.dependencies()) {
                references.add(new ResolvedCatalogReference(
                        dependency.id(),
                        dependency.kind().name(),
                        dependency.id(),
                        versionId(dependency)));
            }
        } catch (RuntimeException exception) {
            throw new SegmentAuthoringException(
                    SegmentAuthoringErrorCodes.EVIDENCE_MISMATCH,
                    "catalogReferences",
                    "A resolved Metadata dependency is not a stable bounded catalog reference.");
        }
        return List.copyOf(references);
    }

    /**
     * The pinned version of one resolved dependency.
     *
     * <p>An Attribute or Metric pins its entity schema version. A Relation spans two entities, so it pins both
     * the source and target schema versions in one bounded token rather than discarding one of them.
     */
    private static String versionId(ResolvedDependency dependency) {
        return switch (dependency) {
            case ResolvedDependency.AttributeDependency attribute ->
                    attribute.entityVersion().schemaVersion().value();
            case ResolvedDependency.MetricDependency metric ->
                    metric.entityVersion().schemaVersion().value();
            case ResolvedDependency.RelationDependency relation ->
                    relation.sourceVersion().schemaVersion().value()
                            + ':' + relation.targetVersion().schemaVersion().value();
        };
    }

    // ------------------------------------------------------------------ code card and payload

    private static PlanPreviewCard planCard(
            String logicalId,
            SegmentPreviewCard card,
            SegmentDsl document,
            SegmentValidationResult validation,
            SemanticQueryPlan plan,
            MetadataSnapshot snapshot,
            CapabilityRequest request,
            ContentHash skillHash,
            CapabilityReadiness readiness,
            SegmentAuthoringReferencePins referencePins) {
        var reference = request.context().snapshotRef();
        List<PlanPreviewStatistic> statistics = statistics(card.conditionStatistics());
        // The card carries the compile's dedicated reference evidence exactly when the plan depends on
        // referenced immutable Audience Versions, and never otherwise. A reference-bearing plan must not hide
        // the extension and the development-only readiness it ran under, and a reference-free plan must not
        // claim a dependency it does not have — either way the card would misstate the compile.
        boolean referenceBearing = !plan.audienceDependencies().isEmpty();
        if (referenceBearing != (referencePins != null)) {
            throw new SegmentAuthoringException(
                    SegmentAuthoringErrorCodes.EVIDENCE_MISMATCH,
                    "referencePins",
                    "A Segment's reference readiness pins must be present exactly when it depends on a referenced version.");
        }
        return new PlanPreviewCard(
                SegmentAuthoringVersions.PREVIEW_CARD_KIND,
                logicalId,
                card.matchedCount(),
                statistics,
                card.sampleTokens(),
                snapshot.snapshotId(),
                reference.asOf(),
                reference.timezone().getId(),
                reference.dataWatermark(),
                document.editableDslHash(),
                validation.canonicalAstHash(),
                plan.planHash(),
                card.previewHash(),
                snapshot.contentHash(),
                skillHash,
                readiness.state(),
                readiness.currentInputsHash(),
                readiness.approvedDecisionHash(),
                referenceBearing ? referenceEvidenceOf(referencePins) : null);
    }

    /**
     * The card's dedicated reference evidence, populated exactly from the compile's frozen host reference pins.
     *
     * <p>The pins are host input, never a proposal or a capability payload, so nothing a client or a model
     * declares can become evidence on the card. The dedicated identity is re-proved here rather than copied
     * blindly: only {@code segment.existing-audience-reference} may be carried, so a pin set naming the base
     * capability — or anything else — is refused instead of being written into the plan material.
     */
    private static ReferencePlanEvidence referenceEvidenceOf(SegmentAuthoringReferencePins pins) {
        if (!SegmentAuthoringVersions.REFERENCE_CAPABILITY_CODE.equals(pins.capabilityCode())) {
            throw new SegmentAuthoringException(
                    SegmentAuthoringErrorCodes.EVIDENCE_MISMATCH,
                    "referencePins",
                    "Only the dedicated Existing Audience Reference identity may be carried as reference evidence.");
        }
        return new ReferencePlanEvidence(
                pins.capabilityCode(),
                pins.referenceDependenciesHash(),
                pins.referenceInputsHash(),
                pins.readinessState());
    }

    private static List<PlanPreviewStatistic> statistics(List<PreviewConditionStatistic> source) {
        if (source == null) {
            return null;
        }
        List<PlanPreviewStatistic> statistics = new ArrayList<>(source.size());
        for (PreviewConditionStatistic statistic : source) {
            statistics.add(new PlanPreviewStatistic(
                    statistic.path(), statistic.trueCount(), statistic.falseCount(), statistic.unknownCount()));
        }
        return List.copyOf(statistics);
    }

    /**
     * Builds the deterministic normalized canonical payload.
     *
     * <p>It preserves the complete executable Segment semantics as one opaque canonical DSL wire and binds the
     * three hashes the compile re-proved plus the frozen Skill hash. It deliberately carries no natural-language
     * prompt, provider or model output, clarification answer, audit trace, raw member identifier, SQL, matched
     * count, sample, trusted identity or security fact, readiness or Gate fact: none of those is part of a
     * component's semantics, and the safe projection of a Preview lives on the plan card.
     */
    private static CapabilityPayload normalize(
            SegmentDsl document,
            SegmentValidationResult validation,
            SemanticQueryPlan plan,
            SegmentPreviewCard card,
            ContentHash skillHash) {
        // The envelope is written by its one owning writer, which the shared authoring integration also uses,
        // so the payload a submitted source produces and the payload this contract re-proves are one shape.
        return SegmentAuthoringEnvelope.payloadOf(
                document, validation.canonicalAstHash(), plan.planHash(), card.previewHash(), skillHash);
    }

    // ------------------------------------------------------------------ failure shape

    private static CapabilityResolution failed(String code) {
        return new CapabilityResolution.Failed(List.of(Issue.error(code, FAILURE_MESSAGE)));
    }

    /** A bounded diagnostic: this contract holds no value that could leak, so it prints only its identity. */
    @Override
    public String toString() {
        return "SegmentRuleBasedCapability[capabilityCode=" + SegmentAuthoringVersions.CAPABILITY_CODE
                + ", contractVersion=" + SegmentAuthoringVersions.CONTRACT_VERSION + ']';
    }
}