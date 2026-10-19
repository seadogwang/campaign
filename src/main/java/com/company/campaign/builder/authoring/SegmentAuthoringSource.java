package com.company.campaign.builder.authoring;

import com.company.campaign.builder.agent.SegmentAgentResult;
import com.company.campaign.builder.refine.SegmentRefinementResult;
import com.company.campaign.contracts.ContractPatterns;
import com.company.campaign.contracts.Disposition;
import com.company.campaign.segment.resolution.Clarification;

import java.util.Objects;

/**
 * One server-owned source of a rule-based Segment: a closed union of exactly two structurally distinct halves.
 *
 * <p>{@link ResolvedSource} is the resolved half. Every one of its shapes pairs a source result with the exact
 * typed artifact that result claims, so a resolved source always carries a complete, checkable proof:
 *
 * <ul>
 *   <li>{@link AiResolved} — a resolved T11 {@code SegmentAgentResult} together with the T05/T07/T08/T09 proof
 *       it asserts. The result alone is hash-only, so it is never sufficient on its own: the typed proof is the
 *       explicit input that makes the claim checkable, and the integration re-proves that the result's DSL,
 *       canonical, plan, Preview and Skill pins are exactly the proof's.</li>
 *   <li>{@link VisualBuilder} — a Visual Builder typed resolved DSL carrying the same frozen proofs. It has no
 *       separate result to reconcile, because the typed artifact <em>is</em> the resolution.</li>
 *   <li>{@link Refinement} — a successful T12 {@code SegmentRefinementResult} together with the refined proof
 *       it claims, so a refinement preserves the Segment's stable logical identity rather than creating one.</li>
 * </ul>
 *
 * <p>{@link TerminalSource} is the terminal half. A {@code NEEDS_INPUT}, {@code UNSUPPORTED}, {@code DENIED} or
 * {@code INVALID} result intentionally carries no DSL, validation, semantic plan or Preview artifact, so a
 * terminal source carries <strong>no proof at all</strong>: forcing a caller to attach an unrelated, valid proof
 * to a non-resolved outcome would be a dishonest requirement, and no terminal fixture has to manufacture one. A
 * terminal source is refused by the integration before any payload, Proposal or compile exists.
 *
 * <p>The union is sealed so no unproven or invented source can be admitted, and none of its shapes carries a
 * conversation identifier, model trace, provider output or presentation detail on the semantic path: those live
 * inside the source result, which the integration reads only to re-prove the pins.
 */
public sealed interface SegmentAuthoringSource {

    /** The closed source kind, for audit and routing only. */
    SegmentAuthoringKind kind();

    /**
     * The resolved half of the union: a source that must carry the exact typed proof of the Segment it claims.
     *
     * <p>Only a resolved source has a {@link #proof()}: the terminal half is structurally incapable of carrying
     * one, so a terminal outcome can never be dressed up as a resolved artifact.
     */
    sealed interface ResolvedSource extends SegmentAuthoringSource {

        /** The typed proof of the resolved Segment this source claims. */
        SegmentAuthoringProof proof();
    }

    /** The terminal half of the union: a non-resolved result that carries no proof and no artifact. */
    sealed interface TerminalSource extends SegmentAuthoringSource {

        /** The one non-resolved disposition this source reached. */
        Disposition disposition();

        /** The bounded machine code the source result reported. */
        String failureCode();

        /** The offered clarification, present only for {@link Disposition#NEEDS_INPUT}. */
        Clarification clarification();
    }

    /** A <em>resolved</em> Segment Agent result paired with the exact typed artifact it claims. */
    record AiResolved(SegmentAgentResult result, SegmentAuthoringProof proof)
            implements ResolvedSource {

        public AiResolved {
            Objects.requireNonNull(result, "result");
            Objects.requireNonNull(proof, "proof");
            requireResolved(Disposition.RESOLVED == result.disposition(), "aiResolvedResult");
        }

        @Override
        public SegmentAuthoringKind kind() {
            return SegmentAuthoringKind.AI;
        }
    }

