package com.company.campaign.builder.authoring;

import com.company.campaign.builder.compiler.CampaignPlan;
import com.company.campaign.builder.compiler.PlanPreviewCard;
import com.company.campaign.builder.compiler.ResolvedComponent;
import com.company.campaign.builder.proposal.CampaignProposalPatch;
import com.company.campaign.builder.proposal.ProposalOperation;
import com.company.campaign.builder.proposal.ProposalOperationKind;
import com.company.campaign.contracts.ContractPatterns;
import com.company.campaign.contracts.Disposition;
import com.company.campaign.segment.resolution.Clarification;

import java.util.Objects;

/**
 * The closed outcome of one shared Segment authoring submission.
 *
 * <p>There are exactly two shapes. {@link Compiled} carries the one canonical Proposal the source converged
 * on and the Plan the single accepted compiler produced from it, and its constructor <em>binds the pair</em>:
 * the Plan must be the compile of that exact Proposal, must resolve the single UPSERT to the
 * {@code segment.rule-based} component at this exact contract version, and must carry exactly one bound
 * {@code SEGMENT_PREVIEW} card for it. {@link Terminal} carries a bounded, redacted terminal outcome with one
 * machine code and <em>no</em> Proposal, Manifest or Plan: a {@code NEEDS_INPUT}, {@code UNSUPPORTED},
 * {@code DENIED} or {@code INVALID} resolution is preserved honestly instead of being turned into an empty or
 * partial artifact.
 *
 * <p>A {@link Compiled} result additionally carries the dedicated Existing Audience Reference evidence of the
 * compile when the Segment depends on referenced immutable Audience Versions, and carries none when it does
 * not. That evidence is the frozen host readiness the compile ran against, never a declaration a client or a
 * model supplied, and it is development-only: no production reference activation exists to report.
 *
 * <p>The type is sealed so no third, half-produced shape — a payload without a compile, a compile without a
 * proposal, an issue list without a disposition — can be invented by a caller or a later task.
 */
public sealed interface SegmentAuthoringResult {

