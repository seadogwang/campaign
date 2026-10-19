package com.company.campaign.evaluation.segment.runner;

import com.company.campaign.builder.agent.ExplanationClaim;
import com.company.campaign.builder.agent.GroundedExplanation;
import com.company.campaign.builder.agent.SegmentAgentAdapter;
import com.company.campaign.builder.agent.SegmentAgentEnvironment;
import com.company.campaign.builder.agent.SegmentAgentErrorCodes;
import com.company.campaign.builder.agent.SegmentAgentRequest;
import com.company.campaign.builder.agent.SegmentAgentResult;
import com.company.campaign.builder.agent.SegmentPreviewCard;
import com.company.campaign.builder.refine.SegmentRefinementAdapter;
import com.company.campaign.builder.refine.SegmentRefinementAdapterErrorCodes;
import com.company.campaign.builder.refine.SegmentRefinementEnvironment;
import com.company.campaign.builder.refine.SegmentRefinementPatch;
import com.company.campaign.builder.refine.SegmentRefinementRequest;
import com.company.campaign.builder.refine.SegmentRefinementReceipt;
import com.company.campaign.builder.refine.SegmentRefinementResult;
import com.company.campaign.builder.segment.SegmentPromptContract;
import com.company.campaign.builder.skill.SegmentSkillDefinition;
import com.company.campaign.builder.tool.SegmentToolCatalog;
import com.company.campaign.contracts.AgentEnvelope;
import com.company.campaign.contracts.ContractVersions;
import com.company.campaign.contracts.Disposition;
import com.company.campaign.contracts.Issue;
import com.company.campaign.contracts.SourceSpan;
import com.company.campaign.contracts.TrustedExecutionContextRef;
import com.company.campaign.evaluation.segment.contract.CaseFileReference;
import com.company.campaign.evaluation.segment.contract.CaseInput;
import com.company.campaign.evaluation.segment.contract.CorpusBundle;
import com.company.campaign.evaluation.segment.contract.CorpusManifest;
import com.company.campaign.evaluation.segment.contract.ExpectedIntentSlot;
import com.company.campaign.evaluation.segment.contract.ExpectedResult;
import com.company.campaign.evaluation.segment.contract.FixtureDescriptor;
import com.company.campaign.evaluation.segment.contract.FixtureRole;
import com.company.campaign.evaluation.segment.contract.MemberIdSet;
import com.company.campaign.evaluation.segment.contract.MetadataSnapshotDescriptor;
import com.company.campaign.segment.dsl.contract.MetadataBinding;
import com.company.campaign.evaluation.segment.contract.RefinementTargetPlaceholder;
import com.company.campaign.evaluation.segment.loader.EvaluationRoot;
import com.company.campaign.evaluation.segment.loader.SegmentEvaluationLoader;
import com.company.campaign.evaluation.segment.review.CorpusUsageClassification;
import com.company.campaign.evaluation.segment.review.ReviewedCorpusClassification;
import com.company.campaign.evaluation.segment.review.SegmentCorpusReviewBoundary;
import com.company.campaign.evaluation.segment.validation.ReviewedExpectedEvidence;
import com.company.campaign.metadata.domain.MetadataSnapshot;
import com.company.campaign.query.fixture.FixtureDataset;
import com.company.campaign.query.fixture.FixtureDatasetLoader;
import com.company.campaign.query.fixture.FixtureExecutionResult;
import com.company.campaign.query.fixture.FixtureExecutor;
import com.company.campaign.query.plan.SegmentQueryPlanner;
import com.company.campaign.query.plan.SemanticQueryPlan;
import com.company.campaign.segment.canonical.CanonicalizerVersions;
import com.company.campaign.segment.dsl.codec.SegmentDslCodec;
import com.company.campaign.segment.dsl.contract.SegmentDsl;
import com.company.campaign.segment.intent.IntentClause;
import com.company.campaign.segment.intent.IntentProvenance;
import com.company.campaign.segment.intent.IntentValue;
import com.company.campaign.segment.intent.SegmentIntent;
import com.company.campaign.segment.resolution.ClauseResolution;
import com.company.campaign.segment.resolution.QuestionCode;
import com.company.campaign.segment.resolution.RejectedCandidate;
import com.company.campaign.segment.resolution.ResolutionResult;
import com.company.campaign.segment.validation.SegmentValidationResult;
import com.company.campaign.segment.validation.SegmentValidator;
import com.company.campaign.security.context.TrustedExecutionContext;
import com.company.campaign.shared.canonical.CanonicalJson;
import com.company.campaign.shared.canonical.ContentHash;
import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * The reproducible T13 Segment evaluation runner.
 *
 * <p>It freezes one complete run contract before the first Case starts, executes every Case in manifest order
 * through the genuine accepted T11 Agent adapter, the genuine T12 refinement adapter and the genuine T09
 * fixture executor, compares the observed typed artifacts against the frozen expectations, and emits a report
 * whose bytes are a pure function of the frozen inputs and the observed outcomes.
 *
 * <p>It implements an Evaluation Runner only. It never applies a threshold, decides a Gate verdict, promotes a
 * capability, saves, applies, publishes, mutates a review, relabels an old report, or reaches a network,
 * database, clock, random source or default locale/timezone.
 */
public final class SegmentEvaluationRunner {

    /** The single deterministic code recorded for an attempt not run because the run was already invalid. */
    public static final String NOT_RUN_DUE_TO_RUN_INVALID = "NOT_RUN_DUE_TO_RUN_INVALID";

    private static final CanonicalJson CANONICAL_JSON = new CanonicalJson();
    private static final FixtureExecutor FIXTURE_EXECUTOR = new FixtureExecutor();
    private static final int RATIO_SCALE = 12;

    /** The exact repeat count every {@code RiskLevel.HIGH} Case runs, independent of any manifest request. */
    public static final int REQUIRED_HIGH_RISK_REPEATS = 5;

    /** A resolved expectation carried no reviewed canonical AST. */
    public static final String EXPECTED_EVIDENCE_AST_MISSING = "EXPECTED_EVIDENCE_AST_MISSING";
    /** A reviewed canonical AST was not a structurally valid T05 document. */
    public static final String EXPECTED_EVIDENCE_AST_UNREADABLE = "EXPECTED_EVIDENCE_AST_UNREADABLE";
    /** A reviewed canonical AST named a different Metadata snapshot than its Case. */
    public static final String EXPECTED_EVIDENCE_BINDING_MISMATCH = "EXPECTED_EVIDENCE_BINDING_MISMATCH";
    /** The genuine T07 validator refused the reviewed canonical AST of a resolved expectation. */
    public static final String EXPECTED_EVIDENCE_AST_INVALID = "EXPECTED_EVIDENCE_AST_INVALID";
    /** The genuine T08 planner refused the reviewed canonical AST. */
    public static final String EXPECTED_EVIDENCE_PLAN_INVALID = "EXPECTED_EVIDENCE_PLAN_INVALID";
    /** A reviewed expectation claims an invalid outcome, but its DSL genuinely validated. */
    public static final String EXPECTED_EVIDENCE_INVALID_DSL_VALIDATED =
            "EXPECTED_EVIDENCE_INVALID_DSL_VALIDATED";
    /** The genuine T07 issue sequence contradicts the one a reviewed invalid expectation declares. */
    public static final String EXPECTED_EVIDENCE_ISSUE_SEQUENCE_CONTRADICTED =
            "EXPECTED_EVIDENCE_ISSUE_SEQUENCE_CONTRADICTED";

    private final SegmentEvaluationLoader loader;
    private final FixtureDatasetLoader fixtureLoader;
    private final SegmentCorpusReviewBoundary reviewBoundary = new SegmentCorpusReviewBoundary();
    private final SegmentDslCodec dslCodec = new SegmentDslCodec();

    public SegmentEvaluationRunner(Path repositoryRoot) {
        Objects.requireNonNull(repositoryRoot, "repositoryRoot");
        this.loader = new SegmentEvaluationLoader(repositoryRoot);
        this.fixtureLoader = new FixtureDatasetLoader(EvaluationRoot.of(repositoryRoot));
    }

    /** Frozen run: the exact contract plus the loaded corpus and host services it was derived from. */
    public record FrozenRun(
            EvaluationRunContract contract,
            CorpusBundle bundle,
            Map<String, MetadataSnapshot> snapshots,
            Map<String, FixtureDataset> datasets,
            Map<String, ContentHash> expectedAstHashes,
            Map<String, ContentHash> expectedPlanHashes,
            Map<String, ContentHash> expectedInvalidDslHashes,
            Map<String, String> expectedEvidenceCodes,
            EvaluationRunHost host) {
    }

    /** Loads and freezes the run contract for one request without executing any Case. */
    public FrozenRun freeze(EvaluationRunRequest request, EvaluationRunHost host) {
        Objects.requireNonNull(host, "host");
        return freeze(request, host, loader.loadCorpus(request.manifestReference()));
    }

