package com.company.campaign.builder.agent;

import com.company.campaign.builder.refine.SegmentRefinementReceipt;
import com.company.campaign.preview.PreviewResponse;
import com.company.campaign.query.plan.SemanticQueryPlan;
import com.company.campaign.segment.dsl.contract.SegmentDsl;
import com.company.campaign.segment.intent.SegmentIntent;
import com.company.campaign.segment.resolution.ResolutionResult;
import com.company.campaign.segment.validation.SegmentValidationResult;
import com.company.campaign.shared.canonical.ContentHash;

/**
 * The host-owned, no-op-by-default evaluation observation boundary of the admitted Agent and refinement stages.
 *
 * <p>It exists because the model-visible result of a formal run publishes only safe hashes and Preview
 * summaries, while an evaluation must compare typed Intent slots, candidate resolution, canonical AST,
 * validation issues, Query Plan structure and refinement targeting. An observer is the smallest boundary that
 * can hand those already-admitted typed artifacts to a host that is allowed to see them.
 *
 * <p>Every guarantee is structural rather than promised:
 * <ul>
 *   <li>It is disabled by default: every method has a no-op body, and an environment that injects nothing
 *       behaves exactly as before.</li>
 *   <li>It is not model-visible and not caller-controlled: no {@code AgentEnvelope}, model request, Tool
 *       payload or model output can name, replace or reach it.</li>
 *   <li>It cannot change a stage result: every method returns {@code void}, receives already-constructed
 *       immutable artifacts, and is invoked only after the genuine stage admitted or accepted the artifact.
 *       The adapter holds no branch whose outcome depends on an observation.</li>
 *   <li>It is unavailable to production result DTOs, audit records and logs: no result, audit projection or
 *       log statement in this codebase carries an observer or any artifact it received.</li>
 *   <li>It never receives raw provider output, credentials or unrestricted trusted context: only parsed,
 *       admitted typed artifacts are handed over.</li>
 * </ul>
 *
 * <p>A throwing observer is a fail-closed condition: both adapters treat an observation failure as an
 * {@code INVALID} terminal rather than continuing with an unobserved stage.
 */
public interface SegmentStageObserver {

    /** The disabled boundary: an environment that injects this behaves exactly as an unobserved environment. */
    SegmentStageObserver NOOP = new SegmentStageObserver() {
    };

    /** One strict-schema-accepted T10 Intent, observed after the codec accepted it. */
    default void intentAccepted(SegmentIntent intent, ContentHash intentHash) {
    }

    /**
     * One genuine T10 resolution, observed after the {@code intent.resolve} Tool produced it.
     *
     * <p>It is handed over for every deterministic verdict the Tool returned, including a
     * {@code NEEDS_INPUT} clarification and a denial, so the exact question code and the selected/rejected
     * candidates of a non-resolved outcome are comparable evidence rather than invisible.
     */
    default void resolutionAccepted(ResolutionResult resolution) {
    }

    /** One accepted host-owned T05 DSL, observed after the deterministic compiler resolved it. */
    default void dslCompiled(SegmentDsl document) {
    }

    /** One atomically applied T12 refinement result, observed after the editor accepted it. */
    default void refinementApplied(SegmentDsl document) {
    }

    /**
     * The typed receipt of one atomically accepted T12 patch, observed after the editor accepted it.
     *
     * <p>It carries the ordered accepted operation identities and the base/refined editable hashes, so an
     * evaluation can compare the refinement the run actually applied instead of inferring it from a diff.
     */
    default void refinementReceiptAccepted(SegmentRefinementReceipt receipt) {
    }

    /**
     * One genuine T07 validation result, observed after the {@code dsl.validate} Tool produced it.
     *
     * <p>It is handed over for both a valid and an invalid result: the deterministic issue-code sequence of an
     * invalid or denied validation is itself expected evidence an evaluation must be able to compare.
     */
    default void validationAccepted(SegmentValidationResult validation) {
    }

    /** One admitted T08 Query Plan, observed after the {@code plan.build} Tool admitted it. */
    default void planAccepted(SemanticQueryPlan plan) {
    }

    /** One admitted T09C Safe Preview, observed after the {@code preview.safe} Tool admitted it. */
    default void previewAccepted(PreviewResponse preview, SegmentPreviewCard card) {
    }

    /** One admitted grounded explanation, observed after the {@code explanation.grounded} Tool admitted it. */
    default void explanationAccepted(GroundedExplanation explanation) {
    }
}