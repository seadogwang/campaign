package com.company.campaign.lifecycle.testsupport;

import com.company.campaign.builder.authoring.SegmentAuthoringInputs;
import com.company.campaign.builder.authoring.SegmentAuthoringIntegration;
import com.company.campaign.builder.authoring.SegmentAuthoringProof;
import com.company.campaign.builder.authoring.SegmentAuthoringReferenceContext;
import com.company.campaign.builder.authoring.SegmentAuthoringReferencePins;
import com.company.campaign.builder.authoring.SegmentAuthoringReferenceReadiness;
import com.company.campaign.builder.authoring.SegmentAuthoringRequest;
import com.company.campaign.builder.authoring.SegmentAuthoringResult;
import com.company.campaign.builder.authoring.testsupport.SegmentAuthoringFixtures;
import com.company.campaign.builder.authoring.testsupport.SegmentAuthoringIntegrationFixtures;
import com.company.campaign.builder.compiler.CampaignCompiler;
import com.company.campaign.builder.compiler.CampaignPlan;
import com.company.campaign.builder.compiler.CapabilityCompileContext;
import com.company.campaign.builder.compiler.PlanPreviewCard;
import com.company.campaign.builder.compiler.ReferencePlanEvidence;
import com.company.campaign.builder.proposal.ProposalIdentity;
import com.company.campaign.builder.testsupport.BuilderFixtures;
import com.company.campaign.evaluation.segment.contract.GateOutcome;
import com.company.campaign.evaluation.segment.gate.GateApprovalRecord;
import com.company.campaign.evaluation.segment.gate.GateOwnerApproval;
import com.company.campaign.evaluation.segment.gate.GateOwnerRole;
import com.company.campaign.evaluation.segment.gate.ReferenceApprovalLookupPort;
import com.company.campaign.evaluation.segment.gate.ReferenceApprovedDecision;
import com.company.campaign.evaluation.segment.gate.ReferenceGateDecision;
import com.company.campaign.evaluation.segment.gate.ReferenceReadinessInputs;
import com.company.campaign.lifecycle.LifecycleAuditSink;
import com.company.campaign.lifecycle.PreparePublishResult;
import com.company.campaign.lifecycle.ReferencePreparationFacts;
import com.company.campaign.lifecycle.ReferencePreparationLookupPort;
import com.company.campaign.lifecycle.ResolvedReferenceDependencies;
import com.company.campaign.query.plan.AudienceVersionDependency;
import com.company.campaign.segment.reference.AudienceVersionRef;
import com.company.campaign.segment.validation.testsupport.SegmentValidationFixtures;
import com.company.campaign.shared.canonical.ContentHash;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Shared fixtures for the focused T18 reference-readiness-at-Prepare tests.
 *
 * <p>A reference-bearing Plan is always a genuine product of the accepted shared Segment authoring
 * integration over the genuine host reference ports: nothing here hand-assembles a card, a plan hash or a
 * reference evidence slot. The only synthetic values are the <em>approved</em> reference Gate decision and
 * the host resolution answer, because no approved reference Gate decision exists in the checked-in code and
 * the point of these tests is to prove what a production path does when a host does hold one. No default
 * approval is ever supplied: a case that is not explicitly wired fails closed.
 */
public final class ReferencePublishTestFixtures {

    private static final SegmentAuthoringIntegration INTEGRATION =
            new SegmentAuthoringIntegration(new CampaignCompiler(SegmentValidationFixtures.allowAll()));

    /** The trusted instant a synthetic reference approval was decided at. */
    public static final Instant DECIDED_AT = Instant.parse("2026-10-07T09:00:00Z");

    private ReferencePublishTestFixtures() {
    }

    // ------------------------------------------------------------------ host reference inputs

    /** The current host reference Gate inputs: the corpus, the policy, the contract and the graph inputs. */
    public static ReferenceReadinessInputs inputs() {
        return ReferenceReadinessInputs.current(
                "reference-corpus-1", "reference-corpus/1.0", hash("reference-corpus"),
                hash("reference-policy"), hash("catalog-directory-contract"),
                hash("dependency-graph-inputs"));
    }