    /** Freezes the contract and executes every required Case in manifest order. */
    public EvaluationReport run(EvaluationRunRequest request, EvaluationRunHost host) {
        FrozenRun frozen = freeze(request, host);
        return execute(frozen);
    }

    private FrozenRun freeze(EvaluationRunRequest request, EvaluationRunHost host, CorpusBundle bundle) {
        SegmentSkillDefinition skill = requireHost(host.skill(), "Skill");
        // The review classification is frozen, never a refusal: a pending-review corpus is exactly the
        // diagnostic case T13 exists for, and a structurally unusable corpus is recorded as an INVALID
        // report rather than silently converted into a different verdict. Only a corpus that cannot be
        // loaded, or a pin that is missing, stale or contradictory, stops the run.
        ReviewedCorpusClassification classification = reviewBoundary.classify(bundle);
        CorpusManifest manifest = bundle.manifest();
        if (classification.manifestHash() == null || !classification.manifestHash().equals(manifest.manifestHash())
                || classification.reviewSetHash() == null || classification.corpusHash() == null) {
            throw EvaluationRunException.of(
                    EvaluationRunErrorCodes.PIN_STALE,
                    "The review classification was not computed over the exact frozen manifest");
        }

        Map<String, MetadataSnapshot> snapshots = parseSnapshots(bundle);
        Map<String, FixtureDataset> datasets = loadDatasets(bundle);
        List<EvaluationCasePins> casePins = buildCasePins(bundle, snapshots, host);
        ExpectedEvidence expectedEvidence = freezeExpectedEvidence(bundle, snapshots, casePins, host);
        Map<String, ContentHash> expectedAstHashes = expectedEvidence.astHashes();
        Map<String, ContentHash> expectedPlanHashes = expectedEvidence.planHashes();
        Map<String, ContentHash> expectedInvalidDslHashes = expectedEvidence.invalidDslHashes();
        Map<String, String> expectedEvidenceCodes = expectedEvidence.codes();

        EvaluationRunContract contract = new EvaluationRunContract(
                T13Versions.RUN_CONTRACT_VERSION,
                T13Versions.REPORT_SCHEMA_VERSION,
                request.reportId(),
                request.model(),
                request.decoding(),
                artifactPins(skill),
                parserPins(skill),
                new EvaluationRunContract.MetadataPins(
                        bundle.metadataCatalog().catalogVersion(), manifest.metadataCatalog().contentHash()),
                new EvaluationRunContract.CandidateCorpusPins(
                        manifest.corpusVersion(), classification.corpusHash(), manifest.manifestHash(),
                        bundle.metadataCatalog().catalogVersion(), manifest.metadataCatalog().contentHash(),
                        bundle.fixtureCatalog().catalogVersion(), manifest.fixtureCatalog().contentHash(),
                        bundle.cases().size(), manifest.minimumCasesPerCategory(),
                        manifest.requiredHighRiskRepeats()),
                new EvaluationRunContract.ReviewPins(
                        classification.classification(), classification.issueCodes(),
                        classification.reviewSetHash(), classification.manifestHash()),
                timePins(bundle),
                new EvaluationRunContract.CodePins(request.codeRevision(), request.environment()),
                casePins);
        return new FrozenRun(
                contract, bundle, snapshots, datasets, expectedAstHashes, expectedPlanHashes,
                expectedInvalidDslHashes, expectedEvidenceCodes, host);
    }

    /** The frozen expected evidence of every Case: hashes, plus any bounded corpus-invalidity code. */
    private record ExpectedEvidence(
            Map<String, ContentHash> astHashes,
            Map<String, ContentHash> planHashes,
            Map<String, ContentHash> invalidDslHashes,
            Map<String, String> codes) {
    }

    private Map<String, MetadataSnapshot> parseSnapshots(CorpusBundle bundle) {
        Map<String, MetadataSnapshot> snapshots = new LinkedHashMap<>();
        for (MetadataSnapshotDescriptor descriptor : bundle.metadataCatalog().snapshots()) {
            byte[] bytes = bundle.metadataPayload(descriptor.snapshotId());
            if (bytes == null) {
                throw EvaluationRunException.of(
                        EvaluationRunErrorCodes.PIN_MISSING,
                        "A pinned Metadata payload is absent from the loaded corpus");
            }
            MetadataSnapshot snapshot;
            try {
                snapshot = CANONICAL_JSON.read(new String(bytes, StandardCharsets.UTF_8), MetadataSnapshot.class);
            } catch (RuntimeException failure) {
                throw EvaluationRunException.of(
                        EvaluationRunErrorCodes.PIN_STALE,
                        "A pinned Metadata payload is not a valid T04 snapshot");
            }
            // The descriptor pins the payload file's bytes; the snapshot owns the material hash its ASTs
            // bind. Requiring the bytes to be the canonical serialization of the parsed value proves the two
            // agree without pretending they are the same hash.
            if (!snapshot.snapshotId().equals(descriptor.snapshotId())
                    || !snapshot.snapshotVersion().equals(descriptor.snapshotVersion())
                    || !ContentHash.sha256(CANONICAL_JSON.write(snapshot)).equals(descriptor.contentHash())) {
                throw EvaluationRunException.of(
                        EvaluationRunErrorCodes.PIN_CONTRADICTORY,
                        "A pinned Metadata snapshot contradicts its catalog descriptor");
            }
            snapshots.put(descriptor.snapshotId(), snapshot);
        }
        return snapshots;
    }

    private Map<String, FixtureDataset> loadDatasets(CorpusBundle bundle) {
        Map<String, FixtureDataset> datasets = new LinkedHashMap<>();
        for (FixtureDescriptor descriptor : bundle.fixtureCatalog().fixtures()) {
            if (!descriptor.role().isMemberSetFixture()) {
                continue;
            }
            FixtureDataset dataset;
            try {
                dataset = fixtureLoader.load(descriptor);
            } catch (RuntimeException failure) {
                throw EvaluationRunException.of(
                        EvaluationRunErrorCodes.PIN_STALE,
                        "A pinned fixture payload could not be loaded as its reviewed dataset");
            }
            if (!dataset.payloadHash().equals(descriptor.payloadHash())
                    || !dataset.fixtureId().equals(descriptor.fixtureId())) {
                throw EvaluationRunException.of(
                        EvaluationRunErrorCodes.PIN_CONTRADICTORY,
                        "A loaded fixture dataset contradicts its catalog descriptor");
            }
            datasets.put(descriptor.fixtureId(), dataset);
        }
        return datasets;
    }

    private List<EvaluationCasePins> buildCasePins(
            CorpusBundle bundle, Map<String, MetadataSnapshot> snapshots, EvaluationRunHost host) {
        List<EvaluationCasePins> pins = new ArrayList<>();
        List<CaseFileReference> references = bundle.manifest().cases();
        for (int index = 0; index < references.size(); index++) {
            CaseFileReference reference = references.get(index);
            CaseInput caseInput = bundle.cases().get(index);
            if (!caseInput.caseId().equals(reference.caseId())
                    || !reference.hasExpectedCounterpart() || !reference.hasReviewEvidence()) {
                throw EvaluationRunException.of(
                        EvaluationRunErrorCodes.PIN_MISSING,
                        "Every evaluated Case requires its exact expected and review counterpart");
            }
            MetadataSnapshot snapshot = snapshots.get(caseInput.metadataSnapshot());
            if (snapshot == null) {
                throw EvaluationRunException.of(
                        EvaluationRunErrorCodes.PIN_MISSING, "A Case references an unloaded Metadata snapshot");
            }
            FixtureDescriptor contextFixture = bundle.fixture(caseInput.executionContextFixture());
            if (contextFixture == null || contextFixture.role() != FixtureRole.EXECUTION_CONTEXT) {
                throw EvaluationRunException.of(
                        EvaluationRunErrorCodes.PIN_MISSING,
                        "A Case requires its pinned execution-context fixture");
            }
            List<EvaluationCasePins.FixturePin> fixturePins = new ArrayList<>();
            Set<String> caseFixtureIds = new LinkedHashSet<>();
            for (String fixtureId : caseInput.fixtures()) {
                FixtureDescriptor descriptor = bundle.fixture(fixtureId);
                if (descriptor == null || !descriptor.role().isMemberSetFixture()) {
                    throw EvaluationRunException.of(
                            EvaluationRunErrorCodes.PIN_MISSING, "A Case references an unknown fixture");
                }
                if (!caseFixtureIds.add(fixtureId)) {
                    throw EvaluationRunException.of(
                            EvaluationRunErrorCodes.PIN_CONTRADICTORY,
                            "A Case must pin each fixture exactly once");
                }
                FixtureExecutionServices services = requireHost(
                        host.fixtureServices(fixtureId), "fixture services");
                fixturePins.add(new EvaluationCasePins.FixturePin(
                        descriptor.fixtureId(), descriptor.fixtureVersion(), descriptor.payloadHash(),
                        services.mapping().mappingVersion(), services.mapping().mappingHash(),
                        services.scopeBinding().bindingVersion(), services.scopeBinding().bindingHash(),
                        services.fxRateSnapshot().snapshotId(), services.fxRateSnapshot().contentHash()));
            }
            // A high-risk Case runs exactly the frozen five repeat attempts and every other Case exactly once,
            // whatever a candidate manifest asked for: the repeat count is a T13 constant, not a request field.
            int repeats = caseInput.riskLevel().requiresRepeatConsistency() ? REQUIRED_HIGH_RISK_REPEATS : 1;
            EvaluationCasePins casePins = new EvaluationCasePins(
                    caseInput.caseId(),
                    caseInput.category().name(),
                    caseInput.riskLevel().name(),
                    repeats,
                    caseInput.currentSegment() != null,
                    reference.caseHash(),
                    reference.expectedHash(),
                    reference.reviewHash(),
                    snapshot.snapshotId(),
                    snapshot.snapshotVersion(),
                    snapshot.contentHash(),
                    caseInput.executionContextFixture(),
                    contextFixture.payloadHash(),
                    caseInput.locales().get(0).value(),
                    contextFixture.asOf(),
                    contextFixture.timezone().getId(),
                    contextFixture.dataWatermark(),
                    fixturePins);
            verifyTrustedContext(
                    bundle, casePins, requireHost(host.trustedContext(casePins), "trusted context"));
            pins.add(casePins);
        }
        return pins;
    }