    /** One canonical Proposal plus the Plan the accepted compiler produced from it. */
    record Compiled(
            CampaignProposalPatch patch,
            CampaignPlan plan,
            SegmentAuthoringReferencePins referencePins) implements SegmentAuthoringResult {

        /**
         * The compiled result of a reference-free Segment, which carries no reference evidence.
         *
         * <p>This is the accepted result shape, unchanged: a Segment that depends on no referenced Audience
         * Version produces exactly the artifacts it always did.
         */
        public Compiled(CampaignProposalPatch patch, CampaignPlan plan) {
            this(patch, plan, null);
        }

        public Compiled {
            Objects.requireNonNull(patch, "patch");
            Objects.requireNonNull(plan, "plan");
            if (patch.operations().size() != 1
                    || patch.operations().get(0).kind() != ProposalOperationKind.UPSERT) {
                throw new SegmentAuthoringException(
                        SegmentAuthoringErrorCodes.REQUEST_INVALID,
                        "compiledOperation",
                        "A compiled Segment submission is exactly one Segment upsert.");
            }
            // The pair is bound to one another: the Plan must actually be the compile of this exact Proposal,
            // it must hold the Segment component the single UPSERT resolved to at this capability's exact
            // contract version, and that component must carry exactly one bound SEGMENT_PREVIEW card. An
            // unrelated patch/plan pair, a plan whose identity resolved to something else, and a plan whose card
            // set is stale are all refused rather than being presented as one compiled result.
            if (!plan.proposalHash().equals(patch.proposalHash())) {
                throw new SegmentAuthoringException(
                        SegmentAuthoringErrorCodes.RESULT_UNBOUND,
                        "compiledProposal",
                        "A compiled result must carry the Plan its own Proposal compiled to.");
            }
            String logicalId = logicalIdOf(patch, plan);
            ResolvedComponent component = logicalId == null ? null : plan.manifest().component(logicalId);
            if (component == null
                    || !SegmentAuthoringVersions.CAPABILITY_CODE.equals(component.capabilityCode())
                    || !SegmentAuthoringVersions.CONTRACT_VERSION.equals(component.contractVersion())) {
                throw new SegmentAuthoringException(
                        SegmentAuthoringErrorCodes.RESULT_UNBOUND,
                        "compiledComponent",
                        "A compiled Segment result must resolve to the rule-based Segment component it named.");
            }
            int cards = 0;
            for (PlanPreviewCard card : plan.previewCards()) {
                if (card.cardKind().equals(PlanPreviewCard.SEGMENT_PREVIEW)
                        && card.componentLogicalId().equals(logicalId)) {
                    cards++;
                }
            }
            if (cards != 1) {
                throw new SegmentAuthoringException(
                        SegmentAuthoringErrorCodes.RESULT_UNBOUND,
                        "compiledCard",
                        "A compiled Segment result must carry exactly one card bound to its component.");
            }
        }

        /**
         * The stable identity the compile bound the Segment to.
         *
         * <p>For an EDIT it is the logical identifier the proposal named; for a CREATE it is the plan-scoped
         * identifier the compiler derived from the {@code clientRef}, which the caller never chose.
         */
        public String logicalId() {
            return logicalIdOf(patch, plan);
        }

        /**
         * True when this compile carried the dedicated Existing Audience Reference evidence.
         *
         * <p>It is present exactly when the Segment depends on referenced immutable Audience Versions, and the
         * evidence is the frozen, development-only reference readiness the compile ran against — never a Gate
         * declaration a client or model supplied.
         */
        public boolean hasReferenceEvidence() {
            return referencePins != null;
        }

        /**
         * The stable identity the single upsert resolved to, read from the pair rather than from the fields.
         *
         * <p>It is a static form because a record's compact constructor runs before its fields are assigned, and
         * the constructor itself must bind the pair through the same identity the accessor reports.
         */
        private static String logicalIdOf(CampaignProposalPatch patch, CampaignPlan plan) {
            ProposalOperation operation = patch.operations().get(0);
            if (operation.target().isLogicalId()) {
                return operation.target().logicalId();
            }
            return plan.clientRefBindings().isEmpty()
                    ? null
                    : plan.clientRefBindings().get(0).logicalId();
        }

        /**
         * A bounded diagnostic: the mode, the operation kind and two counts.
         *
         * <p>It prints no logical identity, no plan identifier, no hash, no payload value and no operand: a
         * diagnostic of a compiled artifact must not become a way to read the artifact's identities.
         */
        @Override
        public String toString() {
            return "SegmentAuthoringResult.Compiled[mode=" + patch.mode()
                    + ", operation=" + patch.operations().get(0).kind()
                    + ", components=" + plan.manifest().components().size()
                    + ", cards=" + plan.previewCards().size()
                    + ", reference=" + (referencePins == null ? "none" : referencePins.readinessState())
                    + ']';
        }
    }

    /** One bounded terminal outcome with no Segment payload, Manifest or Plan. */
    record Terminal(
            Disposition disposition,
            String code,
            Clarification clarification,
            SegmentAuthoringKind sourceKind) implements SegmentAuthoringResult {

        public Terminal {
            Objects.requireNonNull(disposition, "disposition");
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(sourceKind, "sourceKind");
            if (disposition.isResolved()) {
                throw new SegmentAuthoringException(
                        SegmentAuthoringErrorCodes.TERMINAL_OUTCOME,
                        "terminalDisposition",
                        "Only a non-resolved disposition is a terminal integration outcome.");
            }
            if (!code.matches(ContractPatterns.MACHINE_CODE)) {
                // A terminal code is a machine code the host branches on, never free text that could carry a
                // rejected payload, a provider message or a business literal into a diagnostic.
                throw new SegmentAuthoringException(
                        SegmentAuthoringErrorCodes.TERMINAL_OUTCOME,
                        "terminalCode",
                        "A terminal integration outcome must carry one bounded machine code.");
            }
            if (clarification != null && disposition != Disposition.NEEDS_INPUT) {
                throw new SegmentAuthoringException(
                        SegmentAuthoringErrorCodes.TERMINAL_OUTCOME,
                        "terminalClarification",
                        "Only a clarification outcome may carry the offered clarification.");
            }
        }

        /** A bounded diagnostic: the disposition, the machine code and never the clarification's content. */
        @Override
        public String toString() {
            return "SegmentAuthoringResult.Terminal[disposition=" + disposition
                    + ", code=" + code
                    + ", sourceKind=" + sourceKind
                    + ", clarification=" + (clarification != null) + ']';
        }
    }
}