    /** The same reference inputs with exactly one changed component, standing in for any drift. */
    public static ReferenceReadinessInputs otherInputs() {
        return ReferenceReadinessInputs.current(
                "reference-corpus-1", "reference-corpus/1.0", hash("reference-corpus"),
                hash("reference-policy"), hash("catalog-directory-contract"),
                hash("other-dependency-graph-inputs"));
    }

    // ------------------------------------------------------------------ the reference-bearing plan

    /** A genuine reference-bearing Plan and the exact host resolution of its own dependency closure. */
    public record ReferencePlanArtifacts(CampaignPlan plan, ResolvedReferenceDependencies dependencies) {
    }

    /**
     * Compiles a genuine reference-bearing Segment Plan whose card pins exactly the given reference inputs.
     *
     * <p>The base capability readiness is {@code ACTIVE}, so the accepted base Segment activation is
     * satisfiable for this plan and a test can isolate the dedicated reference activation instead of failing
     * in the base guard first.
     */
    public static ReferencePlanArtifacts referencePlanArtifacts(ContentHash referenceInputsHash) {
        SegmentAuthoringProof proof = SegmentAuthoringIntegrationFixtures.referenceProof();
        ContentHash dependenciesHash =
                SegmentAuthoringIntegrationFixtures.referenceDependenciesHash(proof.plan());
        SegmentAuthoringReferencePins pins =
                SegmentAuthoringReferenceReadiness.developmentPins(dependenciesHash, referenceInputsHash);
        SegmentAuthoringRequest request = new SegmentAuthoringRequest(
                SegmentAuthoringIntegrationFixtures.ai(proof),
                ProposalIdentity.ofClientRef(SegmentAuthoringFixtures.CLIENT_REF), null,
                SegmentAuthoringIntegrationFixtures.catalog(), BuilderFixtures.policy(),
                BuilderFixtures.instants(), SegmentAuthoringFixtures.context(),
                new CapabilityCompileContext(
                        List.of(SegmentAuthoringFixtures.active()),
                        List.of(new SegmentAuthoringInputs(
                                SegmentAuthoringFixtures.snapshot(), proof.previewCard(), proof.skillHash(),
                                pins,
                                SegmentAuthoringReferenceContext.of(
                                        SegmentAuthoringIntegrationFixtures.referenceExecutionContext())))));
        CampaignPlan plan = ((SegmentAuthoringResult.Compiled) INTEGRATION.author(request)).plan();

        List<AudienceVersionRef> versions = new ArrayList<>();
        for (AudienceVersionDependency dependency : proof.plan().audienceDependencies()) {
            versions.add(dependency.reference());
        }
        return new ReferencePlanArtifacts(plan, new ResolvedReferenceDependencies(
                ReferencePlanEvidence.CAPABILITY_ID, dependenciesHash, versions));
    }

    /** The single reference-bearing card of a plan, which must exist for every reference-bearing plan. */
    public static ReferencePlanEvidence cardEvidence(CampaignPlan plan) {
        ReferencePlanEvidence found = null;
        for (PlanPreviewCard card : plan.previewCards()) {
            if (card.referenceEvidence() != null) {
                found = card.referenceEvidence();
            }
        }
        if (found == null) {
            throw new IllegalStateException("the fixture plan carries no reference evidence");
        }
        return found;
    }

    /** A host resolution answer for some other dependency closure, for the dependency-drift cases. */
    public static ResolvedReferenceDependencies otherDependencies() {
        return new ResolvedReferenceDependencies(
                ReferencePlanEvidence.CAPABILITY_ID, hash("other-reference-dependencies"),
                List.of());
    }

    // ------------------------------------------------------------------ synthetic reference approvals

    /** A synthetic exact approved {@code PASSED} reference decision over exactly these inputs. */
    public static ReferenceApprovedDecision approvedPassed(ReferenceReadinessInputs over) {
        return approvedOf(ReferencePlanEvidence.CAPABILITY_ID, GateOutcome.PASSED, over);
    }