    private EvaluationRunContract.ArtifactPins artifactPins(SegmentSkillDefinition skill) {
        List<EvaluationRunContract.ArtifactPin> artifacts = new ArrayList<>();
        artifacts.add(EvaluationRunContract.ArtifactPin.present("system-prompt", SegmentPromptContract.HASH));
        artifacts.add(EvaluationRunContract.ArtifactPin.present("skill", skill.canonicalHash()));
        artifacts.add(EvaluationRunContract.ArtifactPin.present(
                "tool-schema", CANONICAL_JSON.hash(SegmentToolCatalog.fromSkill(skill).ordered())));
        return new EvaluationRunContract.ArtifactPins(artifacts);
    }

    private EvaluationRunContract.ParserPins parserPins(SegmentSkillDefinition skill) {
        String intentVersionText = skill.contractPins().intentVersion();
        return new EvaluationRunContract.ParserPins(
                intentVersionText,
                intentVersionText,
                skill.contractPins().resolverVersion(),
                CanonicalizerVersions.CANONICALIZER_VERSION,
                skill.contractPins().validatorVersion(),
                skill.contractPins().plannerVersion());
    }

    /**
     * Canonicalizes, plans or genuinely refuses every expected document once, at freeze time.
     *
     * <p>Freezing is disposition-aware, because "the reviewed answer is a valid document" and "the reviewed
     * answer is a deterministic refusal" are different claims:
     *
     * <ul>
     *   <li>{@code RESOLVED}: the expected DSL must validate and plan. The expected canonical AST hash comes
     *       from the genuine T07 validator and canonicalizer, the expected Query Plan hash from the genuine T08
     *       planner over the exact frozen Metadata snapshot and trusted context. A missing, unparsable,
     *       invalid or unplannable reviewed document is input/corpus invalidity, never a silent skip and never
     *       a model mismatch.</li>
     *   <li>{@code INVALID}: the expected DSL is the reviewed invalid document. The genuine T07 validator must
     *       refuse it with exactly the declared issue-code sequence; its editable DSL hash is frozen as the
     *       safe evidence that distinguishes this reviewed invalid document, and no Query Plan is required. A
     *       reviewed invalid DSL that actually validates, or whose genuine issue sequence differs from the
     *       declared one, is input/corpus invalidity.</li>
     *   <li>{@code NEEDS_INPUT}, {@code UNSUPPORTED}, {@code DENIED}: no AST or Plan is expected or allowed, so
     *       there is nothing to freeze and their absence in the pipeline is a match.</li>
     * </ul>
     */
    private ExpectedEvidence freezeExpectedEvidence(
            CorpusBundle bundle,
            Map<String, MetadataSnapshot> snapshots,
            List<EvaluationCasePins> casePins,
            EvaluationRunHost host) {
        Map<String, EvaluationCasePins> pinsByCase = new LinkedHashMap<>();
        for (EvaluationCasePins pins : casePins) {
            pinsByCase.put(pins.caseId(), pins);
        }
        Map<String, ContentHash> astHashes = new LinkedHashMap<>();
        Map<String, ContentHash> planHashes = new LinkedHashMap<>();
        Map<String, ContentHash> invalidDslHashes = new LinkedHashMap<>();
        Map<String, String> codes = new LinkedHashMap<>();
        for (CaseInput caseInput : bundle.cases()) {
            ExpectedResult expected = bundle.expectedFor(caseInput.caseId());
            if (expected == null) {
                throw EvaluationRunException.of(
                        EvaluationRunErrorCodes.PIN_MISSING,
                        "Every frozen Case requires its exact expected result");
            }
            if (!expected.permitsCanonicalAst()) {
                // The expected disposition prohibits a canonical AST and a Query Plan by construction, so no
                // expected evidence exists to freeze and their absence in the pipeline is a match.
                continue;
            }
            if (expected.expectedCanonicalAst() == null) {
                // A resolved expectation must carry its reviewed AST, and an invalid expectation must carry
                // the invalid DSL its declared issue sequence was derived from: either way the evidence is
                // missing, which is input/corpus invalidity rather than a silent skip.
                codes.put(caseInput.caseId(), EXPECTED_EVIDENCE_AST_MISSING);
                continue;
            }
            EvaluationCasePins pins = pinsByCase.get(caseInput.caseId());
            MetadataSnapshot snapshot = snapshots.get(caseInput.metadataSnapshot());
            if (snapshot == null) {
                throw EvaluationRunException.of(
                        EvaluationRunErrorCodes.PIN_MISSING, "A Case references an unloaded Metadata snapshot");
            }
            TrustedExecutionContext context = requireHost(host.trustedContext(pins), "trusted context");
            SegmentDsl reviewedDsl;
            try {
                reviewedDsl = dslCodec.parse(CANONICAL_JSON.writeString(expected.expectedCanonicalAst()));
            } catch (RuntimeException unreadable) {
                codes.put(caseInput.caseId(), EXPECTED_EVIDENCE_AST_UNREADABLE);
                continue;
            }
            if (!bindsReviewedSnapshot(bundle, caseInput.metadataSnapshot(), reviewedDsl, snapshot)) {
                codes.put(caseInput.caseId(), EXPECTED_EVIDENCE_BINDING_MISMATCH);
                continue;
            }
            SegmentDsl expectedDsl = ReviewedExpectedEvidence.pinTo(reviewedDsl, snapshot);
            SegmentValidationResult validation = new SegmentValidator(host.authorizationService())
                    .validate(expectedDsl, snapshot, context, host.validationPolicy());
            if (expected.expectsInvalidEvidence()) {
                freezeInvalidExpectedEvidence(caseInput, expected, validation, invalidDslHashes, codes);
                continue;
            }
            if (validation == null || !validation.valid() || validation.canonicalAstHash() == null) {
                codes.put(caseInput.caseId(), EXPECTED_EVIDENCE_AST_INVALID);
                continue;
            }
            SemanticQueryPlan plan;
            try {
                plan = new SegmentQueryPlanner(host.authorizationService())
                        .plan(validation, snapshot, context);
            } catch (RuntimeException unplannable) {
                codes.put(caseInput.caseId(), EXPECTED_EVIDENCE_PLAN_INVALID);
                continue;
            }
            if (plan == null || plan.planHash() == null) {
                codes.put(caseInput.caseId(), EXPECTED_EVIDENCE_PLAN_INVALID);
                continue;
            }
            astHashes.put(caseInput.caseId(), validation.canonicalAstHash());
            planHashes.put(caseInput.caseId(), plan.planHash());
        }
        return new ExpectedEvidence(astHashes, planHashes, invalidDslHashes, codes);
    }

