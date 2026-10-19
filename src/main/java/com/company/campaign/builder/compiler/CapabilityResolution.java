package com.company.campaign.builder.compiler;

import com.company.campaign.builder.capability.CapabilityPlanPreviewCard;
import com.company.campaign.contracts.Issue;

import java.util.List;
import java.util.Objects;

/**
 * The closed result of one capability-contract invocation.
 *
 * <p>A contract either produces one immutable normalized component or an ordered, bounded issue list. It
 * cannot return both, cannot return null and cannot return a partially normalized value: the compiler fails
 * closed on any other shape. The type is closed so a contract cannot smuggle behavior — a raw command, a
 * callback, a script or a resolved provider handle — through its result.
 */
public sealed interface CapabilityResolution {

    /**
     * A normalized payload plus every catalog reference and preview card the contract resolved.
     *
     * <p>A contract contributes zero cards for an ordinary component and exactly one card per resolved
     * component that has a genuine, already-approved preview to project. The compiler aggregates the cards in
     * manifest order and re-proves that every card binds to a component the merged campaign still holds.
     *
     * <p>The two card lists are independent projections of the same resolution. {@code cards} is the accepted
     * Segment {@link PlanPreviewCard} path; {@code capabilityPreviewCards} is the additive generic T19
     * capability card, whose empty list is the honest default for every contract that projects no such card.
     */
    record Resolved(
            CapabilityPayload value,
            List<ResolvedCatalogReference> references,
            List<PlanPreviewCard> cards,
            List<CapabilityPlanPreviewCard> capabilityPreviewCards) implements CapabilityResolution {

        public Resolved {
            Objects.requireNonNull(value, "value");
            Objects.requireNonNull(references, "references");
            Objects.requireNonNull(cards, "cards");
            Objects.requireNonNull(capabilityPreviewCards, "capabilityPreviewCards");
            for (PlanPreviewCard card : cards) {
                if (card == null) {
                    throw new CompilerException(
                            CompilerErrorCodes.COMPILER_CAPABILITY_CONTRACT_INVALID,
                            "capabilityContractCards",
                            "A capability resolution must not carry a null preview card.");
                }
            }
            for (CapabilityPlanPreviewCard card : capabilityPreviewCards) {
                if (card == null) {
                    throw new CompilerException(
                            CompilerErrorCodes.COMPILER_CAPABILITY_CONTRACT_INVALID,
                            "capabilityContractCapabilityCards",
                            "A capability resolution must not carry a null capability preview card.");
                }
            }
            cards = List.copyOf(cards);
            capabilityPreviewCards = List.copyOf(capabilityPreviewCards);
        }

        /** The T15 shape: a normalized payload and references, contributing no card of either kind. */
        public Resolved(CapabilityPayload value, List<ResolvedCatalogReference> references) {
            this(value, references, List.of(), List.of());
        }

        /**
         * The accepted Segment shape: a normalized payload, references and the accepted plan preview cards,
         * contributing no generic T19 capability preview card.
         */
        public Resolved(
                CapabilityPayload value,
                List<ResolvedCatalogReference> references,
                List<PlanPreviewCard> cards) {
            this(value, references, cards, List.of());
        }
    }

    /** An ordered, bounded list of issues that prevented resolution. */
    record Failed(List<Issue> issues) implements CapabilityResolution {

        public Failed {
            Objects.requireNonNull(issues, "issues");
        }
    }
}