    /** A synthetic approved reference decision bound to one capability, verdict and input revision. */
    public static ReferenceApprovedDecision approvedOf(
            String capabilityId, GateOutcome outcome, ReferenceReadinessInputs over) {
        ReferenceGateDecision decision =
                ReferenceGateDecision.of("reference-decision-1", outcome, over, DECIDED_AT);
        return new ReferenceApprovedDecision(
                capabilityId, decision,
                GateApprovalRecord.of(decision.decisionHash(), decision.outcome(), quorum()));
    }

    /**
     * A synthetic exact approved {@code PASSED} reference decision over the same inputs but under a different
     * decision identity.
     *
     * <p>It stands in for a superseding approval: the host's latest approved reference decision is real and
     * {@code ACTIVE}, but it is not the one a preparation froze, so a Publish that re-requires the frozen
     * decision must refuse it rather than silently upgrade to it.
     */
    public static ReferenceApprovedDecision supersedingApproval(ReferenceReadinessInputs over) {
        ReferenceGateDecision decision =
                ReferenceGateDecision.of("reference-decision-2", GateOutcome.PASSED, over, DECIDED_AT);
        return new ReferenceApprovedDecision(
                ReferencePlanEvidence.CAPABILITY_ID, decision,
                GateApprovalRecord.of(decision.decisionHash(), decision.outcome(), quorum()));
    }

    /** A synthetic approval that is still pending: only one of the three owner roles has approved. */
    public static ReferenceApprovedDecision pendingApproval(ReferenceReadinessInputs over) {
        ReferenceGateDecision decision =
                ReferenceGateDecision.of("reference-decision-1", GateOutcome.PASSED, over, DECIDED_AT);
        return new ReferenceApprovedDecision(
                ReferencePlanEvidence.CAPABILITY_ID, decision,
                GateApprovalRecord.of(decision.decisionHash(), decision.outcome(),
                        List.of(new GateOwnerApproval("owner-product-1", GateOwnerRole.PRODUCT, DECIDED_AT))));
    }

    private static List<GateOwnerApproval> quorum() {
        return List.of(
                new GateOwnerApproval("owner-product-1", GateOwnerRole.PRODUCT, DECIDED_AT),
                new GateOwnerApproval("owner-data-1", GateOwnerRole.DATA, DECIDED_AT),
                new GateOwnerApproval("owner-test-1", GateOwnerRole.TEST, DECIDED_AT));
    }

    // ------------------------------------------------------------------ host ports that never default

    /** A host that holds no reference resolution for the plan at all. */
    public static ReferencePreparationLookupPort missingFacts() {
        return plan -> Optional.empty();
    }

    /** A host whose reference resolver cannot answer. */
    public static ReferencePreparationLookupPort failingFacts() {
        return plan -> {
            throw new IllegalStateException("reference resolution unavailable");
        };
    }

    /** A host that holds no approved reference decision at all: the honest default of the current code. */
    public static ReferenceApprovalLookupPort missingApproval() {
        return capabilityId -> Optional.empty();
    }

    /** A host whose reference approval store cannot answer. */
    public static ReferenceApprovalLookupPort failingApproval() {
        return capabilityId -> {
            throw new IllegalStateException("reference approval lookup unavailable");
        };
    }

    /** A host that answers with exactly this approval, or with nothing when it is absent. */
    public static ReferenceApprovalLookupPort approval(ReferenceApprovedDecision approved) {
        return capabilityId -> Optional.ofNullable(approved);
    }

    /** A host that resolves exactly these facts for the plan. */
    public static ReferencePreparationLookupPort facts(ReferencePreparationFacts resolved) {
        return plan -> Optional.of(resolved);
    }