    /**
     * Freezes one reviewed {@code INVALID} expectation against the genuine T07 validator.
     *
     * <p>The reviewed answer is a deterministic refusal, so the freeze proves the expectation is coherent
     * rather than valid: T07 must genuinely refuse the reviewed DSL with exactly the declared issue-code
     * sequence, in order. A refusal that is not reproduced, or one whose sequence differs, is a contradictory
     * reviewed expectation and is recorded as input/corpus invalidity. When the refusal is reproduced, the
     * reviewed document's editable DSL hash is frozen as the safe evidence that identifies exactly which
     * invalid document was reviewed and expected.
     */
    private static void freezeInvalidExpectedEvidence(
            CaseInput caseInput,
            ExpectedResult expected,
            SegmentValidationResult validation,
            Map<String, ContentHash> invalidDslHashes,
            Map<String, String> codes) {
        if (validation == null || validation.valid()) {
            codes.put(caseInput.caseId(), EXPECTED_EVIDENCE_INVALID_DSL_VALIDATED);
            return;
        }
        List<String> generated = new ArrayList<>();
        for (Issue issue : validation.issues()) {
            generated.add(issue.code());
        }
        if (!generated.equals(new ArrayList<>(expected.expectedValidationIssueCodes()))
                || validation.editableDslHash() == null) {
            codes.put(caseInput.caseId(), EXPECTED_EVIDENCE_ISSUE_SEQUENCE_CONTRADICTED);
            return;
        }
        invalidDslHashes.put(caseInput.caseId(), validation.editableDslHash());
    }

    /**
     * Proves a reviewed expected document was pinned to the exact snapshot its Case names.
     *
     * <p>The corpus catalog pins a Metadata payload by the descriptor content hash of its bytes; T04
     * recomputes the snapshot's own material content hash on parse. A reviewed expected document may name
     * either pin of the same snapshot — never a third value and never another snapshot — before the runner
     * translates it onto the frozen snapshot for the genuine T07 and T08 stages. A binding that names a
     * different snapshot is corpus invalidity, never a re-pin.
     */
    private static boolean bindsReviewedSnapshot(
            CorpusBundle bundle, String snapshotId, SegmentDsl reviewed, MetadataSnapshot snapshot) {
        MetadataSnapshotDescriptor descriptor = null;
        for (MetadataSnapshotDescriptor candidate : bundle.metadataCatalog().snapshots()) {
            if (candidate.snapshotId().equals(snapshotId)) {
                descriptor = candidate;
                break;
            }
        }
        if (descriptor == null) {
            return false;
        }
        MetadataBinding binding = reviewed.metadata();
        return binding.snapshotId().equals(descriptor.snapshotId())
                && binding.snapshotVersion().equals(descriptor.snapshotVersion())
                && (binding.contentHash().equals(descriptor.contentHash())
                        || binding.contentHash().equals(snapshot.contentHash()));
    }

    private EvaluationRunContract.TimePins timePins(CorpusBundle bundle) {
        FixtureDescriptor reference = null;
        for (FixtureDescriptor descriptor : bundle.fixtureCatalog().fixtures()) {
            if (reference == null) {
                reference = descriptor;
                continue;
            }
            if (!descriptor.asOf().equals(reference.asOf())
                    || !descriptor.timezone().equals(reference.timezone())
                    || !descriptor.dataWatermark().equals(reference.dataWatermark())) {
                throw EvaluationRunException.of(
                        EvaluationRunErrorCodes.PIN_CONTRADICTORY,
                        "A frozen corpus must share one time context across its fixtures");
            }
        }
        if (reference == null) {
            throw EvaluationRunException.of(
                    EvaluationRunErrorCodes.PIN_MISSING, "A frozen corpus requires a fixture catalog");
        }
        return new EvaluationRunContract.TimePins(
                reference.asOf(), reference.timezone().getId(), reference.dataWatermark());
    }

    private static <T> T requireHost(T value, String name) {
        if (value == null) {
            throw EvaluationRunException.of(
                    EvaluationRunErrorCodes.HOST_SERVICE_MISSING, "The host did not supply its " + name);
        }
        return value;
    }

    /**
     * Binds the host trusted context at freeze time to the exact reviewed execution-context fixture.
     *
     * <p>Every identity, snapshot, locale, timezone, DataScope and time value the host will execute a Case
     * under must be exactly the one the reviewed fixture and Case pins carry. A host that substitutes its own
     * tenant, program, snapshot, locale, scope, {@code asOf}, timezone or watermark is input/pin invalidity
     * rather than an ordinary model mismatch, and the run is refused before the first Case starts. The same
     * comparison is re-proved during execution, so a substitution that appears later is refused too.
     */
    private static void verifyTrustedContext(
            CorpusBundle bundle, EvaluationCasePins pins, TrustedExecutionContext context) {
        TrustedExecutionContextRef reference = bundle.executionContext(pins.executionContextFixture());
        if (reference == null) {
            throw EvaluationRunException.of(
                    EvaluationRunErrorCodes.PIN_MISSING,
                    "A Case requires its reviewed execution-context fixture payload");
        }
        TrustedExecutionContextRef actual = context.snapshotRef();
        if (!context.tenantId().equals(reference.tenantId())
                || !context.programId().equals(reference.programId())
                || !context.userId().equals(reference.userId())
                || !context.dataScopeId().equals(reference.dataScopeId())
                || !actual.metadataSnapshotId().equals(reference.metadataSnapshotId())
                || !actual.locale().equals(reference.locale())
                || !actual.timezone().equals(reference.timezone())
                || !actual.reportingCurrency().equals(reference.reportingCurrency())
                || !actual.asOf().equals(reference.asOf())
                || !actual.dataWatermark().equals(reference.dataWatermark())) {
            throw EvaluationRunException.of(
                    EvaluationRunErrorCodes.PIN_STALE,
                    "The host trusted context does not match the reviewed execution-context fixture");
        }
    }

    // ------------------------------------------------------------------ execution

    /**
     * Executes one already-frozen run. Package-private so a focused test can execute a deliberately tampered
     * frozen contract; it is the single execution path and performs no other work.
     */
    EvaluationReport execute(FrozenRun frozen) {
        EvaluationRunContract contract = frozen.contract();
        Map<String, CaseInput> caseInputs = new LinkedHashMap<>();
        Map<String, ExpectedResult> expectations = new LinkedHashMap<>();
        for (CaseInput caseInput : frozen.bundle().cases()) {
            caseInputs.put(caseInput.caseId(), caseInput);
        }
        for (ExpectedResult expected : frozen.bundle().expectedResults()) {
            expectations.put(expected.caseId(), expected);
        }

        List<EvaluationReport.CaseOutcome> caseOutcomes = new ArrayList<>();
        List<AttemptRecord> records = new ArrayList<>();
        boolean runInvalid = false;

        for (EvaluationCasePins pins : contract.cases()) {
            CaseInput caseInput = caseInputs.get(pins.caseId());
            ExpectedResult expected = expectations.get(pins.caseId());
            List<AttemptRecord> caseRecords = new ArrayList<>();
            List<EvaluationReport.AttemptOutcome> attempts = new ArrayList<>();
            List<Integer> notRunAttemptNumbers = new ArrayList<>();
            String notRunCode = null;

            for (int attemptNumber = 1; attemptNumber <= pins.repeats(); attemptNumber++) {
                if (runInvalid) {
                    notRunAttemptNumbers.add(attemptNumber);
                    notRunCode = NOT_RUN_DUE_TO_RUN_INVALID;
                    continue;
                }
                AttemptRecord record = executeAttempt(contract, pins, caseInput, expected, frozen, attemptNumber);
                caseRecords.add(record);
                records.add(record);
                if (record.taxonomy() != null && record.taxonomy().invalidatesRun()) {
                    runInvalid = true;
                }
                if (record.outcome().evidence() == null) {
                    throw EvaluationRunException.of(
                            EvaluationRunErrorCodes.COMPARISON_INVALID, "An executed attempt requires evidence");
                }
            }

            int firstIndex = records.size() - caseRecords.size();
            boolean consistent = repeatConsistent(caseRecords);
            for (int i = 0; i < caseRecords.size(); i++) {
                AttemptRecord settled = caseRecords.get(i).withRepeat(consistent, pins.repeats());
                caseRecords.set(i, settled);
                records.set(firstIndex + i, settled);
                attempts.add(settled.outcome());
            }
            caseOutcomes.add(new EvaluationReport.CaseOutcome(
                    pins.caseId(), pins.category(), pins.riskLevel(), pins.repeats(), attempts,
                    notRunAttemptNumbers.size(), notRunAttemptNumbers, notRunCode));
        }

        Map<MetricId, int[]> overall = tally(records);
        List<EvaluationReport.MetricValue> metrics = metricValues(overall);
        Map<String, List<EvaluationReport.MetricValue>> perCategory = grouped(records, caseOutcomes, true);
        Map<String, List<EvaluationReport.MetricValue>> perRisk = grouped(records, caseOutcomes, false);

        int executed = records.size();
        int resolved = 0;
        int invalid = 0;
        int mismatched = 0;
        int failed = 0;
        for (AttemptRecord record : records) {
            if (Disposition.RESOLVED == record.disposition()) {
                resolved++;
            }
            if (Disposition.INVALID == record.disposition()) {
                invalid++;
            }
            if (record.evidence().mismatched()) {
                mismatched++;
            }
            if (record.taxonomy() != null) {
                failed++;
            }
        }
        EvaluationReport.Summary summary = new EvaluationReport.Summary(
                contract.cases().size(), contract.requiredAttemptCount(), executed,
                contract.requiredAttemptCount() - executed, resolved, invalid, mismatched, failed, runInvalid);

        return EvaluationReport.of(
                contract.reportId(), contract, runInvalid, caseOutcomes, metrics, perCategory, perRisk, summary);
    }

