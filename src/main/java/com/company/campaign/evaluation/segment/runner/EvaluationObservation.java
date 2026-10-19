package com.company.campaign.evaluation.segment.runner;

import com.company.campaign.builder.agent.GroundedExplanation;
import com.company.campaign.builder.agent.SegmentPreviewCard;
import com.company.campaign.builder.agent.SegmentStageObserver;
import com.company.campaign.builder.refine.SegmentRefinementReceipt;
import com.company.campaign.preview.PreviewResponse;
import com.company.campaign.query.plan.SemanticQueryPlan;
import com.company.campaign.segment.dsl.contract.SegmentDsl;
import com.company.campaign.segment.intent.SegmentIntent;
import com.company.campaign.segment.resolution.ResolutionResult;
import com.company.campaign.segment.validation.SegmentValidationResult;
import com.company.campaign.shared.canonical.ContentHash;

import java.util.ArrayList;
import java.util.List;

/**
 * The Evaluation Runner's own implementation of the host-owned observation boundary for one attempt.
 *
 * <p>It is created per attempt, injected through the environment, and read only by the runner that created it.
 * It is a plain value collector: it stores the exact immutable typed artifacts the genuine stages admitted, and
 * exposes them to the comparison boundary. It holds no member identifier — exact member sets are produced by
 * the runner's own genuine T09 execution and never travel through this object.
 */
public final class EvaluationObservation implements SegmentStageObserver {

    /** The closed set of stages an attempt may be observed at, in pipeline order. */
    public enum ObservedStage {
        INTENT,
        RESOLUTION,
        DSL,
        REFINEMENT,
        REFINEMENT_RECEIPT,
        VALIDATION,
        PLAN,
        PREVIEW,
        EXPLANATION
    }

    private final List<ObservedStage> stages = new ArrayList<>();

    private SegmentIntent intent;
    private ContentHash intentHash;
    private ResolutionResult resolution;
    private SegmentDsl document;
    private SegmentDsl refined;
    private SegmentRefinementReceipt refinementReceipt;
    private SegmentValidationResult validation;
    private SemanticQueryPlan plan;
    private PreviewResponse preview;
    private SegmentPreviewCard card;
    private GroundedExplanation explanation;

    @Override
    public void intentAccepted(SegmentIntent acceptedIntent, ContentHash acceptedIntentHash) {
        this.intent = acceptedIntent;
        this.intentHash = acceptedIntentHash;
        stages.add(ObservedStage.INTENT);
    }

    @Override
    public void resolutionAccepted(ResolutionResult acceptedResolution) {
        this.resolution = acceptedResolution;
        stages.add(ObservedStage.RESOLUTION);
    }

    @Override
    public void dslCompiled(SegmentDsl compiled) {
        this.document = compiled;
        stages.add(ObservedStage.DSL);
    }

    @Override
    public void refinementApplied(SegmentDsl applied) {
        this.refined = applied;
        stages.add(ObservedStage.REFINEMENT);
    }

    @Override
    public void refinementReceiptAccepted(SegmentRefinementReceipt receipt) {
        this.refinementReceipt = receipt;
        stages.add(ObservedStage.REFINEMENT_RECEIPT);
    }

    @Override
    public void validationAccepted(SegmentValidationResult acceptedValidation) {
        this.validation = acceptedValidation;
        stages.add(ObservedStage.VALIDATION);
    }

    @Override
    public void planAccepted(SemanticQueryPlan acceptedPlan) {
        this.plan = acceptedPlan;
        stages.add(ObservedStage.PLAN);
    }

    @Override
    public void previewAccepted(PreviewResponse acceptedPreview, SegmentPreviewCard acceptedCard) {
        this.preview = acceptedPreview;
        this.card = acceptedCard;
        stages.add(ObservedStage.PREVIEW);
    }

    @Override
    public void explanationAccepted(GroundedExplanation acceptedExplanation) {
        this.explanation = acceptedExplanation;
        stages.add(ObservedStage.EXPLANATION);
    }

    /** The observed stages in pipeline order. */
    public List<ObservedStage> stages() {
        return List.copyOf(stages);
    }

    public SegmentIntent intent() {
        return intent;
    }

    public ContentHash intentHash() {
        return intentHash;
    }

    public ResolutionResult resolution() {
        return resolution;
    }

    public SegmentDsl document() {
        return document;
    }

    public SegmentDsl refinedDocument() {
        return refined;
    }

    /** The typed receipt of the refinement patch the genuine T12 editor accepted, or {@code null}. */
    public SegmentRefinementReceipt refinementReceipt() {
        return refinementReceipt;
    }

    public SegmentValidationResult validation() {
        return validation;
    }

    public SemanticQueryPlan plan() {
        return plan;
    }

    public PreviewResponse preview() {
        return preview;
    }

    public SegmentPreviewCard previewCard() {
        return card;
    }

    public GroundedExplanation explanation() {
        return explanation;
    }
}