    /** A Visual Builder typed resolved DSL carrying the same frozen proofs. */
    record VisualBuilder(SegmentAuthoringProof proof) implements ResolvedSource {

        public VisualBuilder {
            Objects.requireNonNull(proof, "proof");
        }

        @Override
        public SegmentAuthoringKind kind() {
            return SegmentAuthoringKind.VISUAL_BUILDER;
        }
    }

    /** A <em>successful</em> stable-node refinement result paired with the refined proof it claims. */
    record Refinement(SegmentRefinementResult result, SegmentAuthoringProof proof)
            implements ResolvedSource {

        public Refinement {
            Objects.requireNonNull(result, "result");
            Objects.requireNonNull(proof, "proof");
            requireResolved(Disposition.RESOLVED == result.disposition(), "refinementResult");
        }

        @Override
        public SegmentAuthoringKind kind() {
            return SegmentAuthoringKind.REFINEMENT;
        }
    }

    /** A <em>non-resolved</em> Segment Agent result: a terminal outcome that carries no later artifact. */
    record AiTerminal(SegmentAgentResult result) implements TerminalSource {

        public AiTerminal {
            Objects.requireNonNull(result, "result");
            requireTerminal(result.disposition(), result.failureCode(), result.clarification());
        }

        @Override
        public SegmentAuthoringKind kind() {
            return SegmentAuthoringKind.AI;
        }

        @Override
        public Disposition disposition() {
            return result.disposition();
        }

        @Override
        public String failureCode() {
            return result.failureCode();
        }

        @Override
        public Clarification clarification() {
            return result.clarification();
        }
    }

    /** A <em>non-resolved</em> refinement result: a terminal outcome that carries no refined artifact. */
    record RefinementTerminal(SegmentRefinementResult result) implements TerminalSource {

        public RefinementTerminal {
            Objects.requireNonNull(result, "result");
            // A refinement result never carries a clarification: only a NEEDS_INPUT Agent outcome offers one.
            requireTerminal(result.disposition(), result.failureCode(), null);
        }

        @Override
        public SegmentAuthoringKind kind() {
            return SegmentAuthoringKind.REFINEMENT;
        }

        @Override
        public Disposition disposition() {
            return result.disposition();
        }

        @Override
        public String failureCode() {
            return result.failureCode();
        }

        @Override
        public Clarification clarification() {
            // A refinement result carries no clarification, so a refinement never offers one either.
            return null;
        }
    }

    /** A resolved variant must carry a resolved result; a terminal variant must not. */
    private static void requireResolved(boolean resolved, String invariant) {
        if (!resolved) {
            throw new SegmentAuthoringException(
                    SegmentAuthoringErrorCodes.SOURCE_MISMATCH,
                    invariant,
                    "A resolved Segment source must carry a resolved source result.");
        }
    }

    /**
     * The closed terminal rules both terminal variants share.
     *
     * <p>The outcome must be non-resolved and carry one bounded machine code, and a clarification may accompany
     * only {@code NEEDS_INPUT}. A terminal source therefore can neither masquerade as a resolved one nor leak
     * arbitrary text through its code.
     */
    private static void requireTerminal(Disposition disposition, String failureCode, Clarification clarification) {
        if (disposition == null || disposition.isResolved() || failureCode == null
                || !failureCode.matches(ContractPatterns.MACHINE_CODE)) {
            throw new SegmentAuthoringException(
                    SegmentAuthoringErrorCodes.TERMINAL_OUTCOME,
                    "terminalOutcome",
                    "A terminal Segment source must carry one bounded non-resolved outcome.");
        }
        if (clarification != null && disposition != Disposition.NEEDS_INPUT) {
            throw new SegmentAuthoringException(
                    SegmentAuthoringErrorCodes.TERMINAL_OUTCOME,
                    "terminalClarification",
                    "Only a clarification outcome may carry the offered clarification.");
        }
    }
}