    private static boolean repeatConsistent(List<AttemptRecord> records) {
        if (records.isEmpty()) {
            return false;
        }
        Disposition disposition = records.get(0).disposition();
        ContentHash ast = records.get(0).canonicalAstHash();
        for (AttemptRecord record : records) {
            if (record.disposition() != disposition || !Objects.equals(ast, record.canonicalAstHash())) {
                return false;
            }
        }
        return true;
    }

    private AttemptRecord executeAttempt(
            EvaluationRunContract contract,
            EvaluationCasePins pins,
            CaseInput caseInput,
            ExpectedResult expected,
            FrozenRun frozen,
            int attemptNumber) {
        EvaluationRunHost host = frozen.host();
        String replayId = ReplayIdentifier.of(
                contract.contentHash(), pins.contentHash(), attemptNumber,
                pins.isRefinement() ? "refinement" : "create");
        String runId = "run-" + pins.caseId() + "-a" + attemptNumber;
        String conversationId = "conversation-" + pins.caseId() + "-a" + attemptNumber;

        EvaluationObservation observation = new EvaluationObservation();
        RecordingModelPort port = new RecordingModelPort(host.modelPort());
        TrustedExecutionContext context = requireHost(host.trustedContext(pins), "trusted context");
        MetadataSnapshot snapshot = frozen.snapshots().get(pins.metadataSnapshotId());

        Disposition disposition;
        String failureCode;
        EvaluationReport.ComparisonEvidence evidence;
        try {
            if (snapshot == null) {
                throw EvaluationRunException.of(
                        EvaluationRunErrorCodes.PIN_MISSING, "A Case references an unloaded Metadata snapshot");
            }
            // A trusted context substituted after the freeze is a moved pin, not a model answer: it is refused
            // before the Agent or the refinement adapter sees it, so the run is invalid rather than mismatched.
            verifyTrustedContext(frozen.bundle(), pins, context);
            if (pins.isRefinement()) {
                SegmentRefinementEnvironment environment = requireHost(
                        host.refinementEnvironment(pins, observation), "refinement environment");
                SegmentRefinementPatch patch = host.refinementPatch(pins);
                if (patch == null) {
                    throw EvaluationRunException.of(
                            EvaluationRunErrorCodes.PIN_MISSING,
                            "A refinement Case requires its host-pinned proposal");
                }
                SegmentDsl current = dslCodec.parse(
                        CANONICAL_JSON.writeString(caseInput.currentSegment().content()));
                SegmentRefinementResult result = new SegmentRefinementAdapter(environment).invoke(
                        new AgentEnvelope<>(ContractVersions.CURRENT, runId, conversationId,
                                new SegmentRefinementRequest(current, patch)));
                disposition = result.disposition();
                failureCode = result.failureCode();
            } else {
                SegmentAgentEnvironment environment = requireHost(
                        host.agentEnvironment(pins, observation, port), "agent environment");
                SegmentAgentResult result = new SegmentAgentAdapter(environment).invoke(
                        new AgentEnvelope<>(ContractVersions.CURRENT, runId, conversationId,
                                new SegmentAgentRequest(caseInput.utterance())));
                disposition = result.disposition();
                failureCode = result.failureCode();
            }
            // Comparison runs the genuine T09 fixture executor over the observed plan, so it can fail for
            // infrastructure reasons too and belongs inside the same fail-closed boundary.
            evidence = compare(pins, expected, observation, context, snapshot, frozen, host, disposition);
        } catch (RuntimeException failure) {
            // An unexpected unchecked failure — including an observer or trusted-context failure the adapter
            // itself did not convert into a bounded result — is infrastructure, not a model answer: it is
            // recorded with the one infrastructure class and makes the whole report INVALID rather than
            // silently dropping the Case or aborting the run without a report.
            return infrastructureAttempt(attemptNumber, replayId, port, pins, expected);
        }

        FailureTaxonomy taxonomy = taxonomyOf(port, disposition, failureCode, evidence, expected);
        ContentHash astHash = observation.validation() == null
                ? null : observation.validation().canonicalAstHash();
        EvaluationReport.AttemptOutcome outcome = new EvaluationReport.AttemptOutcome(
                attemptNumber, replayId, disposition.name(), failureCode, taxonomy,
                observedStages(observation), port.calls().size(), port.repairAttempted(),
                port.repairAttempted() && observation.intentHash() != null, evidence);
        return new AttemptRecord(
                pins.caseId(), attemptNumber, disposition, taxonomy, astHash, outcome, evidence, expected,
                false, pins.repeats());
    }

    private AttemptRecord infrastructureAttempt(
            int attemptNumber,
            String replayId,
            RecordingModelPort port,
            EvaluationCasePins pins,
            ExpectedResult expected) {
        EvaluationReport.ComparisonEvidence evidence = EvaluationReport.ComparisonEvidence.unevaluated(
                expected.expectedDisposition(), Disposition.INVALID.name());
        FailureTaxonomy taxonomy = FailureTaxonomy.PROVIDER_OR_INFRASTRUCTURE_FAILURE;
        EvaluationReport.AttemptOutcome outcome = new EvaluationReport.AttemptOutcome(
                attemptNumber, replayId, Disposition.INVALID.name(), null, taxonomy, List.of(),
                port.calls().size(), port.repairAttempted(), false, evidence);
        return new AttemptRecord(pins.caseId(), attemptNumber, Disposition.INVALID, taxonomy, null, outcome,
                evidence, expected, false, pins.repeats());
    }

    private static List<String> observedStages(EvaluationObservation observation) {
        List<String> stages = new ArrayList<>();
        for (EvaluationObservation.ObservedStage stage : observation.stages()) {
            stages.add(stage.name());
        }
        return stages;
    }

    private FailureTaxonomy taxonomyOf(
            RecordingModelPort port,
            Disposition disposition,
            String failureCode,
            EvaluationReport.ComparisonEvidence evidence,
            ExpectedResult expected) {
        if (port.providerFailed()) {
            return FailureTaxonomy.PROVIDER_OR_INFRASTRUCTURE_FAILURE;
        }
        if (isObserverOrAuditCode(failureCode)) {
            return FailureTaxonomy.OBSERVER_OR_AUDIT_FAILURE;
        }
        if (evidence.expectedEvidenceCode() != null) {
            // The reviewed expectation itself could not be frozen: that is input/corpus invalidity, not a
            // model mismatch and not a pipeline rejection.
            return FailureTaxonomy.INPUT_OR_PIN_INVALIDITY;
        }
        if (disposition == Disposition.INVALID) {
            if (expected.expectsInvalidEvidence()) {
                // A reviewed invalid outcome is an expected deterministic refusal, not a pipeline or
                // infrastructure failure. It is a mismatch exactly when an applicable compared dimension
                // disagrees with the reviewed refusal; otherwise the Case succeeded and has no taxonomy.
                return evidence.mismatched() ? FailureTaxonomy.COMPARISON_MISMATCH : null;
            }
            FailureTaxonomy classified = FailureTaxonomy.classify(failureCode);
            if (classified == FailureTaxonomy.FIRST_PASS_SCHEMA_REJECTION && port.repairAttempted()) {
                return FailureTaxonomy.REPAIR_SCHEMA_REJECTION;
            }
            return classified;
        }
        if (evidence.fixtureExecutionFailed()) {
            // A genuine fixture execution failure is infrastructure, not a model or pipeline outcome: it makes
            // the report INVALID without making every semantic validation rejection invalid too.
            return FailureTaxonomy.FIXTURE_EXECUTION_FAILURE;
        }
        if (!evidence.mismatched()) {
            return null;
        }
        return expected.expectedDisposition() == disposition
                ? FailureTaxonomy.COMPARISON_MISMATCH
                : FailureTaxonomy.MODEL_SEMANTIC_MISMATCH;
    }

    private static boolean isObserverOrAuditCode(String failureCode) {
        return SegmentAgentErrorCodes.OBSERVATION_FAILED.equals(failureCode)
                || SegmentRefinementAdapterErrorCodes.OBSERVATION_FAILED.equals(failureCode)
                || SegmentAgentErrorCodes.AUDIT_UNAVAILABLE.equals(failureCode)
                || SegmentRefinementAdapterErrorCodes.AUDIT_UNAVAILABLE.equals(failureCode);
    }

    // ------------------------------------------------------------------ comparison

