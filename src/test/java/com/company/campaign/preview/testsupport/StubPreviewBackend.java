package com.company.campaign.preview.testsupport;

import com.company.campaign.preview.PreviewBackendIdentity;
import com.company.campaign.preview.PreviewBackendResult;
import com.company.campaign.preview.PreviewConditionStatistic;
import com.company.campaign.preview.PreviewExecutionBackend;
import com.company.campaign.query.plan.SemanticQueryPlan;
import com.company.campaign.security.context.TrustedExecutionContext;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A test-only read-only backend with a pluggable behaviour.
 *
 * <p>It exists so tests can prove the application service refuses an incoherent backend result, maps backend
 * unavailability and timeouts, and never calls a backend after a validation or planning rejection. It is
 * never a production bean and carries no fixture, provider or mutation behaviour.
 *
 * <p>Its immutable {@link PreviewBackendIdentity} is constructed from the exact identifier and version it was
 * given, and {@link #coherent} publishes results carrying those same values unless a test deliberately supplies
 * different ones, so identity-coherence cases can be exercised precisely.
 */
public final class StubPreviewBackend implements PreviewExecutionBackend {

    /** The version every stub result carries unless a test names another one. */
    public static final String DEFAULT_VERSION = "stub-v1";

    /** One execution behaviour: a coherent result, an incoherent result or a bounded backend failure. */
    @FunctionalInterface
    public interface Behavior {
        PreviewBackendResult apply(SemanticQueryPlan plan, TrustedExecutionContext context);
    }

    private final PreviewBackendIdentity identity;
    private final Behavior behavior;
    private final AtomicInteger calls = new AtomicInteger();

    public StubPreviewBackend(Behavior behavior) {
        this("preview-stub-backend", DEFAULT_VERSION, behavior);
    }

    public StubPreviewBackend(String backendId, Behavior behavior) {
        this(backendId, DEFAULT_VERSION, behavior);
    }

    public StubPreviewBackend(String backendId, String backendVersion, Behavior behavior) {
        this.identity = new PreviewBackendIdentity(backendId, backendVersion);
        this.behavior = behavior;
    }

    @Override
    public PreviewBackendIdentity identity() {
        return identity;
    }

    @Override
    public PreviewBackendResult execute(SemanticQueryPlan plan, TrustedExecutionContext context) {
        calls.incrementAndGet();
        return behavior.apply(plan, context);
    }

    /** How many times the backend was invoked. */
    public int callCount() {
        return calls.get();
    }

    /** A coherent result for the supplied plan and context that matches the exact member ids. */
    public static PreviewBackendResult coherent(
            SemanticQueryPlan plan,
            TrustedExecutionContext context,
            List<String> memberIds,
            String backendId,
            String backendVersion) {
        return PreviewBackendResult.publish(
                com.company.campaign.evaluation.segment.contract.MemberIdSet.of(memberIds.toArray(String[]::new)),
                List.of(new PreviewConditionStatistic("r", memberIds.size(), 0, 0)),
                plan.planHash(),
                context.snapshotRef().metadataSnapshotId(),
                context.dataScopeId(),
                context.snapshotRef().asOf(),
                context.snapshotRef().timezone().getId(),
                context.snapshotRef().dataWatermark(),
                backendId,
                backendVersion);
    }
}