    private static ContentHash hash(String seed) {
        return ContentHash.sha256(seed.getBytes(StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------------ the prepared scenario

    /**
     * One reference-bearing Prepare Publish scenario: a genuine reference plan, the Draft a real host Apply
     * saved from it, the exact host resolution of its dependencies, and the host ports the boundary asks.
     *
     * <p>A case is only wired with an approval when a test asks for one. The default is deliberately the empty
     * host port, so a test that forgets to wire evidence proves a refusal rather than an accidental success.
     */
    public static final class ReferenceCase {

        public final CampaignPlan plan;
        public final ResolvedReferenceDependencies dependencies;
        public final ReferenceReadinessInputs referenceInputs;
        public final PublishScenario scenario;

        private ReferenceCase(
                CampaignPlan plan,
                ResolvedReferenceDependencies dependencies,
                ReferenceReadinessInputs referenceInputs,
                PublishScenario scenario) {
            this.plan = plan;
            this.dependencies = dependencies;
            this.referenceInputs = referenceInputs;
            this.scenario = scenario;
        }

        /** A reference-bearing case whose plan pins exactly these reference inputs and wires no approval. */
        public static ReferenceCase of(ReferenceReadinessInputs pinned) {
            return of(pinned, new com.company.campaign.lifecycle.InMemoryLifecycleAuditSink());
        }

        /** The same case whose store writes its audit events through the supplied sink. */
        public static ReferenceCase of(ReferenceReadinessInputs pinned, LifecycleAuditSink auditSink) {
            ReferencePlanArtifacts artifacts = referencePlanArtifacts(pinned.contentHash());
            PublishScenario scenario = PublishScenario.of(artifacts.plan(), auditSink)
                    .withReferencePorts(
                            facts(new ReferencePreparationFacts(artifacts.dependencies(), pinned)),
                            missingApproval());
            return new ReferenceCase(
                    artifacts.plan(), artifacts.dependencies(), pinned, scenario);
        }

        /** The genuine production case: the plan, its resolution and an exact approved activation. */
        public static ReferenceCase approved() {
            ReferenceCase base = of(inputs());
            return base.withApproval(approvedPassed(inputs()));
        }

        /** The same genuine production case whose store writes its audit events through the supplied sink. */
        public static ReferenceCase approved(LifecycleAuditSink auditSink) {
            ReferenceCase base = of(inputs(), auditSink);
            return base.withApproval(approvedPassed(inputs()));
        }

        /** Wires exactly this reference approval. */
        public ReferenceCase withApproval(ReferenceApprovedDecision approved) {
            return withPorts(
                    facts(new ReferencePreparationFacts(dependencies, referenceInputs)), approval(approved));
        }

        /** Wires an explicit approval port, keeping the genuine resolution. */
        public ReferenceCase withApprovalPort(ReferenceApprovalLookupPort port) {
            return withPorts(facts(new ReferencePreparationFacts(dependencies, referenceInputs)), port);
        }

        /** Wires an explicit resolution port, keeping an exact approved activation over the current inputs. */
        public ReferenceCase withFactsPort(ReferencePreparationLookupPort port) {
            return withPorts(port, approval(approvedPassed(referenceInputs)));
        }

        /**
         * Reports a different current reference revision than the plan froze.
         *
         * <p>The host resolution and the approval both move together, so the host is internally coherent and
         * the only disagreement is with the plan's own frozen evidence.
         */
        public ReferenceCase withCurrentReferenceInputs(ReferenceReadinessInputs replaced) {
            return withPorts(
                    facts(new ReferencePreparationFacts(dependencies, replaced)),
                    approval(approvedPassed(replaced)));
        }

        /** Removes every reference port, as a boundary built before the reference extension existed. */
        public ReferenceCase withoutReferenceWiring() {
            return withPorts(null, null);
        }

        private ReferenceCase withPorts(
                ReferencePreparationLookupPort facts, ReferenceApprovalLookupPort approvals) {
            scenario.withReferencePorts(facts, approvals);
            return this;
        }

        public PreparePublishResult prepare() {
            return scenario.prepare();
        }

        /** The card-bound reference evidence the plan itself froze; the exact-match target of the boundary. */
        public ReferencePlanEvidence cardEvidence() {
            return ReferencePublishTestFixtures.cardEvidence(plan);
        }
    }
}