    private EvaluationReport.ComparisonEvidence compare(
            EvaluationCasePins pins,
            ExpectedResult expected,
            EvaluationObservation observation,
            TrustedExecutionContext context,
            MetadataSnapshot snapshot,
            FrozenRun frozen,
            EvaluationRunHost host,
            Disposition actualDisposition) {
        boolean refinementAttempt = pins.isRefinement();
        String expectedEvidenceCode = frozen.expectedEvidenceCodes().get(expected.caseId());
        if (expectedEvidenceCode != null) {
            // The reviewed expectation could not be canonicalized or planned by the genuine stages, so it is
            // recorded as input/corpus invalidity with its bounded code rather than compared as if it were
            // valid evidence. That is an explicit record, never a silent skip.
            return EvaluationReport.ComparisonEvidence.unevaluated(
                    expected.expectedDisposition(), actualDisposition.name(), refinementAttempt,
                    expectedEvidenceCode);
        }
        SegmentIntent intent = observation.intent();
        ResolutionResult resolution = observation.resolution();
        SegmentValidationResult validation = observation.validation();
        SemanticQueryPlan plan = observation.plan();
        SegmentPreviewCard card = observation.previewCard();

        List<IntentClause> clauses = intent == null ? List.of() : intent.clauses();
        Map<String, IntentClause> byClauseId = new LinkedHashMap<>();
        for (IntentClause clause : clauses) {
            byClauseId.put(clause.clauseId(), clause);
        }

        boolean intentSlotsMatched = clauses.size() == expected.expectedIntentSlots().size();
        boolean sourceEvidenceMatched = true;
        for (ExpectedIntentSlot slot : expected.expectedIntentSlots()) {
            IntentClause clause = byClauseId.get(slot.slotId());
            if (clause == null
                    || !clause.concept().equals(slot.concept())
                    || !clause.operator().name().equals(slot.operatorIntent())
                    || !plainValue(clause.value()).equals(plainJson(slot.value()))) {
                intentSlotsMatched = false;
            }
            if (slot.grounded()) {
                SourceSpan span = spanOf(clause);
                // The full source-span evidence is compared: the exact text and both UTF-16 offsets, so a
                // coincidentally equal string at the wrong position is still a mismatch.
                if (span == null || !span.text().equals(slot.sourceSpan().text())
                        || span.start() != slot.sourceSpan().start()
                        || span.end() != slot.sourceSpan().end()) {
                    sourceEvidenceMatched = false;
                }
            }
        }

        Set<String> selected = new TreeSet<>();
        Set<String> rejected = new TreeSet<>();
        if (resolution != null) {
            for (ClauseResolution clause : resolution.clauses()) {
                if (clause.accepted() != null) {
                    selected.add(clause.accepted());
                }
                for (RejectedCandidate candidate : clause.rejected()) {
                    rejected.add(candidate.definitionId());
                }
            }
        }
        boolean selectedMatched = selected.equals(new TreeSet<>(expected.expectedSelectedCandidateIds()));
        boolean rejectedMatched = rejected.equals(new TreeSet<>(expected.expectedRejectedCandidateIds()));

        ContentHash actualAstHash = validation == null ? null : validation.canonicalAstHash();
        ContentHash expectedAstHash = frozen.expectedAstHashes().get(expected.caseId());
        // Only a resolved expectation claims a canonical AST and a Query Plan. A disposition that prohibits them
        // — including a deterministic refusal — matches exactly when the pipeline also produced none; producing
        // evidence the expectation forbids is itself the mismatch.
        boolean astApplicable = expected.requiresResolvedEvidence();
        boolean astMatched = astApplicable
                ? expectedAstHash != null && expectedAstHash.equals(actualAstHash)
                : actualAstHash == null;

        List<String> expectedIssues = new ArrayList<>(expected.expectedValidationIssueCodes());
        List<String> actualIssues = new ArrayList<>();
        if (validation != null) {
            for (Issue issue : validation.issues()) {
                actualIssues.add(issue.code());
            }
        }
        boolean issuesMatched = expectedIssues.equals(actualIssues);

        // A reviewed invalid expectation freezes the editable DSL hash of exactly the invalid document it
        // expects, because an invalid document has no canonical AST to compare. It is the safe evidence that
        // distinguishes that reviewed document from any other refusal carrying the same issue codes.
        ContentHash expectedInvalidHash = frozen.expectedInvalidDslHashes().get(expected.caseId());
        ContentHash actualInvalidHash = validation == null ? null : validation.editableDslHash();
        boolean invalidDslApplicable = expected.expectsInvalidEvidence();
        boolean invalidDslMatched = !invalidDslApplicable
                || (expectedInvalidHash != null && expectedInvalidHash.equals(actualInvalidHash));

        // Two distinct concepts are reported separately and never conflated: whether the actual Plan, Preview
        // and DSL pins are internally coherent, and whether the actual Plan equals the exact expected Plan the
        // freeze produced from the reviewed expected evidence.
        SegmentDsl effectiveDocument = observation.document() != null
                ? observation.document() : observation.refinedDocument();
        boolean planCoherent = plan != null && card != null
                && plan.planHash().equals(card.planHash())
                && effectiveDocument != null
                && card.dslHash().equals(effectiveDocument.editableDslHash())
                && validation != null && card.canonicalAstHash().equals(validation.canonicalAstHash());
        ContentHash expectedPlanHash = frozen.expectedPlanHashes().get(expected.caseId());
        boolean planApplicable = astApplicable;
        boolean expectedPlanMatched = planApplicable && plan != null && expectedPlanHash != null
                && expectedPlanHash.equals(plan.planHash());

        List<String> expectedQuestions = new ArrayList<>(expected.expectedClarificationQuestionCodes());
        List<String> actualQuestions = new ArrayList<>();
        if (resolution != null && resolution.clarification() != null) {
            QuestionCode code = resolution.clarification().questionCode();
            actualQuestions.add(code.name());
        }
        boolean questionsMatched = expectedQuestions.equals(actualQuestions);

        // The grounded-explanation dimension is applicable exactly where the accepted create path genuinely
        // produces one: the T11 adapter observes an explanation only on its terminal success, so a refinement
        // attempt — which presents no comparable reviewed claim sequence — and every non-resolved verdict report
        // applicability false and contribute no mismatch from an absent claim list. When it is applicable, the
        // reviewed claim identifiers are compared exactly as declared, without translating the corpus's
        // upper-case machine codes into the accepted positional claim-N identifiers.
        GroundedExplanation explanation = observation.explanation();
        boolean explanationApplicable = !refinementAttempt && Disposition.RESOLVED == actualDisposition;
        // The reviewed identifiers are the exact immutable sequence the frozen expectation carries, and the
        // accepted identifiers are the exact sequence the T11 explanation emitted, in declaration order. The two
        // are compared as ordered lists so that a reversed sequence, a duplicated identifier or a different
        // cardinality is a mismatch; a set comparison would silently treat every one of those as equal.
        List<String> expectedClaims = expected.groundedExplanationClaimIds();
        List<String> actualClaims = new ArrayList<>();
        if (explanation != null && explanation.claims() != null) {
            for (ExplanationClaim claim : explanation.claims()) {
                actualClaims.add(claim.claimId());
            }
        }
        boolean explanationClaimsMatched = !explanationApplicable
                || (explanation != null && claimSequencesMatch(expectedClaims, actualClaims));

        Set<String> forbidden = new TreeSet<>(expected.forbiddenAssumptions());
        Set<String> assumptions = new TreeSet<>();
        for (IntentClause clause : clauses) {
            assumptions.addAll(clause.assumptions());
        }
        assumptions.retainAll(forbidden);
        boolean assumptionsMatched = assumptions.isEmpty();

        MemberSetOutcome memberSetOutcome =
                memberSets(pins, expected, plan, frozen.datasets(), frozen.bundle(), host);
        List<EvaluationReport.MemberSetComparison> memberSets = memberSetOutcome.comparisons();

        boolean refinementMatched = true;
        String expectedOperation = null;
        String actualOperation = null;
        String expectedTarget = null;
        String actualTarget = null;
        String expectedScope = null;
        String actualScope = null;
        boolean targetMatched = true;
        boolean scopeMatched = true;
        RefinementTargetPlaceholder target = expected.refinementTarget();
        if (target != null) {
            expectedOperation = target.operation().name();
            expectedTarget = target.targetRef();
            expectedScope = target.scopeRef();
            // The accepted operation is read from the typed receipt the genuine T12 editor emitted, never
            // inferred by diffing two trees. A placeholder that matches zero or several accepted operations
            // does not identify one, so it is a deterministic mismatch rather than a guessed precedence.
            SegmentRefinementReceipt.Operation applicable =
                    applicableOperation(observation.refinementReceipt());
            actualOperation = applicable == null ? null : applicable.kind().name();
            actualTarget = applicable == null ? null : applicable.targetNodeId();
            actualScope = applicable == null ? null : applicable.parentNodeId();
            targetMatched = applicable != null && equivalent(expectedTarget, actualTarget);
            scopeMatched = applicable != null && equivalent(expectedScope, actualScope);
            refinementMatched = expectedOperation.equals(actualOperation) && targetMatched && scopeMatched;
        }

        return new EvaluationReport.ComparisonEvidence(
                T13Versions.COMPARISON_VERSION,
                refinementAttempt,
                null,
                expected.expectedDisposition() == actualDisposition,
                expected.expectedDisposition().name(),
                actualDisposition.name(),
                intentSlotsMatched,
                expected.expectedIntentSlots().size(),
                clauses.size(),
                sourceEvidenceMatched,
                selectedMatched,
                rejectedMatched,
                astMatched,
                expectedAstHash,
                actualAstHash,
                issuesMatched,
                expectedIssues,
                actualIssues,
                invalidDslMatched,
                expectedInvalidHash,
                actualInvalidHash,
                planCoherent,
                planApplicable,
                expectedPlanMatched,
                expectedPlanHash,
                plan == null ? null : plan.planHash(),
                questionsMatched,
                expectedQuestions,
                actualQuestions,
                explanationApplicable,
                explanationClaimsMatched,
                List.copyOf(expectedClaims),
                List.copyOf(actualClaims),
                assumptionsMatched,
                refinementMatched,
                expectedOperation,
                actualOperation,
                targetMatched,
                scopeMatched,
                memberSetOutcome.failed(),
                memberSets);
    }

