package com.company.campaign.builder.tool;

import com.company.campaign.builder.agent.SegmentPreviewCard;
import com.company.campaign.contracts.ToolInput;
import com.company.campaign.metadata.domain.MetadataSnapshot;
import com.company.campaign.metadata.service.MetadataQueryService;
import com.company.campaign.preview.PreviewResponse;
import com.company.campaign.query.plan.SegmentQueryPlanner;
import com.company.campaign.query.plan.SemanticQueryPlan;
import com.company.campaign.segment.dsl.contract.SegmentDsl;
import com.company.campaign.segment.intent.RegisteredDefaultSource;
import com.company.campaign.segment.intent.SegmentIntent;
import com.company.campaign.segment.reference.AudienceReferenceDirectoryContext;
import com.company.campaign.segment.resolution.ClarificationSelectionReceipt;
import com.company.campaign.segment.resolution.IntentResolver;
import com.company.campaign.segment.resolution.ResolutionPolicy;
import com.company.campaign.segment.resolution.ResolutionResult;
import com.company.campaign.segment.validation.SegmentValidationPolicy;
import com.company.campaign.segment.validation.SegmentValidationResult;
import com.company.campaign.segment.validation.SegmentValidator;
import com.company.campaign.security.context.TrustedExecutionContext;

import java.util.List;

/**
 * The host-owned, per-run evidence a Tool handler may read.
 *
 * <p>Every accessor is injected by the host run; nothing here is read from a model payload. Trusted context,
 * the exact permitted Metadata snapshot, the deterministic components each handler drives and every artifact an
 * earlier admitted Tool produced are host facts, so a model cannot supply, override or weaken them by shaping a
 * {@link ToolInput}. The interface is read-only: a handler can consume evidence but cannot mutate a run.
 *
 * <p>The accepted deterministic components are exposed here so the <em>handler</em> is the code that really
 * runs the protected operation: {@code intent.resolve} drives the T10 resolver, {@code dsl.validate} drives the
 * T07 validator, {@code plan.build} drives the T08 planner and {@code preview.safe} drives the injected T09C
 * port. A handler whose evidence is missing fails closed with {@code SEGMENT_TOOL_EVIDENCE_MISSING} rather than
 * throwing.
 */
public interface SegmentToolRunContext {

    /** The trusted execution context of this run. */
    TrustedExecutionContext trustedContext();

    /** The exact permitted Metadata snapshot of this run. */
    MetadataSnapshot metadataSnapshot();

    /** The host-owned Metadata discovery/detail service, already bound to the T02 authorization boundary. */
    MetadataQueryService metadataQueryService();

    /** The exact accepted Intent of this run, or null before it exists. */
    SegmentIntent intent();

    /** The host-owned T10 resolver the resolution Tool drives, or null when the run does not expose one. */
    default IntentResolver intentResolver() {
        return null;
    }

    /** The host-owned resolution policy the resolver and DSL compiler are pinned to. */
    default ResolutionPolicy resolutionPolicy() {
        return null;
    }

    /** The host-owned registered defaults the resolver and DSL compiler are pinned to. */
    default RegisteredDefaultSource registeredDefaults() {
        return null;
    }

    /** The exact opaque T10 continuation receipts supplied with the request, in order. */
    default List<ClarificationSelectionReceipt> continuationReceipts() {
        return List.of();
    }

    /**
     * The host-owned Existing Audience Reference directory of this run, or null when the run does not expose one.
     *
     * <p>It is host evidence exactly like the snapshot: a model cannot name it, replace it or widen it. When it
     * is absent, an Intent that asks for an existing audience still fails closed rather than degrading to a
     * guess, which is the accepted reference-free behavior.
     */
    default AudienceReferenceDirectoryContext referenceDirectory() {
        return null;
    }

    /** The host-owned T07 validator the validation Tool drives, or null when the run does not expose one. */
    default SegmentValidator validator() {
        return null;
    }

    /** The host-owned validation policy the validation Tool is pinned to. */
    default SegmentValidationPolicy validationPolicy() {
        return null;
    }

    /** The host-owned T08 planner the planning Tool drives, or null when the run does not expose one. */
    default SegmentQueryPlanner planner() {
        return null;
    }

    /** The injected typed T09C Safe Preview port the Preview Tool drives, or null when none is injected. */
    default SegmentPreviewPort previewPort() {
        return null;
    }

    /** The accepted resolution of this run, or null before the admitted resolution Tool produced it. */
    ResolutionResult resolution();

    /** The accepted DSL document of this run, or null before it exists. */
    SegmentDsl dslDocument();

    /** The accepted validation result of this run, or null before an admitted Tool produced it. */
    SegmentValidationResult validation();

    /** The accepted query plan of this run, or null before an admitted Tool produced it. */
    SemanticQueryPlan plan();

    /** The genuine T09C Preview response of this run, or null before an admitted Tool produced it. */
    PreviewResponse previewResponse();

    /** The safe Preview card derived from the admitted Preview artifact, or null before it exists. */
    default SegmentPreviewCard previewCard() {
        return null;
    }
}