    /**
     * Exact ordered equality of the reviewed claim identifiers and the accepted explanation's claim identifiers.
     *
     * <p>Order and cardinality are both significant and duplicates are not collapsed: only the exact sequence
     * matches. This is the comparison the grounded-explanation dimension uses, so a reversed sequence or a
     * repeated/omitted identifier is a mismatch rather than a set difference that happens to be empty.
     */
    static boolean claimSequencesMatch(List<String> expectedClaims, List<String> actualClaims) {
        return expectedClaims.equals(actualClaims);
    }

    private static boolean equivalent(String expected, String actual) {
        return expected == null ? actual == null : expected.equals(actual);
    }

    /**
     * The single accepted operation a reviewed singular refinement placeholder identifies, or {@code null}.
     *
     * <p>The T03 placeholder names one operation kind, target and scope but no operation id, so it can only
     * identify one accepted operation when the accepted patch genuinely carries exactly one. A patch with
     * zero or several operations therefore identifies none: returning {@code null} makes that ambiguity a
     * deterministic mismatch instead of inventing a precedence or guessing by kind.
     */
    private static SegmentRefinementReceipt.Operation applicableOperation(
            SegmentRefinementReceipt receipt) {
        if (receipt == null || receipt.operations().size() != 1) {
            return null;
        }
        return receipt.operations().get(0);
    }

    /** The bounded member-set comparison plus whether the genuine fixture execution refused. */
    private record MemberSetOutcome(List<EvaluationReport.MemberSetComparison> comparisons, boolean failed) {
    }

    private MemberSetOutcome memberSets(
            EvaluationCasePins pins,
            ExpectedResult expected,
            SemanticQueryPlan plan,
            Map<String, FixtureDataset> datasets,
            CorpusBundle bundle,
            EvaluationRunHost host) {
        List<EvaluationReport.MemberSetComparison> comparisons = new ArrayList<>();
        boolean failed = false;
        Map<String, EvaluationCasePins.FixturePin> pinsById = new LinkedHashMap<>();
        for (EvaluationCasePins.FixturePin fixturePin : pins.fixtures()) {
            pinsById.put(fixturePin.fixtureId(), fixturePin);
        }
        for (Map.Entry<String, MemberIdSet> entry : expected.expectedMemberIdsByFixture().entrySet()) {
            if (!pinsById.containsKey(entry.getKey())) {
                continue;
            }
            MemberIdSet expectedSet = entry.getValue();
            MemberIdSet actualSet = MemberIdSet.of();
            if (plan != null) {
                FixtureDataset dataset = datasets.get(entry.getKey());
                FixtureExecutionServices services = host.fixtureServices(entry.getKey());
                if (dataset == null || services == null) {
                    throw EvaluationRunException.of(
                            EvaluationRunErrorCodes.PIN_MISSING,
                            "A Case fixture has no loaded dataset or host service");
                }
                verifyFixturePins(pinsById.get(entry.getKey()), services);
                try {
                    FixtureExecutionResult result = FIXTURE_EXECUTOR.execute(
                            plan, dataset, services.mapping(), services.scopeBinding(), services.fxRateSnapshot());
                    if (!result.planHash().equals(plan.planHash())) {
                        // The genuine executor ran a different plan than the one observed, which is an
                        // incoherence in the observation boundary rather than a fixture mapping miss.
                        throw EvaluationRunException.of(
                                EvaluationRunErrorCodes.OBSERVATION_FAILURE,
                                "The genuine fixture execution did not execute the observed Query Plan");
                    }
                    actualSet = result.memberIdSet();
                } catch (EvaluationRunException incoherent) {
                    throw incoherent;
                } catch (RuntimeException fixtureRefused) {
                    // A mapping, scope or FX miss is a fixture failure: it is recorded against the attempt and
                    // never inflates the run into INVALID, exactly as section 6 requires.
                    failed = true;
                }
            }
            comparisons.add(new EvaluationReport.MemberSetComparison(
                    entry.getKey(),
                    CANONICAL_JSON.hash(expectedSet),
                    CANONICAL_JSON.hash(actualSet),
                    expectedSet.size(),
                    actualSet.size(),
                    symmetricDifference(expectedSet, actualSet)));
        }
        return new MemberSetOutcome(comparisons, failed);
    }

    /**
     * Refuses a fixture service whose content hashes no longer match the ones frozen at run start.
     *
     * <p>This is the "pins must not change during execution" rule applied to the only host objects the run
     * consults more than once: a swapped mapping, scope binding or FX snapshot is a moved pin, not a result.
     */
    private static void verifyFixturePins(
            EvaluationCasePins.FixturePin pinned, FixtureExecutionServices services) {
        if (pinned == null) {
            throw EvaluationRunException.of(
                    EvaluationRunErrorCodes.PIN_MISSING, "A Case fixture is missing from the frozen pins");
        }
        if (!pinned.mappingHash().equals(services.mapping().mappingHash())
                || !pinned.scopeBindingHash().equals(services.scopeBinding().bindingHash())
                || !pinned.fxHash().equals(services.fxRateSnapshot().contentHash())) {
            throw EvaluationRunException.of(
                    EvaluationRunErrorCodes.PIN_CHANGED,
                    "A frozen fixture service pin changed during execution");
        }
    }

    private static int symmetricDifference(MemberIdSet left, MemberIdSet right) {
        int difference = 0;
        for (String member : left.memberIds()) {
            if (!right.contains(member)) {
                difference++;
            }
        }
        for (String member : right.memberIds()) {
            if (!left.contains(member)) {
                difference++;
            }
        }
        return difference;
    }

    // ------------------------------------------------------------------ metrics

    private static Map<MetricId, int[]> tally(List<AttemptRecord> records) {
        Map<MetricId, int[]> tallies = emptyTallies();
        for (AttemptRecord record : records) {
            accumulate(tallies, record);
        }
        return tallies;
    }

    private static Map<MetricId, int[]> emptyTallies() {
        Map<MetricId, int[]> tallies = new LinkedHashMap<>();
        for (MetricId id : MetricId.ordered()) {
            tallies.put(id, new int[4]);
        }
        return tallies;
    }

    private static void accumulate(Map<MetricId, int[]> tallies, AttemptRecord record) {
        for (Map.Entry<MetricId, int[]> entry : tallies.entrySet()) {
            int[] fraction = fraction(entry.getKey(), record);
            entry.getValue()[0] += fraction[0];
            entry.getValue()[1] += fraction[1];
            entry.getValue()[2] += fraction[2];
            entry.getValue()[3] += fraction[3];
        }
    }

    /** @return numerator, denominator, excluded and invalid counts for one attempt and one metric */
    private static int[] fraction(MetricId id, AttemptRecord record) {
        if (record.taxonomy() != null && record.taxonomy().invalidatesRun()) {
            // A run-invalidating attempt is accounted for as invalid in every metric, so it is neither
            // silently dropped from a denominator nor miscounted as an ordinary model or comparison failure.
            return new int[] {0, 0, 0, 1};
        }
        EvaluationReport.ComparisonEvidence evidence = record.evidence();
        ExpectedResult expected = record.expected();
        boolean refinementAttempt = evidence.refinementAttempt();
        switch (id) {
            case INTENT_SLOT_ACCURACY:
                if (refinementAttempt) {
                    // A refinement performs no Intent resolution, so Intent slots are not applicable to it.
                    return new int[] {0, 0, 1, 0};
                }
                return record.outcome().observedStages().contains("INTENT") || evidence.actualIntentSlotCount() > 0
                        ? new int[] {evidence.intentSlotsMatched() ? 1 : 0, 1, 0, 0}
                        : new int[] {0, 0, 1, 0};
            case CANONICAL_AST_EXACT_MATCH:
                return evidence.actualCanonicalAstHash() != null
                        ? new int[] {evidence.canonicalAstMatched() ? 1 : 0, 1, 0, 0}
                        : new int[] {0, 0, 1, 0};
            case MEMBER_SET_EXACT_MATCH:
                if (evidence.fixtureExecutionFailed()) {
                    return new int[] {0, 0, 1, 0};
                }
                return evidence.memberSets().isEmpty()
                        ? new int[] {0, 0, 1, 0}
                        : new int[] {evidence.memberSetsMatched() ? 1 : 0, 1, 0, 0};
            case CLARIFICATION_RECALL:
                if (refinementAttempt) {
                    return new int[] {0, 0, 1, 0};
                }
                return expected.expectedDisposition() == Disposition.NEEDS_INPUT
                        ? new int[] {evidence.clarificationCodesMatched() ? 1 : 0, 1, 0, 0}
                        : new int[] {0, 0, 1, 0};
            case CLARIFICATION_PRECISION:
                if (refinementAttempt) {
                    return new int[] {0, 0, 1, 0};
                }
                return evidence.actualClarificationQuestionCodes().isEmpty()
                        ? new int[] {0, 0, 1, 0}
                        : new int[] {evidence.clarificationCodesMatched() ? 1 : 0, 1, 0, 0};
            case UNSUPPORTED_RECALL:
                return expected.expectedDisposition() == Disposition.UNSUPPORTED
                        ? new int[] {evidence.dispositionMatched() ? 1 : 0, 1, 0, 0}
                        : new int[] {0, 0, 1, 0};
            case DENIED_RECALL:
                return expected.expectedDisposition() == Disposition.DENIED
                        ? new int[] {evidence.dispositionMatched() ? 1 : 0, 1, 0, 0}
                        : new int[] {0, 0, 1, 0};
            case SILENT_CRITICAL_ASSUMPTION_RATE:
                return expected.forbiddenAssumptions().isEmpty()
                        ? new int[] {0, 0, 1, 0}
                        : new int[] {evidence.forbiddenAssumptionsMatched() ? 0 : 1, 1, 0, 0};
            case FIRST_PASS_SUCCESS:
                if (refinementAttempt) {
                    // A refinement makes no provider call, so a first pass is not applicable to it.
                    return new int[] {0, 0, 1, 0};
                }
                return new int[] {record.outcome().firstPassAccepted() ? 1 : 0, 1, 0, 0};
            case ONE_REPAIR_SUCCESS:
                // Only an eligible schema-repair attempt belongs in this denominator: an attempt outside a
                // permitted one-repair flow is excluded rather than counted as a failed repair.
                return record.outcome().repairAttempted()
                        ? new int[] {record.outcome().repairSucceeded() ? 1 : 0, 1, 0, 0}
                        : new int[] {0, 0, 1, 0};
            case REPEAT_CONSISTENCY:
                // Defined once per high-risk Case, on its first attempt, never once per attempt.
                if (record.repeats() <= 1 || record.attemptNumber() != 1) {
                    return new int[] {0, 0, 1, 0};
                }
                return new int[] {record.repeatConsistent() ? 1 : 0, 1, 0, 0};
            case TOOL_RESOLUTION_ERROR_RATE:
                return new int[] {
                        record.taxonomy() == FailureTaxonomy.TOOL_ADMISSION_OR_RESOLUTION_FAILURE ? 1 : 0,
                        1, 0, 0};
            case REFINEMENT_TARGET_ACCURACY:
                return expected.refinementTarget() == null
                        ? new int[] {0, 0, 1, 0}
                        : new int[] {evidence.refinementMatched() ? 1 : 0, 1, 0, 0};
            default:
                throw EvaluationRunException.of(
                        EvaluationRunErrorCodes.METRIC_INVALID, "An unknown metric identifier was tallied");
        }
    }

    private static List<EvaluationReport.MetricValue> metricValues(Map<MetricId, int[]> tallies) {
        List<EvaluationReport.MetricValue> values = new ArrayList<>();
        for (MetricId id : MetricId.ordered()) {
            values.add(metricValue(id.metricCode(), tallies.get(id)));
        }
        return values;
    }

    private static EvaluationReport.MetricValue metricValue(String metricId, int[] counts) {
        int numerator = counts[0];
        int denominator = counts[1];
        int excluded = counts[2];
        int invalid = counts[3];
        boolean defined = denominator > 0;
        return new EvaluationReport.MetricValue(
                metricId, numerator, denominator, excluded, invalid, defined,
                defined ? ratio(numerator, denominator) : null);
    }

    /** Exact decimal ratio, rendered without a floating-point step; the scale is fixed and deterministic. */
    static String ratio(int numerator, int denominator) {
        return BigDecimal.valueOf(numerator)
                .divide(BigDecimal.valueOf(denominator), RATIO_SCALE, RoundingMode.HALF_UP)
                .stripTrailingZeros()
                .toPlainString();
    }

    private Map<String, List<EvaluationReport.MetricValue>> grouped(
            List<AttemptRecord> records, List<EvaluationReport.CaseOutcome> outcomes, boolean byCategory) {
        Map<String, Map<MetricId, int[]>> groups = new LinkedHashMap<>();
        Map<String, String> caseGroup = new LinkedHashMap<>();
        for (EvaluationReport.CaseOutcome outcome : outcomes) {
            caseGroup.put(outcome.caseId(), byCategory ? outcome.category() : outcome.riskLevel());
        }
        for (AttemptRecord record : records) {
            Map<MetricId, int[]> tallies =
                    groups.computeIfAbsent(caseGroup.get(record.caseId()), key -> emptyTallies());
            accumulate(tallies, record);
        }
        Map<String, List<EvaluationReport.MetricValue>> grouped = new LinkedHashMap<>();
        for (Map.Entry<String, Map<MetricId, int[]>> entry : groups.entrySet()) {
            grouped.put(entry.getKey(), metricValues(entry.getValue()));
        }
        return grouped;
    }

    // ------------------------------------------------------------------ records and helpers

    /** One executed attempt plus everything the metric, taxonomy and repeat layers need. */
    private record AttemptRecord(
            String caseId,
            int attemptNumber,
            Disposition disposition,
            FailureTaxonomy taxonomy,
            ContentHash canonicalAstHash,
            EvaluationReport.AttemptOutcome outcome,
            EvaluationReport.ComparisonEvidence evidence,
            ExpectedResult expected,
            boolean repeatConsistent,
            int repeats
    ) {

        AttemptRecord withRepeat(boolean consistent, int repeats) {
            return new AttemptRecord(
                    caseId, attemptNumber, disposition, taxonomy, canonicalAstHash, outcome, evidence, expected,
                    consistent, repeats);
        }
    }

    private static String plainJson(JsonNode node) {
        if (node == null || node.isNull()) {
            return "";
        }
        return node.isTextual() ? node.textValue() : node.toString();
    }

    private static String plainValue(IntentValue value) {
        if (value == null) {
            return "";
        }
        if (value instanceof IntentValue.ListValue list) {
            List<String> raws = new ArrayList<>();
            list.items().forEach(item -> raws.add(scalarRaw(item)));
            raws.sort(java.util.Comparator.naturalOrder());
            return String.join(",", raws);
        }
        if (value instanceof IntentValue.RangeValue range) {
            return scalarRaw(range.lower()) + ".." + scalarRaw(range.upper());
        }
        if (value instanceof IntentValue.Scalar scalar) {
            return scalarRaw(scalar);
        }
        return "";
    }

    /** The raw text of one scalar, read through the closed union rather than through a shared accessor. */
    private static String scalarRaw(IntentValue.Scalar scalar) {
        return switch (scalar) {
            case IntentValue.StringValue value -> value.raw();
            case IntentValue.EnumValue value -> value.raw();
            case IntentValue.IntegerValue value -> value.raw();
            case IntentValue.DecimalValue value -> value.raw();
            case IntentValue.MoneyValue value -> value.raw();
            case IntentValue.DateValue value -> value.raw();
            case IntentValue.TimestampValue value -> value.raw();
            case IntentValue.BooleanValue value -> Boolean.toString(value.raw());
        };
    }

    private static SourceSpan spanOf(IntentClause clause) {
        if (clause == null || clause.provenance() == null) {
            return null;
        }
        if (clause.provenance() instanceof IntentProvenance.FromSourceSpan from) {
            return from.span();
        }
        return null;
    }
}